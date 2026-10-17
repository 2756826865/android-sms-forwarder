"""Exercise production history schema, page queries and retention with SQLite."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/org/fossify/messages/forwarding'
DATABASE = (ROOT / 'ForwardingHistoryDatabase.kt').read_text()
STORE = (ROOT / 'ForwardingHistoryStore.kt').read_text()
SCHEMA = re.search(r'const val CREATE_HISTORY = "([^"]+)"', DATABASE).group(1)
PAGE = re.search(r'"(SELECT payload FROM history ORDER BY [^"]+)"', STORE).group(1)
COUNTS = re.search(r'"(SELECT status, COUNT\(\*\) FROM history [^"]+)"', STORE).group(1)
RETENTION = re.search(r'"(DELETE FROM history WHERE status [^"]+)"', STORE).group(1).replace('$MAX_RECORDS', '2000')


class HistorySqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute(SCHEMA)
        self.db.execute('CREATE TABLE history_metadata(key TEXT PRIMARY KEY, value TEXT)')
        self.db.execute('CREATE TABLE group_execution(record_id TEXT PRIMARY KEY, status TEXT)')

    def tearDown(self):
        self.db.close()

    def add(self, ident, status='success', at=100, test=0):
        self.db.execute('INSERT INTO history(record_id,work_id,payload,status,received_at,updated_at,is_test,retry_payload) VALUES(?,?,?,?,?,?,?,?)',
                        (str(ident), "sms-" + str(ident), str(ident), status, at, at, test, 'original full task'))

    def test_paging_has_no_gaps_for_equal_timestamps(self):
        for i in range(120): self.add(i)
        pages = [self.db.execute(PAGE, (50, offset)).fetchall() for offset in (0, 50, 100)]
        combined = [item[0] for page in pages for item in page]
        self.assertEqual(len(combined), 120)
        self.assertEqual(len(set(combined)), 120)

    def test_aggregate_counts_exclude_test_and_time_boundaries(self):
        self.add(1, at=100)
        self.add(2, status='failed', at=199)
        self.add(3, at=200)
        self.add(4, at=100, test=1)
        self.assertEqual(dict(self.db.execute(COUNTS, (100, 200))), {'success': 1, 'failed': 1})

    def test_retention_preserves_active_tasks(self):
        self.add('active', status='running', at=0)
        for i in range(2100): self.add(i, at=i + 1)
        self.db.execute(RETENTION)
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM history WHERE record_id='active'").fetchone()[0], 1)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM history').fetchone()[0], 2001)

    def test_status_update_keeps_complete_retry_payload(self):
        self.add(1)
        self.db.execute('UPDATE history SET status=?, payload=? WHERE record_id=?', ('failed', 'new summary', '1'))
        self.assertEqual(self.db.execute('SELECT retry_payload FROM history').fetchone()[0], 'original full task')

    def test_clear_does_not_clear_migration_marker(self):
        self.db.execute("INSERT INTO history_metadata VALUES('legacy_imported','1')")
        self.add(1)
        self.db.execute('DELETE FROM history')
        self.assertEqual(self.db.execute('SELECT value FROM history_metadata').fetchone()[0], '1')

    def test_clearing_history_cannot_reset_group_execution_guard(self):
        self.add(1)
        self.db.execute("INSERT INTO group_execution VALUES('1', 'running')")
        self.db.execute('DELETE FROM history')
        self.assertEqual(self.db.execute('SELECT status FROM group_execution').fetchone()[0], 'running')

    def test_failed_import_rolls_back_data_and_marker_together(self):
        self.db.execute('BEGIN')
        self.add(1)
        self.db.execute("INSERT INTO history_metadata VALUES('legacy_imported','1')")
        self.db.rollback()
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM history').fetchone()[0], 0)
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM history_metadata').fetchone()[0], 0)


if __name__ == '__main__': unittest.main()
