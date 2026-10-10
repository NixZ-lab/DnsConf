import json
import tempfile
import unittest
from pathlib import Path
import sync_scope
import sync_report


class ScopeTests(unittest.TestCase):
    def env(self, **changes):
        values = dict(SYNC_MODE='sync', SYNC_ENABLED='true', GITHUB_REF='refs/heads/main', INITIALIZE='false', DNS='NEXTDNS', CLIENT_ID='abc123', MAX_DELETE='0', DRY_RUN='true', SELECTED_ENVIRONMENT='ALL', REVIEW_RUN_ID='', ADOPT_IDS='')
        values.update(changes)
        return values

    def test_disabled_profile_skips_before_credentials_and_never_initializes(self):
        self.assertTrue(sync_scope.validate(self.env(SYNC_ENABLED='false', DNS='', CLIENT_ID='')))
        for changes in (dict(INITIALIZE='true'), dict(GITHUB_REF='refs/heads/feature'), dict(SYNC_ENABLED='invalid')):
            with self.assertRaises(ValueError):
                sync_scope.validate(self.env(**changes))

    def test_sync_all_does_not_require_review_but_legacy_mutations_do(self):
        self.assertFalse(sync_scope.validate(self.env()))
        for mode in ('apply', 'adopt'):
            with self.assertRaises(ValueError):
                sync_scope.validate(self.env(SYNC_MODE=mode, DRY_RUN='false'))
        self.assertFalse(sync_scope.validate(self.env(SYNC_MODE='adopt', DRY_RUN='false', SELECTED_ENVIRONMENT='DNS', REVIEW_RUN_ID='123', ADOPT_IDS='reviewed-id')))

    def test_one_failed_profile_does_not_prevent_other_three_scope_checks(self):
        outcomes = []
        for env in (self.env(), self.env(CLIENT_ID='invalid'), self.env(SYNC_ENABLED='false', DNS='', CLIENT_ID=''), self.env(CLIENT_ID='def456')):
            try:
                outcomes.append('Not onboarded' if sync_scope.validate(env) else 'Ready')
            except ValueError:
                outcomes.append('Failed')
        self.assertEqual(['Ready', 'Failed', 'Not onboarded', 'Ready'], outcomes)
        workflow = Path('.github/workflows/github_action.yml').read_text()
        self.assertIn('fail-fast: false', workflow)
        self.assertIn('cancel-in-progress: false', workflow)
        self.assertIn("inputs.mode == 'sync' && 'false' || env.DRY_RUN", workflow)


class ReportTests(unittest.TestCase):
    def test_partial_failure_reports_actual_operations_only_without_sensitive_fields(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory, 'abc123');p.mkdir()
            (p/'result.json').write_text(json.dumps(dict(profile='abc123', status='Failed', failureType='PrivateError', added=1, updated=0, deleted=0, conflicts=2, operations=[dict(id='private', name='secret.example')])))
            row = sync_report.profile_row('DNS', 'sync', 'failure', False, directory)
            self.assertEqual(1, row['added'])
            self.assertEqual('Failed', row['status'])
            self.assertNotIn('secret', json.dumps(row))
            self.assertNotIn('abc123', json.dumps(row))
            rows = [row, dict(row, environment='DNS_WIFE', status='Success'), sync_report.profile_row('DNS_DIRECTOR','sync','success',True,directory), dict(row,environment='DNS_PV',status='Success')]
            table = sync_report.markdown(rows)
            self.assertEqual(6, len(table.splitlines()))
            self.assertIn('Not onboarded', table)
            self.assertEqual(2, table.count('Success'))

    def test_sync_failure_before_apply_never_reports_planned_changes_as_actual(self):
        with tempfile.TemporaryDirectory() as directory:
            p = Path(directory,'abc123');p.mkdir()
            (p/'plan.json').write_text(json.dumps(dict(create=[dict(name='one.com')], delete=[], conflicts=[])))
            row=sync_report.profile_row('DNS','sync','failure',False,directory)
            self.assertIsNone(row['added'])
            self.assertEqual('Failed',row['status'])
            self.assertEqual(1,sync_report.profile_row('DNS','preview','success',False,directory)['added'])


if __name__ == '__main__':
    unittest.main()
