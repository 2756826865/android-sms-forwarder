"""Production queue schema migration, fair scanning and durable repair reservation."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/org/fossify/messages/forwarding'
SOURCE = (ROOT / 'ForwardingHistoryDatabase.kt').read_text()
STORE = (ROOT / 'ForwardingHistoryStore.kt').read_text()
SCHEMA = re.search(r'const val CREATE_HISTORY = "([^"]+)"', SOURCE).group(1)
GUARD = re.search(r'const val CREATE_REPAIR_GUARD = "([^"]+)"', SOURCE).group(1)
MANUAL = re.search(r'const val CREATE_MANUAL_RETRY_GUARD = "([^"]+)"', SOURCE).group(1)
ALTERS = re.findall(r'"(ALTER TABLE history [^"]+)"', SOURCE)
SCAN = re.search(r'"(SELECT payload FROM history WHERE is_test = 0 [^"]+)"', STORE).group(1)
BIND_WHERE = re.search(r'"(record_id = \? AND retry_payload IS NULL)"', STORE).group(1)
REPLACE_WHERE = re.search(r'"(record_id = \? AND retry_payload = \?)"', STORE).group(1)


class QueueRepairSqlTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(':memory:')
        self.db.execute(MANUAL)

    def tearDown(self):
        self.db.close()

    def test_upgrade_preserves_original_logs_and_retry_payload(self):
        self.db.execute(SCHEMA.replace(', queue_checked_at INTEGER NOT NULL DEFAULT 0', '')
                        .replace(", queue_detail TEXT NOT NULL DEFAULT ''", ''))
        self.db.execute("INSERT INTO history VALUES('id','sms-1','original log','queued',10,20,0,'original task')")
        for alter in ALTERS:
            self.db.execute(alter)
        self.db.execute(GUARD)
        self.assertEqual(self.db.execute('SELECT payload,retry_payload,queue_checked_at FROM history').fetchone(),
                         ('original log', 'original task', 0))

    def test_repair_reservation_is_unique_and_survives_log_clear(self):
        self.db.execute(SCHEMA)
        self.db.execute(GUARD)
        self.assertEqual(self.db.execute("INSERT OR IGNORE INTO queue_repair_guard VALUES('id')").rowcount, 1)
        self.assertEqual(self.db.execute("INSERT OR IGNORE INTO queue_repair_guard VALUES('id')").rowcount, 0)
        self.db.execute('DELETE FROM history')
        self.assertEqual(self.db.execute("INSERT OR IGNORE INTO queue_repair_guard VALUES('id')").rowcount, 0)

    def test_failed_transaction_cannot_leave_a_half_claim(self):
        self.db.execute(GUARD)
        self.db.execute('BEGIN')
        self.db.execute("INSERT INTO queue_repair_guard VALUES('id')")
        self.db.rollback()
        self.assertEqual(self.db.execute('SELECT COUNT(*) FROM queue_repair_guard').fetchone()[0], 0)

    def test_scan_observes_unknown_submissions_without_including_test_or_completed(self):
        self.db.execute(SCHEMA)
        for i, (status, test) in enumerate([('queued', 0), ('running', 0), ('retry', 0),
                                          ('unknown', 0), ('success', 0), ('queued', 1)]):
            self.db.execute('INSERT INTO history(record_id,work_id,payload,status,received_at,updated_at,is_test) VALUES(?,?,?,?,?,?,?)',
                            (str(i), 'sms-'+str(i), str(i), status, 0, 0, test))
        self.assertEqual([x[0] for x in self.db.execute(SCAN, (30,))], ['0', '1', '2', '3'])

    def test_checked_active_tasks_do_not_starve_unchecked_tasks(self):
        self.db.execute(SCHEMA)
        for i in range(3):
            self.db.execute('INSERT INTO history(record_id,work_id,payload,status,received_at,updated_at,is_test) VALUES(?,?,?,?,?,?,?)',
                            (str(i), 'sms-'+str(i), str(i), 'queued', 0, i, 0))
        self.assertEqual(self.db.execute(SCAN, (1,)).fetchone()[0], '0')
        self.db.execute("UPDATE history SET queue_checked_at=100 WHERE record_id='0'")
        self.assertEqual(self.db.execute(SCAN, (1,)).fetchone()[0], '1')

    def test_v2_upgrade_preserves_specific_errors_and_original_work_snapshot(self):
        self.db.execute(SCHEMA.replace(", queue_detail TEXT NOT NULL DEFAULT ''", ''))
        self.db.execute("INSERT INTO history VALUES('id','sms-1','socket timeout','retry',10,20,0,'original task',42)")
        self.db.execute(ALTERS[-1])
        self.assertEqual(self.db.execute('SELECT payload,retry_payload,queue_checked_at,queue_detail FROM history').fetchone(),
                         ('socket timeout', 'original task', 42, ''))

    def test_duplicate_keep_snapshot_cannot_overwrite_an_existing_payload(self):
        self.db.execute(SCHEMA)
        self.db.execute("INSERT INTO history(record_id,work_id,payload,status,received_at,updated_at,is_test) VALUES('id','sms-1','original error','queued',10,20,0)")
        statement = 'UPDATE history SET retry_payload = ? WHERE ' + BIND_WHERE
        self.assertEqual(self.db.execute(statement, ('accepted-uuid-and-body', 'id')).rowcount, 1)
        self.assertEqual(self.db.execute(statement, ('ignored-uuid-and-different-body', 'id')).rowcount, 0)
        self.assertEqual(self.db.execute('SELECT retry_payload FROM history').fetchone()[0], 'accepted-uuid-and-body')

    def test_queue_observation_leaves_original_delivery_error_untouched(self):
        self.db.execute(SCHEMA)
        self.db.execute("INSERT INTO history(record_id,work_id,payload,status,received_at,updated_at,is_test) VALUES('id','sms-1','read timeout','retry',10,20,0)")
        self.db.execute("UPDATE history SET queue_detail='waiting for scheduling',queue_checked_at=50 WHERE record_id='id'")
        self.assertEqual(self.db.execute('SELECT payload,queue_detail FROM history').fetchone(),
                         ('read timeout', 'waiting for scheduling'))

    def test_manual_retry_reservation_survives_history_clear(self):
        self.db.execute(SCHEMA)
        self.assertEqual(self.db.execute("INSERT OR IGNORE INTO queue_manual_retry_guard VALUES('id')").rowcount, 1)
        self.db.execute('DELETE FROM history')
        self.assertEqual(self.db.execute("INSERT OR IGNORE INTO queue_manual_retry_guard VALUES('id')").rowcount, 0)

    def test_reserved_manual_retry_does_not_starve_other_observations(self):
        self.db.execute(SCHEMA)
        for key in ['reserved', 'next']:
            self.db.execute('INSERT INTO history(record_id,work_id,payload,status,received_at,updated_at,is_test) VALUES(?,?,?,?,?,?,?)',
                            (key, 'sms-'+key, key, 'unknown', 0, 0, 0))
        self.db.execute("INSERT INTO queue_manual_retry_guard VALUES('reserved')")
        self.assertEqual([x[0] for x in self.db.execute(SCAN, (30,))], ['next'])

    def test_verified_repair_binding_compares_original_snapshot_before_replacement(self):
        self.db.execute(SCHEMA)
        self.db.execute("INSERT INTO history(record_id,work_id,payload,status,received_at,updated_at,is_test,retry_payload) VALUES('id','sms-1','original','retry',10,20,0,'original snapshot')")
        statement = 'UPDATE history SET retry_payload = ? WHERE ' + REPLACE_WHERE
        self.assertEqual(self.db.execute(statement, ('new snapshot', 'id', 'unrelated snapshot')).rowcount, 0)
        self.assertEqual(self.db.execute(statement, ('repaired snapshot', 'id', 'original snapshot')).rowcount, 1)
        self.assertEqual(self.db.execute(statement, ('late callback', 'id', 'original snapshot')).rowcount, 0)
        self.assertEqual(self.db.execute('SELECT retry_payload FROM history').fetchone()[0], 'repaired snapshot')


if __name__ == '__main__':
    unittest.main()
