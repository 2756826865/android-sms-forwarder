"""Production schema/matching SQL regression tests. Only isolated temporary databases."""
from pathlib import Path
import re
import sqlite3
import unittest

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main/kotlin/org/fossify/messages'
HISTORY = (ROOT/'forwarding/ForwardingHistoryDatabase.kt').read_text()
GUARD = (ROOT/'forwarding/SmsDirectSendGuard.kt').read_text()
INGRESS = (ROOT/'messaging/SmsIngressIdentity.kt').read_text()

def constant(source, name):
    return re.search(r'const val '+name+r' = "([^"]+)"', source).group(1)

class IntegrationSqlTest(unittest.TestCase):
    def setUp(self): self.db = sqlite3.connect(':memory:')
    def tearDown(self): self.db.close()
    def test_history_v3_migration_preserves_log_and_original_payload(self):
        schema = constant(HISTORY, 'CREATE_HISTORY').replace(", sms_phase TEXT NOT NULL DEFAULT ''", '')
        self.db.execute(schema)
        self.db.execute("INSERT INTO history(record_id,work_id,payload,status,received_at,updated_at,is_test,retry_payload) VALUES('old','sms-1','log','failed',1,2,0,'snapshot')")
        sql = next(x for x in re.findall(r'"(ALTER TABLE history [^"]+)"', HISTORY) if 'sms_phase' in x)
        self.db.execute(sql)
        self.db.execute(constant(HISTORY, 'CREATE_SUBMISSIONS'))
        self.assertEqual(self.db.execute('SELECT payload,retry_payload,sms_phase FROM history').fetchone(), ('log','snapshot',''))
    def test_old_charge_claims_are_not_unlocked(self):
        self.db.execute('CREATE TABLE sms_direct_claims(claim_key TEXT PRIMARY KEY NOT NULL,claimed_at INTEGER NOT NULL)')
        self.db.execute("INSERT INTO sms_direct_claims VALUES('old',1)")
        for sql in re.findall(r'"(ALTER TABLE sms_direct_claims [^"]+)"', GUARD): self.db.execute(sql)
        self.assertEqual(self.db.execute('SELECT phase,message_uri,record_id FROM sms_direct_claims').fetchone(), ('unknown','',''))
        self.assertEqual(self.db.execute("INSERT OR IGNORE INTO sms_direct_claims(claim_key,claimed_at) VALUES('old',2)").rowcount, 0)
    def match(self):
        query = re.search(r'find\("""(SELECT work_id, received_at FROM ingress.*?)"""', INGRESS, re.S).group(1)
        return self.db.execute(query.replace('$opposite', 'provider_id IS NULL AND fingerprint IS NOT NULL'), ('hash','1','8000','12000','0','0')).fetchall()
    def create_ingress(self):
        self.db.execute(constant(INGRESS, 'CREATE'))
        self.db.execute('INSERT INTO ingress VALUES(?,?,?,?,?,?,?,?)', ('first','fp',None,'hash',10,10000,1,None))
    def test_android_string_zero_sent_time_matches(self):
        self.create_ingress()
        self.assertEqual(self.match(), [('first',10000)])
    def test_ambiguous_identical_sms_are_not_uniquely_matched(self):
        self.create_ingress()
        self.db.execute('INSERT INTO ingress VALUES(?,?,?,?,?,?,?,?)', ('second','fp2',None,'hash',11,10001,1,None))
        self.assertEqual(len(self.match()),2)
    def test_bound_alias_does_not_absorb_another_provider_id(self):
        self.create_ingress()
        self.db.execute('UPDATE ingress SET provider_id=24,provider_date=10000')
        self.assertEqual(self.match(),[])
    def test_submission_uuid_and_parent_kind_are_unique(self):
        self.db.execute(constant(HISTORY,'CREATE_SUBMISSIONS'))
        self.db.execute("INSERT INTO queue_submissions(parent_id,kind,request_id,record_id,payload) VALUES('parent','manual','uuid','child','saved')")
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute("INSERT INTO queue_submissions(parent_id,kind,request_id,record_id,payload) VALUES('parent','manual','other','child','saved')")
        self.assertEqual(self.db.execute('SELECT accepted,worker_started,payload FROM queue_submissions').fetchone(), (0,0,'saved'))

if __name__ == '__main__': unittest.main()
