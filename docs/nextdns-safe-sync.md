# Safe NextDNS rewrite synchronization

This fork uses a reviewed plan and an ownership journal instead of treating every existing rewrite as DnsConf-owned. The four environments remain `DNS`, `DNS_WIFE`, `DNS_DIRECTOR`, and `DNS_PV`. Their `DNS`, `REDIRECT`, `EXCLUDE_REDIRECT`, `DONOR_DNS`, `BLOCK`, `CLIENT_ID`, and `AUTH_SECRET` settings are not changed by this update.

## Everyday operation

Use `mode=preview` to inspect one environment or `ALL` without changing NextDNS. The legacy `dry-run` name remains an alias. Scheduled runs remain read-only. The workflow summary shows one row per selected environment with additions, IP updates, deletions, manual conflicts and outcome.

After **separate, approved onboarding**, update hosts and dispatch `mode=sync`, `environment=ALL`, `initialize=false`. Sync prepares and uploads an encrypted preview, fetches all sources again, validates their body hashes, URLs/order, effective routes, exclusions and code SHA, then rechecks the journal and the full API snapshot before writing. It needs no `review_run_id`. Each environment has independent secrets, state, backup and report; a failed matrix job does not cancel the others.

Sync is disabled by default. An environment without `SYNC_ENABLED=true` is reported as `Not onboarded` and skipped before credentials or API access. Setting that flag with a missing, corrupt, expired or wrong-profile journal fails closed; it never adopts existing rules or initializes ownership. See [onboarding](nextdns-onboarding.md) for the one-time review. This code change does not set flags or change existing environment settings.

| Environment variable | Default | Meaning |
|---|---:|---|
| `SYNC_ENABLED` | `false` | Explicit per-profile rollout approval |
| `SYNC_MAX_CREATE` | `20` | New names per run, excluding IP replacements |
| `SYNC_MAX_UPDATE` | `20` | Owned names with an IP replacement |
| `SYNC_MAX_DELETE` | `20` | All DELETE requests, including IP replacements |
| `SYNC_MAX_DELETE_PERCENT` | `5` | Stale removals as a percentage of journal entries; replacements excluded |
| `SYNC_ALLOW_NEW_WWW` | `false` | Separate authorization for new `www.` names, including `*.www.` wildcard routes |
| `SYNC_ALLOW_NEW_OPENAI` | `false` | Separate authorization for new OpenAI/ChatGPT routes |

A limit violation stops the entire profile before writes. Limits are not silently increased and forbidden additions are not silently filtered. An owned `www`/OpenAI IP replacement counts as an update; a new name requires the corresponding permission. Conservative OpenAI detection covers names containing `openai`, `chatgpt`, `oaistatic` or `oaiusercontent`. If the source proposes new OpenAI names without approval, use reviewed exact exclusions or a separately reviewed plan. Changing flags, exclusions or limits is a distinct rollout decision.

## Exact and subtree exclusions

`EXCLUDE_REDIRECT` accepts comma-separated entries:

| Entry | Meaning |
|---|---|
| `exact:www.openai.com` | Exclude only that name; preserve `openai.com` and other subdomains |
| `tree:openai.com` | Exclude the parent and all subdomains |
| `tree:www.openai.com` | Exclude that literal subtree, without stripping `www.` |
| `openai.com` | Legacy subtree exclusion |
| `www.openai.com` | Legacy normalization: strip one leading `www.`, then exclude the `openai.com` subtree |
| `www.www.openai.com` | Legacy workaround: strip one prefix, then exclude the `www.openai.com` subtree |

Matching ignores case and a trailing dot and respects domain-label boundaries. Unknown prefixes fail closed. Existing unprefixed values retain their previous behavior, including the workaround. Replace a legacy entry with `exact:` or `tree:` only after reviewing the semantic difference in Preview. No existing variables are rewritten automatically. This shared exclusion service also retains legacy behavior in the Cloudflare runner.

The additional `www` names are literal rows already present in hosts. The older pre-safe parser collapsed those names into their parents; the current parser preserves them. Unmanaged parent/subdomain conflicts suppress additions until ownership is deliberately adopted. Adoption can therefore expose previously blocked `www` rows. They must be reviewed independently: matching parent IPs alone do not prove redundancy, and differing IPs require a routing decision. Exact exclusions can prevent unwanted rows without editing hosts.

## Reviewed migration or exceptional apply

1. Run Preview on `main` for one environment. First use treats all existing IDs as unmanaged. Download/decrypt the snapshot privately and review exact ID, name, content and enabled state against history and known manual records. A domain/IP match alone is not proof of ownership.
2. For an approved subset only, dispatch `mode=adopt`, the same commit's successful `review_run_id`, and comma-separated `adopt_ids`. Use `initialize=true` only when no journal exists. Adoption writes the ownership journal and sends no POST or DELETE to NextDNS. Leave uncertain IDs unmanaged.
3. Run a fresh Preview. If a separately reviewed API change is authorized, dispatch `mode=apply`, one environment, that preview's `review_run_id`, `max_delete` equal to the approved bound, and `initialize=false` for an existing journal. Default `max_delete=0` forbids deletion. An explicitly approved first addition-only apply may use `initialize=true`; existing rules stay unmanaged.
4. Run another Preview and verify IDs, complete manual records and journal before enabling ordinary Sync separately.

Adopt/apply are restricted to `main` and one environment. A successful reviewed preview must use the **same code commit**. Changes to the complete live rewrite records or ownership journal invalidate it. The reviewed target is frozen for these legacy modes; only automatic Sync refetches and verifies sources immediately before use.

## What is managed

`state.json` contains a schema version, profile ID and exact `{id, name, content, enabled}` records last written by DnsConf or explicitly adopted. Only an exact match of the current API record with the journal can be changed or removed. Changing a managed value or disabling a rule manually relinquishes ownership. An externally deleted ID is dropped from the journal. Manually recreating the same domain with a new ID is not adoption.

An owned rule can be removed when its domain disappears from all successful sources, becomes excluded, changes IP (including an EU/US switch), or is an owned duplicate. `EXCLUDE_REDIRECT` uses domain-label boundaries, so `ai.com` does not exclude `openai.com`. Exclusions never authorize deletion of a manual rule.

Source order is first-wins. Parsing preserves `www`, wildcard domains, aliases and IPv6; it does not collapse `www.example.com` into `example.com`. Each source must return a successful HTTP response and contain valid, usable redirect rows. Malformed rows, HTTP errors, empty/comment-only sources, or an empty target after exclusions stop the run. Even if one of several sources is empty, no partial target is used. A syntactically valid truncated source cannot always be detected; the deletion count/percentage limits are the final guard against unexpected source shrinkage during Sync; reviewed apply uses its explicit deletion budget.

An empty `REDIRECT` is an error, never an instruction to clear the profile. There is no remove-all entry point for rewrites. This workflow is deliberately scoped to NextDNS **Rewrites**. `BLOCK`, when set, is previewed only; rewrite apply does not alter the denylist. All four configured environments had no `BLOCK` source at the time of this update. Cloudflare continues to use its separate existing runner and is not supported by this workflow.

## Backups, journals and privacy

Artifacts are encrypted because artifacts in a public GitHub repository must not be treated as private. The helper uses OpenSSL AES-256-CBC with a random salt, PBKDF2 and encrypt-then-HMAC-SHA256; encryption and authentication keys are separately derived from `AUTH_SECRET` and the environment name. No API key is written into an artifact. Tampered artifacts and artifacts from another environment/key fail authentication before decryption.

`nextdns-preview-ENV` contains `preview.bin`. After decryption it contains:

- `<profile>/backup.json`: complete original API response pages, including fields not used by the planner.
- `<profile>/state-before.json`: previous ownership journal.
- `<profile>/plan.json`: original snapshot and exact proposed operations.
- `<profile>/sources.json`: code SHA, source hashes, exclusions and effective routes (new previews).

`nextdns-state-ENV` contains `state.bin`, with the most recent checkpointed `state.json` and, when execution reached apply, `result.json` containing acknowledged operations and actual counts. Result details remain encrypted; public summaries expose environment names and counts only. A restored prior result is discarded before a new run. The workflow attempts to upload this journal even after an API failure. State is restored only from this workflow's `main` runs, including failed runs with useful checkpoints. Branch/PR artifacts cannot become ownership state. The latest expired/corrupt journal is an error; the helper never silently falls back to an older journal. A first Preview without state preserves everything already present. Sync requires state. Existing version-1 journals and previews without `sources.json` remain usable for reviewed adopt/apply; automatic Sync requires freshly generated source evidence.

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

New replacements are created before old rules are deleted. Failed creation therefore preserves the working old route. Each acknowledged write checkpoints ownership to an atomic local file. Before each deletion the live profile is rechecked, including all original fields of surviving API records. Avoid editing the profile during apply: NextDNS does not provide an atomic compare-and-delete transaction, so there is still a small race between the final read and a deletion.

On failure, retain the encrypted preview and latest state artifact. Inspect the profile, rerun a preview, and review the new plan. A crash between remote creation and local checkpoint can leave an unowned addition; it is preserved and requires explicit adoption after review. A failed delete may leave both old and new values; the next plan can remove the old **owned** value. Do not reset the journal or run a bulk profile overwrite.

For rollback, compare the backup against the current snapshot, restore only the specific removed/changed records after review, and reconcile their new API IDs into the journal. There is intentionally no automatic full-profile restore: that could erase intervening manual rules. The Git history retains both the pre-update fork and upstream merge parents for code rollback, but restoring the old unsafe workflow is not a profile recovery strategy.

## Validation

Run `mvn -B clean package` with JDK 26, `python3 -m unittest discover -s scripts -p 'test_*.py'`, and `actionlint .github/workflows/*.yml`. Tests use synthetic profiles and local HTTP servers. They do not mutate a live NextDNS profile. Read-only previews and offline source parsing are additional checks; no live apply is needed to validate this change.

The remaining iOS `ISRG Root X2` warning is a separate unresolved issue. Updating DNS synchronization does not establish its cause or prove it is fixed.
