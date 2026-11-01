"""Exercise the SQL used by the additive ingress migration and receipt journal."""
from pathlib import Path
import re,sqlite3,unittest
ROOT=Path(__file__).resolve().parents[1]
SRC=ROOT/'app/src/main/kotlin/org/fossify/messages/messaging'
class IngressJournalSqlTest(unittest.TestCase):
 def setUp(self):
  self.db=sqlite3.connect(':memory:');self.addCleanup(self.db.close)
  old=re.search(r'const val CREATE\s*=\s*"([^"]+)"',(SRC/'SmsIngressIdentity.kt').read_text()).group(1)
  self.db.execute(old)
  self.db.execute("INSERT INTO ingress VALUES('old-owner','old-fp',1,'hash',10,100,7,100)")
  source=(SRC/'SmsIngressDatabase.kt').read_text()
  statements=re.findall(r'db.execSQL\(\s*("""[\s\S]*?"""|"[^"\n]+")',source)
  for stmt in statements:
   sql=stmt[3:-3] if stmt.startswith('"""') else stmt[1:-1]
   sql=sql.strip()
   if sql.startswith('CREATE TABLE ingress_') or sql.startswith('CREATE INDEX journal_'): self.db.execute(sql)
 def receipt(self,key='event',sub=7,received=100):
  self.db.execute('INSERT INTO ingress_journal(event_key,pdu_hash,first_action,raw_sub,confirmed_sub,slot,evidence,observed_at,received_at,payload) VALUES(?,?,?,?,?,?,?,?,?,?)',
   (key,'pdu','SMS_DELIVER',sub,sub,0,'CONFIRMED',received,received,'encrypted'))
 def test_existing_owners_survive_additive_schema(self):
  self.assertEqual(self.db.execute('SELECT work_id,fingerprint,provider_id FROM ingress').fetchone(),('old-owner','old-fp',1))
 def test_duplicate_event_cannot_create_second_owner(self):
  self.receipt()
  with self.assertRaises(sqlite3.IntegrityError):self.receipt()
 def test_write_attempt_survives_restart_without_success(self):
  self.receipt();self.db.execute("UPDATE ingress_journal SET provider_attempted=1 WHERE event_key='event'")
  self.assertEqual(self.db.execute('SELECT provider_attempted,provider_id,done FROM ingress_journal').fetchone(),(1,-1,0))
 def test_notification_claim_is_at_most_once(self):
  self.receipt()
  stmt='UPDATE ingress_journal SET notification_claimed=1 WHERE event_key=? AND notification_claimed=0'
  self.assertEqual(self.db.execute(stmt,('event',)).rowcount,1)
  self.assertEqual(self.db.execute(stmt,('event',)).rowcount,0)
 def test_completion_releases_payload_preserves_identity(self):
  self.receipt();self.db.execute("INSERT INTO ingress_alias VALUES('alias','event')")
  self.db.execute("UPDATE ingress_journal SET done=1,payload=NULL,reason='' WHERE event_key='event'")
  self.assertEqual(self.db.execute('SELECT done,payload FROM ingress_journal').fetchone(),(1,None))
  self.assertEqual(self.db.execute('SELECT event_key FROM ingress_alias').fetchone(),('event',))
 def test_receiving_evidence_does_not_change_alias(self):
  self.receipt(sub=-1);self.db.execute("INSERT INTO ingress_alias VALUES('unknown-fp','event')")
  self.db.execute("UPDATE ingress_journal SET confirmed_sub=42,slot=1 WHERE event_key='event' AND confirmed_sub<0")
  self.assertEqual(self.db.execute('SELECT event_key FROM ingress_alias').fetchone(),('event',))
 def test_execution_snapshot_is_frozen(self):
  self.db.execute("INSERT OR IGNORE INTO ingress_execution_sim VALUES('request','old-snapshot')")
  self.db.execute("INSERT OR IGNORE INTO ingress_execution_sim VALUES('request','new-snapshot')")
  self.assertEqual(self.db.execute('SELECT snapshot FROM ingress_execution_sim').fetchone(),('old-snapshot',))
 def test_provider_reuse_is_not_an_owned_new_receipt(self):
  self.receipt();self.db.execute("UPDATE ingress_journal SET provider_id=1 WHERE event_key='event'")
  self.assertEqual(self.db.execute('SELECT COUNT(*) FROM ingress_journal WHERE provider_id=? AND received_at=?',(1,200)).fetchone(),(0,))
 def paired(self,sub):
  src=(SRC/'SmsIngressStaging.kt').read_text()
  query=re.search(r'"""\s*(SELECT event_key FROM ingress_journal.*?)"""',src,re.S).group(1)
  return self.db.execute(query,('pdu','0','200','SMS_RECEIVED',str(sub),str(sub),str(sub))).fetchall()
 def test_unknown_event_cannot_absorb_a_second_known_sim(self):
  self.receipt(sub=-1);self.db.execute("UPDATE ingress_journal SET confirmed_sub=42")
  self.assertEqual(self.paired(7),[])
  self.assertEqual(self.paired(42),[('event',)])
 def test_unknown_opposite_entrance_can_join_one_confirmed_receipt(self):
  self.receipt(sub=42)
  self.assertEqual(self.paired(-1),[('event',)])
 def test_ambiguous_pending_events_remain_separate(self):
  self.receipt(key='first',sub=-1);self.receipt(key='second',sub=-1)
  self.assertEqual(len(self.paired(42)),2)
if __name__=='__main__':unittest.main()
