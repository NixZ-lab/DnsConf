#!/usr/bin/env python3
"""Activate scheduled NextDNS rewrites only after a complete 4-profile migration."""
import json
import os
from urllib.request import Request, urlopen

REPO = "NixZ-lab/DnsConf"
WORKFLOW = ".github/workflows/migrate_nextdns.yml"


def migration_completed(runs):
    return any(
        run.get("conclusion") == "success"
        and run.get("status") == "completed"
        and run.get("head_branch") == "main"
        and run.get("event") in {"push", "workflow_dispatch"}
        and run.get("path") == WORKFLOW
        for run in runs
    )


def main():
    url = (f"https://api.github.com/repos/{REPO}/actions/workflows/"
           "migrate_nextdns.yml/runs?status=success&per_page=30")
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "nixz-dnsconf/1"}
    token = os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    with urlopen(Request(url, headers=headers), timeout=30) as response:
        payload = json.load(response)
    if not isinstance(payload.get("workflow_runs"), list):
        raise RuntimeError("Unexpected GitHub workflow history response")
    activated = migration_completed(payload["workflow_runs"])
    if output := os.environ.get("GITHUB_OUTPUT"):
        with open(output, "a", encoding="utf-8") as stream:
            stream.write(f"activated={'true' if activated else 'false'}\n")
    print("Four-profile migration verified: " + ("yes" if activated else "no"),
          flush=True)


if __name__ == "__main__":
    main()
