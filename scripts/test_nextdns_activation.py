import unittest

from scripts.nextdns_activation import WORKFLOW, migration_completed


class MigrationActivationTests(unittest.TestCase):
    def test_does_not_activate_before_successful_main_migration(self):
        self.assertFalse(migration_completed([]))
        sample = dict(conclusion="success", status="completed", event="push",
                      head_branch="feat/test", path=WORKFLOW)
        self.assertFalse(migration_completed([sample]))
        self.assertFalse(migration_completed([{**sample, "head_branch": "main",
                                               "conclusion": "failure"}]))
        self.assertFalse(migration_completed([{**sample, "head_branch": "main",
                                               "path": ".github/workflows/tests.yml"}]))

    def test_activates_only_after_full_workflow_success(self):
        sample = dict(conclusion="success", status="completed", event="push",
                      head_branch="main", path=WORKFLOW)
        self.assertTrue(migration_completed([sample]))
        self.assertTrue(migration_completed([{**sample, "event": "workflow_dispatch"}]))


if __name__ == "__main__":
    unittest.main()
