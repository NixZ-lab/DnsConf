import io
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


class ArtifactTests(unittest.TestCase):
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
        items = [dict(id=2, expired=True, workflow_run=dict(id=20)), dict(id=1, expired=False, workflow_run=dict(id=10))]
        with patch.object(sync, "artifacts", return_value=items), patch.object(sync, "api", return_value=dict(path=sync.WORKFLOW, event="workflow_dispatch", head_branch="main")):
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


if __name__ == "__main__":
    unittest.main()
