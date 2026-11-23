import os
import unittest
from unittest.mock import patch

from scripts.migrate_nextdns import (
    MAX_REWRITES, MigrationError, apply_plan, migration_plan, seal, unseal,
)


class MigrationTests(unittest.TestCase):
    def test_plan_preserves_only_exact_records_and_removes_all_extras(self):
        current = [
            {"id": "1", "name": "keep.example", "content": "1.1.1.1"},
            {"id": "2", "name": "drop.example", "content": "2.2.2.2"},
            {"id": "3", "name": "change.example", "content": "3.3.3.3"},
        ]
        desired = {"keep.example": "1.1.1.1", "change.example": "4.4.4.4",
                   "add.example": "5.5.5.5"}
        old, new = migration_plan(current, desired)
        self.assertEqual({x["id"] for x in old}, {"2", "3"})
        self.assertEqual(dict(new), {"change.example": "4.4.4.4",
                                     "add.example": "5.5.5.5"})

    def test_full_500_to_494_conversion(self):
        for shared in (1, 295):
            with self.subTest(shared=shared):
                # 500 current records, all unique, 494 in source. Mimics observed live counts.
                existing = [
                    {"id": str(i), "name": f"domain{i}.example", "content": "1.1.1.1"}
                    for i in range(500)
                ]
                desired = {f"domain{i}.example": "1.1.1.1" for i in range(shared)}
                desired.update({f"fresh{i}.example": "2.2.2.2"
                                for i in range(494 - shared)})
                state = existing[:]
                new_id = 500
                def fake_api(method, url, secret=None, body=None):
                    nonlocal new_id
                    if method == "DELETE":
                        identifier = url.split("/")[-1]
                        state[:] = [r for r in state if r["id"] != identifier]
                        return {}
                    if method == "POST":
                        self.assertLess(len(state), MAX_REWRITES)
                        self.assertFalse(any(r["name"] == body["name"] for r in state))
                        new_id += 1
                        state.append({"id": str(new_id), **body})
                        return {"data": {"id": str(new_id)}}
                    self.fail(f"Unexpected HTTP method: {method}")

                with patch("scripts.migrate_nextdns.api", side_effect=fake_api), patch(
                    "scripts.migrate_nextdns.profile_records", return_value=state
                ):
                    deleted, created, total = apply_plan("test", "fake", state[:],
                                                         desired, pause=0)
                self.assertEqual(deleted, 500 - shared)
                self.assertEqual(created, 494 - shared)
                self.assertEqual(total, 494)
                self.assertEqual({x["name"]: x["content"] for x in state}, desired)

    def test_authenticated_backup_roundtrip_and_tamper(self):
        with patch.dict(os.environ, {"AUTH_SECRET": "synthetic-secret-1234567890"}):
            backup = {"rewrites": [{"id": "example", "name": "a", "content": "1.2.3.4"}]}
            blob = seal(backup, "DNS")
            self.assertEqual(unseal(blob, "DNS"), backup)
            with self.assertRaises(MigrationError):
                unseal(blob, "DNS_PV")
            with self.assertRaises(MigrationError):
                unseal(blob[:-1] + bytes([blob[-1] ^ 1]), "DNS")


if __name__ == "__main__":
    unittest.main()
