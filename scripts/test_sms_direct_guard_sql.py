"""Exercise the production SMS direct guard schema without sending any SMS."""
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
import re
import sqlite3
from tempfile import TemporaryDirectory
import unittest

SOURCE = Path(__file__).resolve().parents[1] / "app/src/main/kotlin/org/fossify/messages/forwarding/SmsDirectSendGuard.kt"
CREATE = re.search(r'const val CREATE_CLAIMS = "([^"]+)"', SOURCE.read_text()).group(1)


class SmsDirectGuardSqlTest(unittest.TestCase):
    def setUp(self):
        self.directory = TemporaryDirectory()
        self.path = Path(self.directory.name) / "sms_direct_guard.db"
        with sqlite3.connect(self.path) as db:
            db.execute(CREATE)

    def tearDown(self):
        self.directory.cleanup()

    def claim(self, key):
        with sqlite3.connect(self.path, timeout=10) as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("INSERT OR IGNORE INTO sms_direct_claims VALUES (?, ?)", (key, 123))
            return row.rowcount == 1

    def test_concurrent_claims_allow_one_submission(self):
        with ThreadPoolExecutor(max_workers=12) as pool:
            results = list(pool.map(self.claim, ["same-task"] * 12))
        self.assertEqual(1, sum(results))

    def test_reopened_database_retains_claim(self):
        self.assertTrue(self.claim("task"))
        self.assertFalse(self.claim("task"))

    def test_rolled_back_claim_never_authorizes_a_send(self):
        db = sqlite3.connect(self.path)
        db.execute("BEGIN IMMEDIATE")
        db.execute("INSERT INTO sms_direct_claims VALUES ('task', 123)")
        db.rollback()
        db.close()
        self.assertTrue(self.claim("task"))

    def test_clearing_display_history_does_not_release_claim(self):
        self.assertTrue(self.claim("task"))
        with sqlite3.connect(Path(self.directory.name) / "forwarding_history.db") as db:
            db.execute("CREATE TABLE history (record_id TEXT)")
            db.execute("INSERT INTO history VALUES ('task')")
            db.execute("DELETE FROM history")
        self.assertFalse(self.claim("task"))


if __name__ == "__main__":
    unittest.main()
