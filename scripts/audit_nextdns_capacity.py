#!/usr/bin/env python3
"""Read-only NextDNS rewrite capacity and overwrite audit.

The upstream DnsConf Java implementation is unchanged. This preflight intentionally
rejects over-capacity or conflicting profiles before it can make partial writes.
"""
import ipaddress
import json
import os
import sys
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen

MAX_REWRITES = 500
BLOCK_IPS = {"0.0.0.0", "127.0.0.1", "::1"}


class CapacityBlocked(ValueError):
    """A predictable unsafe-to-write state, not a broken read-only audit."""


def live_writes_authorized():
    event = os.getenv("EVENT_NAME", "")
    if event == "schedule":
        return os.getenv("UPSTREAM_AUTORUN_ENABLED", "").lower() == "true"
    if event == "workflow_dispatch":
        return os.getenv("CONFIRM_WRITE", "").lower() == "true"
    return False


def fetch_text(url, *, headers=None):
    if urlsplit(url).scheme != "https":
        raise ValueError("Only HTTPS source URLs are supported")
    with urlopen(Request(url, headers=headers or {}), timeout=30) as response:
        body = response.read(8_000_001)
    if len(body) > 8_000_000:
        raise ValueError("Source exceeds the 8 MB read limit")
    return body.decode("utf-8-sig")


def domains_from_hosts(text):
    domains = set()
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip().lower()
        if not line:
            continue
        columns = line.split()
        if len(columns) != 2 or columns[0] in BLOCK_IPS:
            continue
        try:
            ipaddress.ip_address(columns[0])
        except ValueError:
            continue
        domain = columns[1].removeprefix("www.")
        if domain:
            domains.add(domain)
    return domains


def excludes_from_env(text):
    return {part.strip().lower().removeprefix("www.")
            for part in text.split(",") if part.strip()}


def is_excluded(domain, excludes):
    return any(domain == item or domain.endswith("." + item) for item in excludes)


def audit():
    provider = os.getenv("DNS", "").strip().upper()
    if provider != "NEXTDNS":
        raise ValueError(f"Expected one NextDNS provider, got {provider!r}")
    profile = os.getenv("CLIENT_ID", "").strip()
    secret = os.getenv("AUTH_SECRET", "").strip()
    sources = [part.strip() for part in os.getenv("REDIRECT", "").split(",") if part.strip()]
    if not profile or not secret or not sources:
        raise ValueError("CLIENT_ID, AUTH_SECRET and REDIRECT must be configured")

    wanted = set()
    for source in sources:
        wanted |= domains_from_hosts(fetch_text(source))
    exclusions = excludes_from_env(os.getenv("EXCLUDE_REDIRECT", ""))
    desired = {domain for domain in wanted if not is_excluded(domain, exclusions)}

    url = f"https://api.nextdns.io/profiles/{profile}/rewrites"
    payload = json.loads(fetch_text(url, headers={"X-Api-Key": secret, "Accept": "application/json"}))
    existing = payload.get("data")
    if not isinstance(existing, list):
        raise ValueError("NextDNS rewrites response has no data array")
    old_by_name = {}
    for item in existing:
        if not isinstance(item, dict) or not isinstance(item.get("name"), str):
            raise ValueError("Unexpected NextDNS rewrites item")
        old_by_name.setdefault(item["name"].lower(), set()).add(str(item.get("content", "")))
    current_names = set(old_by_name)
    potentially_deleted_exclusions = sum(is_excluded(n, exclusions) for n in current_names)
    estimated_unique_total = len(current_names | desired)
    matched = current_names & desired
    existing_only = current_names - desired
    new_only = desired - current_names
    prospective_additions = len(new_only)
    excess_source = max(0, len(desired) - MAX_REWRITES)
    # A capacity lower bound, never permission to delete or adopt an existing record.
    minimum_retire = (max(0, len(existing) + prospective_additions - MAX_REWRITES)
                      if excess_source == 0 else "N/A (source exceeds limit)")

    summary = (
        "### NextDNS read-only capacity preflight\n"
        f"- Current rewrites: **{len(existing)}**\n"
        f"- Unique domains in REDIRECT sources after original www normalization: **{len(wanted)}**\n"
        f"- Desired domains after EXCLUDE_REDIRECT: **{len(desired)}**\n"
        f"- Domains already present in both source and profile: **{len(matched)}**\n"
        f"- Existing domains absent from current source (review candidates): **{len(existing_only)}**\n"
        f"- Minimum existing entries to retire for full source: **{minimum_retire}**\n"
        f"- Domains by which current source alone exceeds capacity: **{excess_source}**\n"
        f"- New domain names required: **{prospective_additions}**\n"
        f"- Conservative combined domain count: **{estimated_unique_total}**\n"
        f"- Existing domains affected by EXCLUDE_REDIRECT: **{potentially_deleted_exclusions}**\n"
        f"- NextDNS limit per profile: **{MAX_REWRITES}**\n"
        "- This audit performs no NextDNS mutations.\n"
    )
    print(summary)
    if path := os.getenv("GITHUB_STEP_SUMMARY"):
        with open(path, "a", encoding="utf-8") as output:
            output.write(summary)

    # We cannot distinguish manually-created rules with the original upstream.
    # The conservative count intentionally avoids any cleanup to make a profile fit.
    if len(existing) > MAX_REWRITES or len(desired) > MAX_REWRITES or estimated_unique_total > MAX_REWRITES:
        raise CapacityBlocked(
            "Capacity exceeded. The original upstream would fail with listLimitExceeded. "
            "Curate REDIRECT and reconcile legacy rules before enabling scheduled writes."
        )
    if potentially_deleted_exclusions:
        raise CapacityBlocked(
            "Existing rewrites match EXCLUDE_REDIRECT. Original upstream would delete them. "
            "Review those exclusions before enabling scheduled writes."
        )


if __name__ == "__main__":
    try:
        audit()
    except CapacityBlocked as error:
        message = f"NextDNS writes blocked: {error}"
        if live_writes_authorized():
            print(message, file=sys.stderr)
            sys.exit(1)
        print(f"::warning::{message} Preview only; no write was attempted.")
        if path := os.getenv("GITHUB_STEP_SUMMARY"):
            with open(path, "a", encoding="utf-8") as output:
                output.write(f"\n**Preview only: live synchronization blocked.** {error}\n")
        sys.exit(0)
    except (HTTPError, URLError, ValueError, json.JSONDecodeError) as error:
        print(f"NextDNS audit FAILED: {type(error).__name__}: {error}", file=sys.stderr)
        sys.exit(1)
