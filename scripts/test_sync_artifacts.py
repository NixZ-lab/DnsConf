import io
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile
import sync_artifacts as sync


def archive(name, content=b"{}"):
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w") as z:
        z.writestr(name, content)
    return result.getvalue()


def workflow(run_id=20, start="2026-10-10T19:20:39Z", end="2026-10-10T19:21:28Z", attempt=1, **extra):
    return dict(id=run_id, path=sync.WORKFLOW, event="workflow_dispatch", head_branch="main",
                created_at="2026-10-10T18:00:00Z", run_started_at=start, updated_at=end,
                run_attempt=attempt, status="completed", conclusion="success", **extra)


def item(artifact_id=2, run_id=20, created="2026-10-10T19:21:15Z", expired=False):
    return dict(id=artifact_id, name="nextdns-state-DNS", created_at=created, expired=expired,
                digest="sha256:" + hashlib.sha256(b"outer").hexdigest(), workflow_run=dict(id=run_id))


def fake_api(runs, items, jobs=None, attempts=None, downloads=None):
    attempts = {(r["id"], r["run_attempt"]): r for r in runs} | (attempts or {})
    def call(path, binary=False):
        if "/actions/artifacts?" in path:
            return dict(artifacts=items)
        if "/workflows/github_action.yml/runs?" in path:
            return dict(workflow_runs=runs)
        if path.endswith("/zip"):
            if downloads is not None:
                downloads.append(int(path.split("/")[-2]))
            return b"outer"
        parts = path.split("/")
        if "/attempts/" in path:
            key = (int(parts[5]), int(parts[7]))
            if "/jobs?" in path:
                return dict(jobs=(jobs or {}).get(key, [dict(name="DNS / sync", conclusion="success")]))
            return attempts[key]
        if "/actions/runs/" in path:
            return next(r for r in runs if r["id"] == int(parts[-1]))
        raise AssertionError("Unexpected API request: " + path)
    return call


JOURNAL = archive("abc123/state.json", json.dumps(dict(version=1, profile="abc123", managed=[])))


class ArtifactTests(unittest.TestCase):
    def test_old_and_new_state_layouts_restore_without_reusing_previous_result(self):
        for include_report in (False, True):
            data=io.BytesIO()
            with zipfile.ZipFile(data,'w') as z:
                z.writestr('abc123/state.json',json.dumps(dict(version=1,profile='abc123',managed=[])))
                if include_report:z.writestr('abc123/result.json','{"status":"Failed","added":1}')
            with tempfile.TemporaryDirectory() as d, patch.object(sync,'api',side_effect=fake_api([workflow()], [item()])), patch.object(sync,'unseal_artifact',return_value=data.getvalue()):
                self.assertTrue(sync.restore('a/b','DNS',d))
                self.assertEqual([],json.loads(Path(d,'abc123/state.json').read_text())['managed'])
                self.assertFalse(Path(d,'abc123/result.json').exists())

    def test_new_preview_evidence_is_allowed_without_changing_old_layout(self):
        with tempfile.TemporaryDirectory() as d:
            sync.extract(archive('abc123/sources.json'),d,{'plan.json','backup.json','state-before.json','sources.json'})
            self.assertTrue(Path(d,'abc123/sources.json').is_file())

    def test_rejects_path_traversal_wrong_profile_layout_and_unexpected_files(self):
        for name in ("../state.json", "/abc123/state.json", "abc123/../../state.json", "abc123/key.txt", "profile/state.json"):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as d:
                with self.assertRaises(ValueError):
                    sync.extract(archive(name), d, {"state.json"})

    def test_extracts_only_expected_journal_with_restricted_permissions(self):
        with tempfile.TemporaryDirectory() as d:
            sync.extract(archive("abc123/state.json"), d, {"state.json"})
            p = Path(d, "abc123/state.json")
            self.assertEqual(b"{}", p.read_bytes())
            self.assertEqual(0o600, p.stat().st_mode & 0o777)

    def test_branch_and_pr_artifacts_cannot_become_ownership_state(self):
        run = dict(path=sync.WORKFLOW, event="workflow_dispatch", head_branch="main")
        self.assertTrue(sync.trusted(run))
        self.assertFalse(sync.trusted(dict(run, event="pull_request")))
        self.assertFalse(sync.trusted(dict(run, head_branch="feature")))
        self.assertFalse(sync.trusted(dict(run, path="attacker.yml")))

    def test_expired_latest_state_does_not_fall_back_to_older_state(self):
        runs = [workflow(), workflow(10, start="2026-10-10T18:00:00Z", end="2026-10-10T18:01:00Z")]
        items = [item(expired=True), item(1, 10, "2026-10-10T18:00:30Z")]
        with patch.object(sync, "api", side_effect=fake_api(runs, items)):
            with self.assertRaisesRegex(RuntimeError, "expired"):
                sync.restore("a/b", "DNS", "/unused")

    def test_plan_requires_same_commit_and_success(self):
        for run in (dict(head_sha="other", conclusion="success"), dict(head_sha="same", conclusion="failure")):
            run.update(path=sync.WORKFLOW, event="workflow_dispatch", head_branch="main")
            with patch.object(sync, "api", return_value=run), self.assertRaises(RuntimeError):
                sync.download_plan("a/b", "DNS", "/unused", 1, "same")

    @patch.dict(os.environ, {"AUTH_SECRET": "synthetic-test-only-key"})
    def test_encryption_roundtrip_tampering_and_environment_binding(self):
        encrypted = sync.crypt(b"sensitive snapshot", "DNS")
        self.assertNotIn(b"sensitive snapshot", encrypted)
        self.assertEqual(b"sensitive snapshot", sync.crypt(encrypted, "DNS", True))
        with self.assertRaises(ValueError):
            sync.crypt(encrypted, "DNS_WIFE", True)
        with self.assertRaises(ValueError):
            sync.crypt(encrypted[:-1] + bytes([encrypted[-1] ^ 1]), "DNS", True)
        with patch.dict(os.environ, {"AUTH_SECRET": "wrong-key"}), self.assertRaises(ValueError):
            sync.crypt(encrypted, "DNS", True)


@patch.dict(os.environ, {"GITHUB_RUN_ID": "999", "CLIENT_ID": "abc123"})
class JournalChronologyTests(unittest.TestCase):
    def setUp(self):
        self.old = workflow(38077201061, start="2026-10-10T18:47:29Z", end="2026-10-10T18:48:20Z")
        self.new = workflow(38079422258)
        self.old_item = item(11679222246, self.old["id"], "2026-10-10T18:48:07Z")
        self.new_item = item(11678614719, self.new["id"], "2026-10-10T19:21:15Z")

    def select(self, runs=None, items=None, **kwargs):
        with patch.object(sync, "api", side_effect=fake_api(
                runs if runs is not None else [self.old, self.new],
                items if items is not None else [self.old_item, self.new_item], **kwargs)):
            return sync.latest_state("a/b", "DNS")

    def test_real_newer_journal_wins_despite_smaller_artifact_id_and_reversed_API_order(self):
        for items in ([self.old_item, self.new_item], [self.new_item, self.old_item]):
            self.assertEqual(self.new_item, self.select(items=items)[1])
        downloads = []
        with tempfile.TemporaryDirectory() as d, patch.object(sync, "api", side_effect=fake_api(
                [self.old, self.new], [self.old_item, self.new_item], downloads=downloads)), \
                patch.object(sync, "unseal_artifact", return_value=JOURNAL):
            self.assertTrue(sync.restore("a/b", "DNS", d))
            self.assertEqual([11678614719], downloads)

    def test_rerun_of_older_run_wins_by_attempt_time_not_original_creation_or_run_id(self):
        rerun = dict(self.old, run_attempt=2, run_started_at="2026-10-10T20:00:00Z", updated_at="2026-10-10T20:01:00Z")
        checkpoint = item(1, self.old["id"], "2026-10-10T20:00:30Z")
        chosen = self.select([rerun, self.new], [self.old_item, self.new_item, checkpoint],
                             attempts={(self.old["id"], 1): self.old})
        self.assertEqual(2, chosen[0]["run_attempt"])
        self.assertEqual(checkpoint, chosen[1])

    def test_missing_journal_in_latest_rerun_never_reuses_first_attempt(self):
        rerun = dict(self.new, run_attempt=2, run_started_at="2026-10-10T20:00:00Z", updated_at="2026-10-10T20:01:00Z")
        with self.assertRaisesRegex(RuntimeError, "Missing or ambiguous"):
            self.select([self.old, rerun], attempts={(self.new["id"], 1): self.new})

    def test_current_rerun_restores_previous_checkpoint_instead_of_expecting_its_own_upload(self):
        current = dict(self.new, run_attempt=2, run_started_at="2026-10-10T20:00:00Z", updated_at="2026-10-10T20:00:01Z", status="in_progress")
        with patch.dict(os.environ, {"GITHUB_RUN_ID": str(current["id"])}):
            self.assertEqual(self.new_item, self.select([self.old, current],
                attempts={(self.new["id"], 1): self.new})[1])

    def test_current_first_attempt_does_not_require_journal_before_its_own_write(self):
        with patch.dict(os.environ, {"GITHUB_RUN_ID": str(self.new["id"])}):
            self.assertEqual(self.old_item, self.select(items=[self.old_item])[1])

    def test_report_only_rerun_preserves_last_state_producing_attempt(self):
        rerun = dict(self.new, run_attempt=2, run_started_at="2026-10-10T20:00:00Z", updated_at="2026-10-10T20:01:00Z")
        jobs = {(self.new["id"], 2): [dict(name="All selected profiles report", conclusion="success")]}
        self.assertEqual(self.new_item, self.select([self.old, rerun], jobs=jobs,
            attempts={(self.new["id"], 1): self.new})[1])

    def test_failed_latest_run_checkpoint_is_kept(self):
        failed = dict(self.new, conclusion="failure")
        self.assertEqual(self.new_item, self.select([self.old, failed])[1])

    def test_missing_latest_journal_even_when_older_journal_exists_stops(self):
        with self.assertRaisesRegex(RuntimeError, "Missing or ambiguous"):
            self.select(items=[self.old_item])

    def test_absent_journal_with_mutation_history_stops(self):
        with self.assertRaisesRegex(RuntimeError, "Missing or ambiguous"):
            self.select(items=[])

    def test_first_onboarding_without_history_or_artifacts_still_returns_no_state(self):
        self.assertIsNone(self.select(runs=[], items=[]))
        with patch.object(sync, "api", side_effect=fake_api([], [])):
            self.assertFalse(sync.restore("a/b", "DNS", "/unused"))

    def test_missing_trusted_provenance_stops(self):
        with self.assertRaisesRegex(RuntimeError, "no trusted"):
            self.select(runs=[])

    def test_duplicate_latest_artifacts_stops_without_ID_tiebreak(self):
        duplicate = dict(self.new_item, id=123)
        with self.assertRaisesRegex(RuntimeError, "ambiguous"):
            self.select(items=[self.old_item, self.new_item, duplicate])

    def test_two_mutating_attempts_with_identical_start_are_ambiguous(self):
        same_start = dict(self.old, run_started_at=self.new["run_started_at"], updated_at=self.new["updated_at"])
        same_time_item = dict(self.old_item, created_at=self.new_item["created_at"])
        with self.assertRaisesRegex(RuntimeError, "Ambiguous"):
            self.select([same_start, self.new], [same_time_item, self.new_item])

    def test_incomplete_latest_attempt_stops(self):
        with self.assertRaisesRegex(RuntimeError, "incomplete"):
            self.select([self.old, dict(self.new, status="in_progress")])

    def test_bad_digest_or_authentication_never_downloads_older_journal(self):
        for bad_digest in (False, True):
            downloads = []
            newest = dict(self.new_item, digest="sha256:wrong") if bad_digest else self.new_item
            with self.subTest(bad_digest=bad_digest), tempfile.TemporaryDirectory() as d, \
                    patch.object(sync, "api", side_effect=fake_api([self.old, self.new], [self.old_item, newest], downloads=downloads)), \
                    patch.object(sync, "unseal_artifact", side_effect=ValueError("Artifact authentication failed")):
                with self.assertRaises(ValueError):
                    sync.restore("a/b", "DNS", d)
                self.assertEqual([self.new_item["id"]], downloads)
                self.assertEqual([], list(Path(d).rglob("state.json")))

    def test_authenticated_but_malformed_state_stops_before_overwriting_destination(self):
        for plaintext in (archive("abc123/result.json"), archive("abc123/state.json", "not JSON"),
                          archive("abc123/state.json", '{"version":1,"profile":"wrong","managed":[]}'),
                          archive("abc123/state.json", '{"version":1,"profile":"abc123","managed":{}}')):
            with self.subTest(plaintext=plaintext[:8]), tempfile.TemporaryDirectory() as d, \
                    patch.object(sync, "api", side_effect=fake_api([self.old, self.new], [self.old_item, self.new_item])), \
                    patch.object(sync, "unseal_artifact", return_value=plaintext):
                existing = Path(d, "abc123/state.json")
                existing.parent.mkdir()
                existing.write_text("original checkpoint")
                with self.assertRaises(ValueError):
                    sync.restore("a/b", "DNS", d)
                self.assertEqual("original checkpoint", existing.read_text())

    def test_untrusted_branch_and_newer_preview_do_not_replace_main_journal(self):
        later = dict(self.new, id=30, run_started_at="2026-10-10T20:00:00Z", updated_at="2026-10-10T20:01:00Z")
        jobs = {(30, 1): [dict(name="DNS / preview")]}
        branch = dict(later, id=40, head_branch="feature")
        self.assertEqual(self.new_item, self.select([self.old, self.new, later, branch], jobs=jobs)[1])

    def test_explicitly_disabled_sync_does_not_require_new_journal(self):
        disabled = dict(self.new, id=30, run_started_at="2026-10-10T20:00:00Z", updated_at="2026-10-10T20:01:00Z")
        jobs = {(30, 1): [dict(name="DNS / sync", conclusion="success", steps=[
            dict(name="Restore latest ownership state", conclusion="skipped")])]}
        self.assertEqual(self.new_item, self.select([self.old, self.new, disabled], jobs=jobs)[1])

    def test_missing_timezone_or_inconsistent_attempt_time_stops(self):
        for bad in (dict(self.new, run_started_at="2026-10-10T19:20:39"),
                    dict(self.new, updated_at="2026-10-10T18:00:00Z")):
            with self.assertRaises(ValueError):
                self.select([self.old, bad])


if __name__ == "__main__":
    unittest.main()
