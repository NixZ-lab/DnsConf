"""Restore encrypted Actions artifacts with workflow provenance and safe ZIP extraction."""
import argparse
import hashlib
import hmac
import io
import json
import os
from pathlib import Path, PurePosixPath
import subprocess
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
            return sorted(found, key=lambda a: a["id"], reverse=True)
        page += 1


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
    names = {"state.json"} if kind == "state" else {"plan.json", "backup.json", "state-before.json"}
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
    for artifact in artifacts(repo, f"nextdns-state-{environment}"):
        run = api(f"repos/{repo}/actions/runs/{artifact['workflow_run']['id']}")
        if not trusted(run):
            continue
        if artifact["expired"]:
            raise RuntimeError("Latest state expired. Restore a reviewed saved journal; never auto-reset ownership.")
        extract(unseal_artifact(api(f"repos/{repo}/actions/artifacts/{artifact['id']}/zip", True), environment, "state"), destination, {"state.json"})
        print("Restored ownership journal from run", run["id"])
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
            {"plan.json", "backup.json", "state-before.json"})


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
