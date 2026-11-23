#!/usr/bin/env python3
"""One-time, user-authorized NextDNS rewrite migration.

Backup precedes all mutations; the backup is encrypted and authenticated.
Only rewrites are touched. All other NextDNS profile settings are untouched.
"""
import argparse
import hashlib
import hmac
import ipaddress
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode
from urllib.request import Request, urlopen

MAX_REWRITES = 500
SOURCE = "https://raw.githubusercontent.com/NixZ-lab/my-dns-config/{ref}/hosts"
BLOCK_IPS = {"0.0.0.0", "127.0.0.1", "::1"}
PREFIX = b"NIXZMIG1"


class MigrationError(Exception):
    pass


def canonical(obj):
    return json.dumps(obj, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode()


def checksum(obj):
    return hashlib.sha256(canonical(obj)).hexdigest()


def api(method, url, secret=None, body=None):
    headers = {"Accept": "application/json", "User-Agent": "nixz-dnsconf-migration/1"}
    if secret:
        headers["X-Api-Key"] = secret
    if body is not None:
        headers["Content-Type"] = "application/json"
    request = Request(url, headers=headers, method=method,
                      data=canonical(body) if body is not None else None)
    for attempt in range(4):
        try:
            with urlopen(request, timeout=45) as response:
                raw = response.read(8_000_001)
                if len(raw) > 8_000_000:
                    raise MigrationError("Unexpectedly large API response")
                result = json.loads(raw) if raw.strip() else {}
                if isinstance(result, dict) and result.get("errors"):
                    codes = [str(e.get("code", "unknown")) for e in result["errors"]]
                    raise MigrationError(f"NextDNS {method} returned API errors: {codes}")
                return result
        except HTTPError as exc:
            if exc.code == 429 and attempt < 3:
                delay = min(90, max(15, int(exc.headers.get("Retry-After", "60"))))
                print(f"Rate limited; retrying after {delay}s", flush=True)
                time.sleep(delay)
                continue
            # Do not echo the body: it may contain DNS profile data.
            raise MigrationError(f"NextDNS {method}: HTTP {exc.code}") from None
        except URLError as exc:
            raise MigrationError(f"Network failure during {method}; remote state must be reviewed") from None
    raise MigrationError("API retry limit exceeded")


def profile_records(profile, secret):
    base = f"https://api.nextdns.io/profiles/{quote(profile, safe='')}/rewrites"
    result = []
    cursor = None
    cursors = set()
    while True:
        path = base + ("?" + urlencode({"cursor": cursor}) if cursor else "")
        response = api("GET", path, secret)
        records = response.get("data")
        if not isinstance(records, list):
            raise MigrationError("NextDNS did not return a rewrites data array")
        result.extend(records)
        cursor = ((response.get("meta") or {}).get("pagination") or {}).get("cursor")
        if not cursor:
            break
        if cursor in cursors:
            raise MigrationError("Repeated pagination cursor")
        cursors.add(cursor)
    if len(result) > MAX_REWRITES:
        raise MigrationError("Profile unexpectedly exceeds the 500-rewrite limit")
    ids = []
    for item in result:
        if (not isinstance(item, dict) or not isinstance(item.get("id"), str)
                or not isinstance(item.get("name"), str)
                or not isinstance(item.get("content"), str)):
            raise MigrationError("Unexpected rewrite record structure")
        ids.append(item["id"])
    if len(set(ids)) != len(ids):
        raise MigrationError("Duplicate rewrite IDs in API response")
    return result


def desired_hosts(ref, exclusions):
    if len(ref) != 40 or any(c not in "0123456789abcdef" for c in ref):
        raise MigrationError("Host source must be pinned to a full Git commit SHA")
    req = Request(SOURCE.format(ref=ref), headers={"User-Agent": "nixz-dnsconf-migration/1"})
    with urlopen(req, timeout=45) as response:
        raw = response.read(8_000_001)
    if len(raw) > 8_000_000:
        raise MigrationError("Hosts source is too large")
    rules = {}
    ignored = [x.strip().lower().removeprefix("www.")
               for x in exclusions.split(",") if x.strip()]
    for raw_line in raw.decode("utf-8-sig").splitlines():
        cols = raw_line.split("#", 1)[0].strip().lower().split()
        if len(cols) != 2 or cols[0] in BLOCK_IPS:
            continue
        try:
            ipaddress.ip_address(cols[0])
        except ValueError:
            continue
        domain = cols[1].removeprefix("www.")
        if (not domain or domain in rules
                or any(domain == ex or domain.endswith("." + ex) for ex in ignored)):
            continue
        rules[domain] = cols[0]
    if not 1 <= len(rules) <= MAX_REWRITES:
        raise MigrationError(f"Desired list has {len(rules)} unique domains; expected 1..500")
    return rules


def snapshot_digest(records):
    return checksum(sorted(records, key=lambda r: r["id"]))


def key_material(environment):
    secret = os.environ.get("AUTH_SECRET", "")
    if len(secret) < 12:
        raise MigrationError("Missing or unexpectedly short AUTH_SECRET")
    return hashlib.pbkdf2_hmac("sha256", secret.encode(),
                               ("nixz-rewrite-migration:" + environment).encode(), 200_000, 64)


def cipher(data, password, decrypt=False):
    env = dict(os.environ, NIXZ_BACKUP_PASSWORD=password.hex())
    cmd = ["openssl", "enc", "-aes-256-cbc", "-salt", "-pbkdf2", "-iter", "200000",
           "-pass", "env:NIXZ_BACKUP_PASSWORD"]
    if decrypt:
        cmd += ["-d"]
    result = subprocess.run(cmd, input=data, capture_output=True, env=env)
    if result.returncode != 0:
        raise MigrationError("Unable to decrypt/authenticate migration backup")
    return result.stdout


def seal(data, environment):
    keys = key_material(environment)
    encrypted = cipher(canonical(data), keys[:32])
    return PREFIX + hmac.digest(keys[32:], PREFIX + encrypted, "sha256") + encrypted


def unseal(data, environment):
    keys = key_material(environment)
    n = len(PREFIX)
    if (not data.startswith(PREFIX) or len(data) <= n + 32
            or not hmac.compare_digest(data[n:n + 32],
                                       hmac.digest(keys[32:], PREFIX + data[n + 32:], "sha256"))):
        raise MigrationError("Backup authentication failed: wrong profile/key or tampered file")
    return json.loads(cipher(data[n + 32:], keys[:32], decrypt=True))


def env_settings():
    environment = os.environ.get("MATRIX_ENVIRONMENT", "")
    if environment not in {"DNS", "DNS_WIFE", "DNS_DIRECTOR", "DNS_PV"}:
        raise MigrationError("Invalid environment")
    if os.environ.get("DNS", "").upper() != "NEXTDNS":
        raise MigrationError("Environment is not NextDNS")
    if os.environ.get("DONOR_DNS", "").strip():
        raise MigrationError("DONOR_DNS is not supported by the managed Rewrites workflow")
    if os.environ.get("BLOCK", "").strip():
        raise MigrationError("BLOCK is configured; rewrite-only reconciliation must not silently ignore it")
    profile = os.environ.get("CLIENT_ID", "")
    secret = os.environ.get("AUTH_SECRET", "")
    if not profile or not secret:
        raise MigrationError("Missing NextDNS profile credentials")
    return environment, profile, secret


def make_backup(ref, filepath):
    environment, profile, secret = env_settings()
    desired = desired_hosts(ref, os.environ.get("EXCLUDE_REDIRECT", ""))
    current = profile_records(profile, secret)
    payload = {
        "version": 1, "environment": environment, "profile": profile,
        "run_id": os.environ.get("GITHUB_RUN_ID", ""), "ref": ref,
        "desired_digest": checksum(desired), "exclusions_digest":
            checksum(os.environ.get("EXCLUDE_REDIRECT", "")),
        "snapshot_digest": snapshot_digest(current), "rewrites": current,
    }
    target = Path(filepath)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(seal(payload, environment))
    target.chmod(0o600)
    print(f"Encrypted fresh backup created for {environment}: {len(current)} existing, "
          f"{len(desired)} desired. No changes made.", flush=True)


def migration_plan(current, desired):
    retained = {}
    to_delete = []
    for record in current:
        name = record["name"].lower()
        if (name in desired and record["content"] == desired[name]
                and record.get("enabled", True) is not False
                and record.get("active", True) is not False
                and name not in retained):
            retained[name] = record
        else:
            to_delete.append(record)
    to_create = [(name, dest) for name, dest in desired.items() if name not in retained]
    return to_delete, to_create


def apply_plan(profile, secret, current, desired, pause=0.8):
    to_delete, to_create = migration_plan(current, desired)
    base = f"https://api.nextdns.io/profiles/{quote(profile, safe='')}/rewrites"
    removable = list(to_delete)
    count = len(current)
    deletions = 0
    additions = 0

    def remove(record):
        nonlocal count, deletions
        time.sleep(pause)
        api("DELETE", f"{base}/{quote(record['id'], safe='')}", secret)
        count -= 1
        deletions += 1
        removable.remove(record)

    for name, ip in to_create:
        # A differing rewrite for this same name must be removed before POST;
        # NextDNS does not support PUT/PATCH for rewrites.
        for record in list(removable):
            if record["name"].lower() == name:
                remove(record)
        if count >= MAX_REWRITES:
            obsolete = next((r for r in removable if r["name"].lower() not in desired), None)
            if obsolete is None:
                raise MigrationError("No safe free slot available before POST")
            remove(obsolete)
        time.sleep(pause)
        api("POST", base, secret, {"name": name, "content": ip})
        count += 1
        additions += 1
        if additions % 50 == 0:
            print(f"Reconciliation progress: {additions}/{len(to_create)} additions", flush=True)

    for record in list(removable):
        remove(record)

    actual = profile_records(profile, secret)
    by_name = {r["name"].lower(): r["content"] for r in actual}
    if (len(actual) != len(desired) or len(by_name) != len(desired)
            or by_name != desired):
        raise MigrationError("Final verification failed; inspect profile against encrypted backup")
    return deletions, additions, len(actual)


def apply_backup(ref, filepath):
    environment, profile, secret = env_settings()
    backup = unseal(Path(filepath).read_bytes(), environment)
    if (backup.get("version") != 1 or backup.get("environment") != environment
            or backup.get("profile") != profile or backup.get("ref") != ref
            or backup.get("run_id") != os.environ.get("GITHUB_RUN_ID", "")):
        raise MigrationError("Backup metadata does not match this execution")
    desired = desired_hosts(ref, os.environ.get("EXCLUDE_REDIRECT", ""))
    if (backup.get("desired_digest") != checksum(desired)
            or backup.get("exclusions_digest") != checksum(os.environ.get("EXCLUDE_REDIRECT", ""))):
        raise MigrationError("Source/exclusion settings changed since backup")
    current = profile_records(profile, secret)
    if snapshot_digest(current) != backup.get("snapshot_digest"):
        raise MigrationError("NextDNS changed since fresh backup: refusing to overwrite")
    if os.environ.get("MIGRATE_CONFIRM") != "REPLACE_ALL_REWRITES":
        raise MigrationError("Missing migration confirmation")
    print(f"Migration authorized for {environment}, {len(current)} -> {len(desired)} rewrites",
          flush=True)
    deleted, created, total = apply_plan(profile, secret, current, desired)
    print(f"Verified {environment}: deleted={deleted}, created={created}, final={total}",
          flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["backup", "apply"])
    parser.add_argument("--ref", required=True)
    parser.add_argument("--file", required=True)
    args = parser.parse_args()
    if args.action == "backup":
        make_backup(args.ref, args.file)
    else:
        apply_backup(args.ref, args.file)


if __name__ == "__main__":
    try:
        main()
    except (MigrationError, ValueError, OSError, HTTPError, URLError,
            json.JSONDecodeError) as exc:
        # Never print profile domains, IDs, raw API replies or credentials.
        print(f"Migration stopped: {type(exc).__name__}: {exc}", file=sys.stderr)
        sys.exit(1)
