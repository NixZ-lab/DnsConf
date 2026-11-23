import unittest
from unittest.mock import patch

from scripts.audit_nextdns_capacity import domains_from_hosts, excludes_from_env, is_excluded, live_writes_authorized


class CapacityAuditTest(unittest.TestCase):
    def test_domain_normalization(self):
        source = """
            127.0.0.1 localhost
            192.255.159.240 example.com
            192.255.159.241 www.example.com
            ::1 localhost
            192.255.159.240 sub.example.com # inline comment
            192.255.159.240 example.com
            malformed line text
        """
        self.assertEqual(domains_from_hosts(source), {"example.com", "sub.example.com"})

    def test_excludes_match_upstream_suffix(self):
        excluded = excludes_from_env("example.com, www.hello.com")
        self.assertTrue(is_excluded("example.com", excluded))
        self.assertTrue(is_excluded("sub.example.com", excluded))
        self.assertTrue(is_excluded("hello.com", excluded))
        self.assertFalse(is_excluded("notexample.com", excluded))

    def test_live_write_flag_only_for_explicit_authorization(self):
        cases = [
            ({"EVENT_NAME": "workflow_dispatch", "CONFIRM_WRITE": "false"}, False),
            ({"EVENT_NAME": "workflow_dispatch", "CONFIRM_WRITE": "true"}, True),
            ({"EVENT_NAME": "schedule", "UPSTREAM_AUTORUN_ENABLED": "false"}, False),
            ({"EVENT_NAME": "schedule", "UPSTREAM_AUTORUN_ENABLED": "true"}, True),
            ({"EVENT_NAME": "pull_request", "CONFIRM_WRITE": "true"}, False),
        ]
        for env, expected in cases:
            with self.subTest(env=env), patch.dict("os.environ", env, clear=True):
                self.assertEqual(live_writes_authorized(), expected)


if __name__ == "__main__":
    unittest.main()
