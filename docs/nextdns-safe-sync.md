# Safe NextDNS rewrite synchronization

This fork uses a reviewed plan and an ownership journal instead of treating every existing rewrite as DnsConf-owned. The four environments remain `DNS`, `DNS_WIFE`, `DNS_DIRECTOR`, and `DNS_PV`. Their `DNS`, `REDIRECT`, `EXCLUDE_REDIRECT`, `DONOR_DNS`, `BLOCK`, `CLIENT_ID`, and `AUTH_SECRET` settings are not changed by this update.

## Rollout

1. Review and merge the update. Keep the old scheduled workflow disabled until the update is on `main`; then enable it. The updated daily schedule at 01:30 UTC produces **read-only previews**. It never applies changes automatically.
2. Run the workflow on `main` with `mode=dry-run` and `environment=ALL`. The job log reports creation, deletion and manual-conflict counts. Each environment gets an encrypted preview artifact containing the full API response pages, a detailed plan and the previous ownership state.
3. Download and decrypt the relevant preview locally to review it. On first use, every pre-existing rewrite is unmanaged, including existing matching hosts entries. Do not assume a rule is owned based on an IP such as `217.60.245.219` or `217.60.245.233`.
4. To migrate old DnsConf rules, review their exact IDs, names and contents in `plan.json` against your previous DnsConf configuration/history and known manual rules. Run `mode=adopt`, one environment, that `review_run_id`, and the exact comma-separated `adopt_ids`. Set `initialize=true` only if no previous journal exists. Adoption changes the journal only; it sends **no POST or DELETE to NextDNS**. Uncertain rules must remain unmanaged. The 65 rules already manually deleted must not be recreated or imported from an old backup.
5. After adoption, make a **new dry-run**. Review `create`, `delete`, `conflicts`, `desired`, and `before`. A conflict means a manual rule overlaps the proposed domain (including wildcard and parent/child matches); that domain is preserved and no new rule is inserted over it.
6. Apply one environment at a time using `mode=apply`, the new `review_run_id`, and `max_delete` equal to the reviewed upper bound. Default `max_delete=0` prohibits any deletion. For the first addition-only run with no journal, `initialize=true` explicitly starts an empty journal.
7. Run another preview to confirm the expected result, then repeat the migration/review for the other environments. Review the other environments independently even when they use the same hosts source.

Apply/adopt is restricted to `main`. A successful preview must use the **same code commit**. Any changes to the live rewrite snapshot or the ownership journal invalidate the preview. A code update also requires a new preview. `REDIRECT` and exclusions are deliberately frozen in the reviewed target: run a new preview after changing environment settings.

## What is managed

`state.json` contains a schema version, profile ID and exact `{id, name, content, enabled}` records last written by DnsConf or explicitly adopted. Only an exact match of the current API record with the journal can be changed or removed. Changing a managed value or disabling a rule manually relinquishes ownership. An externally deleted ID is dropped from the journal. Manually recreating the same domain with a new ID is not adoption.

An owned rule can be removed when its domain disappears from all successful sources, becomes excluded, changes IP (including an EU/US switch), or is an owned duplicate. `EXCLUDE_REDIRECT` uses domain-label boundaries, so `ai.com` does not exclude `openai.com`. Exclusions never authorize deletion of a manual rule.

Source order is first-wins. Parsing preserves `www`, wildcard domains, aliases and IPv6; it does not collapse `www.example.com` into `example.com`. Each source must return a successful HTTP response and contain valid, usable redirect rows. Malformed rows, HTTP errors, empty/comment-only sources, or an empty target after exclusions stop the run. Even if one of several sources is empty, no partial target is used. A syntactically valid truncated source cannot always be detected; the reviewed deletion budget is the final guard against unexpected source shrinkage.

An empty `REDIRECT` is an error, never an instruction to clear the profile. There is no remove-all entry point for rewrites. This workflow is deliberately scoped to NextDNS **Rewrites**. `BLOCK`, when set, is previewed only; rewrite apply does not alter the denylist. All four configured environments had no `BLOCK` source at the time of this update. Cloudflare continues to use its separate existing runner and is not supported by this workflow.

## Backups, journals and privacy

Artifacts are encrypted because artifacts in a public GitHub repository must not be treated as private. The helper uses OpenSSL AES-256-CBC with a random salt, PBKDF2 and encrypt-then-HMAC-SHA256; encryption and authentication keys are separately derived from `AUTH_SECRET` and the environment name. No API key is written into an artifact. Tampered artifacts and artifacts from another environment/key fail authentication before decryption.

`nextdns-preview-ENV` contains `preview.bin`. After decryption it contains:

- `<profile>/backup.json`: complete original API response pages, including fields not used by the planner.
- `<profile>/state-before.json`: previous ownership journal.
- `<profile>/plan.json`: original snapshot and exact proposed operations.

`nextdns-state-ENV` contains `state.bin`, with the most recent checkpointed `state.json`. The workflow attempts to upload this journal even after an API failure. State is restored only from this workflow's `main` runs, including failed runs with useful checkpoints. Branch/PR artifacts cannot become ownership state. The latest expired/corrupt journal is an error; the helper never silently falls back to an older journal. A first run without state preserves everything already present.

Artifacts expire after 90 days. Export and securely retain the encrypted journal and its decryption key. Before rotating `AUTH_SECRET`, decrypt/export state with the old key and arrange a reviewed migration; old artifacts cannot be decrypted by the new key. Losing a journal never justifies inferring ownership from domain names or proxy IPs.

To download/decrypt a preview from a checkout at the same commit, using your own NextDNS API key in the local environment:

```bash
export GITHUB_REPOSITORY=NixZ-lab/DnsConf
export GITHUB_SHA="$(git rev-parse HEAD)"
export SYNC_DIR=nextdns-sync
read -r -s -p 'NextDNS API key: ' AUTH_SECRET
export AUTH_SECRET
python3 scripts/sync_artifacts.py plan DNS --run-id REVIEWED_RUN_ID
unset AUTH_SECRET
```

Use the correct environment name and an actual numeric run ID. The helper needs Python 3, OpenSSL and authenticated `gh`. Decrypted files have mode 0600 and are git-ignored. Inspect them locally; do not commit snapshots or paste credentials into issue/PR bodies. For an already downloaded `preview.bin` or `state.bin`, the `crypt(..., decrypt=True)` and `extract(...)` helpers in `scripts/sync_artifacts.py` can recover the files using the same environment/key.

## Failure and recovery

The client follows pagination, rejects malformed/error envelopes (including HTTP 200 with `errors`), paces requests sequentially, and has HTTP timeouts. HTTP 429, transport errors and ambiguous mutation outcomes stop the run; POST/DELETE are not blindly retried. Workflow jobs are serialized across environments and branches to reduce shared-account rate limits.

New replacements are created before old rules are deleted. Failed creation therefore preserves the working old route. Each acknowledged write checkpoints ownership to an atomic local file. Before each deletion the live profile is rechecked. Avoid editing the profile during apply: NextDNS does not provide an atomic compare-and-delete transaction, so there is still a small race between the final read and a deletion.

On failure, retain the encrypted preview and latest state artifact. Inspect the profile, rerun a preview, and review the new plan. A crash between remote creation and local checkpoint can leave an unowned addition; it is preserved and requires explicit adoption after review. A failed delete may leave both old and new values; the next plan can remove the old **owned** value. Do not reset the journal or run a bulk profile overwrite.

For rollback, compare the backup against the current snapshot, restore only the specific removed/changed records after review, and reconcile their new API IDs into the journal. There is intentionally no automatic full-profile restore: that could erase intervening manual rules. The Git history retains both the pre-update fork and upstream merge parents for code rollback, but restoring the old unsafe workflow is not a profile recovery strategy.

## Validation

Run `mvn -B clean package` with JDK 26, `python3 -m unittest discover -s scripts -p 'test_*.py'`, and `actionlint .github/workflows/*.yml`. Tests use synthetic profiles and local HTTP servers. They do not mutate a live NextDNS profile. Read-only previews and offline source parsing are additional checks; no live apply is needed to validate this change.

The remaining iOS `ISRG Root X2` warning is a separate unresolved issue. Updating DNS synchronization does not establish its cause or prove it is fixed.
