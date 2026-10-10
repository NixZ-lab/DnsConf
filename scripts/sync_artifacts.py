"""Restore encrypted Actions artifacts with workflow provenance and safe ZIP extraction."""
import argparse
from datetime import datetime
import hashlib
import hmac
import io
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
import shutil
import tempfile
import zipfile

ENVIRONMENTS = {"DNS", "DNS_WIFE", "DNS_DIRECTOR", "DNS_PV"}
WORKFLOW = ".github/workflows/github_action.yml"


def api(path, binary=False):
    result = subprocess.run(["gh", "api", path], check=True, capture_output=True)
    return result.stdout if binary else json.loads(result.stdout)


def trusted(run, main_only=True):
    return (run.get("path") == WORKFLOW
            and run.get("event") in {"workflow_dispatch", "schedule"}
            and (not main_only or run.get("head_branch") == "main"))


def extract(data, destination, allowed):
    destination = Path(destination)
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for item in archive.infolist():
            path = PurePosixPath(item.filename)
            if (path.is_absolute() or ".." in path.parts or len(path.parts) != 2
                    or not all(c in "0123456789abcdef" for c in path.parts[0])
                    or len(path.parts[0]) != 6 or path.name not in allowed
                    or item.file_size > 50_000_000):
                raise ValueError("Unexpected artifact entry")
            target = destination.joinpath(*path.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(archive.read(item))
            target.chmod(0o600)


def artifacts(repo, name):
    page = 1
    found = []
    while True:
        batch = api(f"repos/{repo}/actions/artifacts?name={name}&per_page=100&page={page}")["artifacts"]
        found.extend(a for a in batch if a["name"] == name)
        if len(batch) < 100:
            return found
        page += 1


def timestamp(value):
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        raise ValueError("Journal chronology requires timezone-aware timestamps")
    return parsed


def pages(path, key):
    found = []
    page = 1
    while True:
        batch = api(f"{path}?per_page=100&page={page}")[key]
        found.extend(batch)
        if len(batch) < 100:
            return found
        page += 1


def latest_state(repo, environment):
    """Find the last state-producing attempt, including failed checkpoints.

    Scan run history as well as artifacts: a missing newer journal must not
    silently roll ownership back. A rerun's original created_at/run_number is
    unchanged, so use attempt-specific run_started_at and artifact timestamps.
    """
    prefix = f"repos/{repo}/actions"
    items = artifacts(repo, f"nextdns-state-{environment}")
    runs = pages(f"{prefix}/workflows/github_action.yml/runs", "workflow_runs")
    attempts = []
    for run in runs:
        if not trusted(run):
            continue
        last = run["run_attempt"]
        if str(run["id"]) == os.environ.get("GITHUB_RUN_ID"):
            # The running job has not uploaded its state yet. On a rerun, its
            # previous attempt remains a candidate; never discard that checkpoint.
            last -= 1
        if run["run_attempt"] < 1:
            raise ValueError("Invalid workflow attempt")
        for number in range(last, 0, -1):
            attempt = run if number == run["run_attempt"] else api(f"{prefix}/runs/{run['id']}/attempts/{number}")
            if not trusted(attempt) or attempt["id"] != run["id"] or attempt["run_attempt"] != number:
                raise RuntimeError("Unverified workflow attempt")
            start = timestamp(attempt["run_started_at"])
            if timestamp(attempt["created_at"]) > start or start > timestamp(attempt["updated_at"]):
                raise ValueError("Inconsistent workflow chronology")
            attempts.append((start, attempt))

    latest = None
    for start, run in sorted(attempts, key=lambda pair: pair[0], reverse=True):
        if latest is not None and start < latest[0]:
            break
        jobs = pages(f"{prefix}/runs/{run['id']}/attempts/{run['run_attempt']}/jobs", "jobs")
        names = {f"{environment} / {mode}" for mode in ("sync", "apply", "adopt")}
        mutations = [job for job in jobs if job["name"] in names]
        # Disabled Sync explicitly skips restoring, writing and saving state.
        mutations = [job for job in mutations if not (
            job.get("conclusion") == "success" and any(
                step["name"] == "Restore latest ownership state" and step.get("conclusion") == "skipped"
                for step in job.get("steps", [])))]
        run_items = [a for a in items if a["workflow_run"]["id"] == run["id"]]
        candidates = [a for a in run_items if start <= timestamp(a["created_at"]) <= timestamp(run["updated_at"])]
        if not mutations and not candidates:
            continue
        if latest is not None:
            raise RuntimeError("Ambiguous latest journal workflow chronology")
        if run["status"] != "completed":
            raise RuntimeError("Latest journal workflow attempt is incomplete")
        # Artifacts from previous attempts share the run ID, but not this window.
        if len(candidates) != 1:
            raise RuntimeError("Missing or ambiguous latest ownership journal; never fall back")
        latest = (start, run, candidates[0])

    if latest is None:
        # No history and no artifacts is first onboarding. Existing artifacts
        # without verifiable run provenance must never be treated as an empty state.
        if items:
            raise RuntimeError("Ownership journal has no trusted workflow history")
        return None
    _, run, artifact = latest
    if artifact["expired"]:
        raise RuntimeError("Latest state expired. Restore a reviewed saved journal; never auto-reset ownership.")
    # Conflicting artifact chronology also fails closed, instead of using IDs
    # to break a tie or choosing a checkpoint from an older attempt.
    for other in items:
        if timestamp(other["created_at"]) <= timestamp(artifact["created_at"]):
            continue
        source = api(f"{prefix}/runs/{other['workflow_run']['id']}")
        if trusted(source) and str(source["id"]) != os.environ.get("GITHUB_RUN_ID"):
            raise RuntimeError("Conflicting journal artifact chronology")
    return run, artifact


def crypt(data, environment, decrypt=False):
    secret = os.environ["AUTH_SECRET"].encode()
    if not secret:
        raise ValueError("Artifact encryption requires AUTH_SECRET")
    keys = hashlib.pbkdf2_hmac("sha256", secret, ("dnsconf-v1:" + environment).encode(), 200_000, 64)
    header = b"DNSCONF1"
    if decrypt:
        if (not data.startswith(header) or len(data) < 56
                or not hmac.compare_digest(data[8:40], hmac.digest(keys[32:], header + data[40:], "sha256"))):
            raise ValueError("Artifact authentication failed (wrong key/environment or corrupt artifact)")
        data = data[40:]
    cmd = ["openssl", "enc", "-aes-256-cbc", "-pbkdf2", "-iter", "200000", "-pass", "env:DNSCONF_ARTIFACT_PASSWORD"]
    if decrypt:
        cmd.append("-d")
    env = dict(os.environ, DNSCONF_ARTIFACT_PASSWORD=keys[:32].hex())
    result = subprocess.run(cmd, input=data, capture_output=True, check=True, env=env).stdout
    return result if decrypt else header + hmac.digest(keys[32:], header + result, "sha256") + result


def seal(directory, environment, kind):
    names = {"state.json", "result.json"} if kind == "state" else {"plan.json", "backup.json", "state-before.json", "sources.json"}
    files = [p for p in Path(directory).glob("*/*.json") if p.name in names]
    if not files:
        if kind == "state":
            return
        raise ValueError("No preview to encrypt")
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w", zipfile.ZIP_DEFLATED) as archive:
        for path in files:
            archive.write(path, path.relative_to(directory))
    output = Path("sealed") / (kind + ".bin")
    output.parent.mkdir(exist_ok=True)
    output.write_bytes(crypt(data.getvalue(), environment))
    output.chmod(0o600)


def unseal_artifact(data, environment, kind):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        if archive.namelist() != [kind + ".bin"] or archive.infolist()[0].file_size > 100_000_000:
            raise ValueError("Unexpected encrypted artifact layout")
        return crypt(archive.read(kind + ".bin"), environment, decrypt=True)


def restore(repo, environment, destination):
    selected = latest_state(repo, environment)
    if selected is not None:
        run, artifact = selected
        blob = api(f"repos/{repo}/actions/artifacts/{artifact['id']}/zip", True)
        if artifact.get("digest") != "sha256:" + hashlib.sha256(blob).hexdigest():
            raise ValueError("Latest journal artifact digest mismatch")
        plaintext = unseal_artifact(blob, environment, "state")
        with tempfile.TemporaryDirectory() as staging:
            extract(plaintext, staging, {"state.json", "result.json"})
            states = list(Path(staging).glob("*/state.json"))
            if len(states) != 1:
                raise ValueError("Latest journal must contain exactly one profile state")
            state = states[0]
            data = json.loads(state.read_text())
            if (data.get("version") != 1 or data.get("profile") != state.parent.name
                    or not isinstance(data.get("managed"), list)
                    or (os.environ.get("CLIENT_ID") and data["profile"] != os.environ["CLIENT_ID"])):
                raise ValueError("Invalid latest ownership journal")
            if any(p.parent != state.parent for p in Path(staging).glob("*/*.json")):
                raise ValueError("Unexpected profile in latest journal")
            target = Path(destination) / state.parent.name / "state.json"
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(state, target)
            target.chmod(0o600)
        # Reports belong to their producing run and must never be mistaken for this run's outcome.
        for old_report in Path(destination).glob("*/result.json"):
            old_report.unlink()
        print("Restored ownership journal from run", run["id"], "attempt", run["run_attempt"], "artifact", artifact["id"])
        return True
    print("No ownership journal found: existing rewrites will remain unmanaged.")
    return False


def download_plan(repo, environment, destination, run_id, sha):
    run = api(f"repos/{repo}/actions/runs/{run_id}")
    if not trusted(run, main_only=False) or run.get("head_sha") != sha or run.get("conclusion") != "success":
        raise RuntimeError("Plan must come from a successful preview using this exact code commit")
    candidates = api(f"repos/{repo}/actions/runs/{run_id}/artifacts?per_page=100")["artifacts"]
    matches = [a for a in candidates if a["name"] == f"nextdns-preview-{environment}" and not a["expired"]]
    if len(matches) != 1:
        raise RuntimeError("Missing or ambiguous preview artifact")
    extract(unseal_artifact(api(f"repos/{repo}/actions/artifacts/{matches[0]['id']}/zip", True), environment, "preview"), destination,
            {"plan.json", "backup.json", "state-before.json", "sources.json"})


def main():
    p = argparse.ArgumentParser()
    p.add_argument("action", choices=["state", "plan", "seal-state", "seal-preview"])
    p.add_argument("environment", choices=sorted(ENVIRONMENTS))
    p.add_argument("--run-id", type=int)
    args = p.parse_args()
    repo = os.environ["GITHUB_REPOSITORY"]
    destination = os.environ.get("SYNC_DIR", "nextdns-sync")
    if args.action.startswith("seal-"):
        seal(destination, args.environment, args.action.removeprefix("seal-"))
    elif args.action == "state":
        restored = restore(repo, args.environment, destination)
        if not restored and os.environ.get("DRY_RUN") == "false" and os.environ.get("INITIALIZE") != "true":
            raise RuntimeError("First apply requires initialize=true after reviewing the unmanaged-rule preview")
    else:
        if not args.run_id:
            raise ValueError("An explicit reviewed preview run ID is required")
        download_plan(repo, args.environment, destination, args.run_id, os.environ["GITHUB_SHA"])


if __name__ == "__main__":
    main()
