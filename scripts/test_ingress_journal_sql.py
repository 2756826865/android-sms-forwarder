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
 def publication_updates(self):
  src=(SRC/'SmsIngressAlerts.kt').read_text()
  return (re.search(r'"(UPDATE ingress_alert SET delivered=1[^"\n]+)"',src).group(1),
          re.search(r'"(UPDATE ingress_journal SET notification_claimed=1[^"\n]+)"',src).group(1))
 def test_publication_acknowledgement_is_idempotent(self):
  self.receipt();self.db.execute("INSERT INTO ingress_alert(event_key,payload,created_at,thread_id) VALUES('event','cipher',100,4)")
  for _ in range(2):
   for sql in self.publication_updates():self.db.execute(sql,('event',))
  self.assertEqual(self.db.execute('SELECT delivered,payload FROM ingress_alert').fetchone(),(1,None))
  self.assertEqual(self.db.execute('SELECT notification_claimed FROM ingress_journal').fetchone()[0],1)
 def test_failed_ack_transaction_keeps_the_replay_payload(self):
  self.receipt();self.db.execute("INSERT INTO ingress_alert(event_key,payload,created_at,thread_id) VALUES('event','cipher',100,4)")
  self.db.commit();self.db.execute('BEGIN')
  self.db.execute(self.publication_updates()[0],('event',));self.db.rollback()
  self.assertEqual(self.db.execute('SELECT delivered,payload FROM ingress_alert').fetchone(),(0,'cipher'))
  self.assertEqual(self.db.execute('SELECT notification_claimed FROM ingress_journal').fetchone()[0],0)
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
  self.db.execute("INSERT OR IGNORE INTO ingress_execution_sim(request_id,snapshot) VALUES('request','old-snapshot')")
  self.db.execute("INSERT OR IGNORE INTO ingress_execution_sim(request_id,snapshot) VALUES('request','new-snapshot')")
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
 def alias_query(self):
  src=(SRC/'SmsIngressStaging.kt').read_text()
  return re.search(r'"""\s*(SELECT j.event_key FROM ingress_alias.*?)"""',src,re.S).group(1)
 def test_alias_requires_same_pdu_and_recent_receipt(self):
  self.receipt();self.db.execute("INSERT INTO ingress_alias VALUES('same-fingerprint','event')")
  query=self.alias_query()
  self.assertEqual(self.db.execute(query,('same-fingerprint','different-pdu','0','200')).fetchall(),[])
  self.assertEqual(self.db.execute(query,('same-fingerprint','pdu','10000','14000')).fetchall(),[])
  self.assertEqual(self.db.execute(query,('same-fingerprint','pdu','0','200')).fetchall(),[('event',)])
 def test_alias_rotation_preserves_old_receipt(self):
  self.receipt('old');self.receipt('new',received=10000)
  self.db.execute("INSERT OR REPLACE INTO ingress_alias VALUES('same-fingerprint','old')")
  self.db.execute("INSERT OR REPLACE INTO ingress_alias VALUES('same-fingerprint','new')")
  self.assertEqual(self.db.execute('SELECT COUNT(*) FROM ingress_journal').fetchone()[0],2)
  self.assertEqual(self.db.execute('SELECT event_key FROM ingress_alias').fetchone()[0],'new')
 def test_spool_replay_keeps_the_original_owner(self):
  self.receipt();self.db.execute("INSERT OR IGNORE INTO ingress_alias VALUES('spool:uuid','event')")
  self.db.execute("INSERT OR IGNORE INTO ingress_alias VALUES('spool:uuid','new-owner')")
  self.assertEqual(self.db.execute("SELECT event_key FROM ingress_alias WHERE alias='spool:uuid'").fetchone(),('event',))
 def test_alert_is_pending_until_actual_publish_acknowledgement(self):
  self.receipt()
  self.db.execute("INSERT OR IGNORE INTO ingress_alert(event_key,payload,created_at,thread_id) VALUES('event','cipher',100,4)")
  # A crash before publish leaves both payload and acknowledgement intact for retry.
  self.assertEqual(self.db.execute('SELECT delivered,payload FROM ingress_alert').fetchone(),(0,'cipher'))
  self.assertEqual(self.db.execute('SELECT notification_claimed FROM ingress_journal').fetchone()[0],0)
  self.db.execute("INSERT OR IGNORE INTO ingress_alert(event_key,payload,created_at,thread_id) VALUES('event','new-cipher',101,4)")
  self.assertEqual(self.db.execute('SELECT COUNT(*),thread_id,payload FROM ingress_alert').fetchone(),(1,4,'cipher'))
 def test_v5_snapshot_migration_preserves_frozen_content(self):
  self.db.execute('DROP TABLE ingress_execution_sim')
  self.db.execute('CREATE TABLE ingress_execution_sim(request_id TEXT PRIMARY KEY,snapshot TEXT NOT NULL)')
  self.db.execute("INSERT INTO ingress_execution_sim VALUES('retry','frozen')")
  self.db.execute('ALTER TABLE ingress_execution_sim ADD COLUMN created_at INTEGER NOT NULL DEFAULT 0')
  self.assertEqual(self.db.execute('SELECT * FROM ingress_execution_sim').fetchone(),('retry','frozen',0))
 def test_review_dismissal_does_not_remove_provider_or_history_owner(self):
  self.receipt();self.db.execute("UPDATE ingress_journal SET reason='configuration_changed'")
  src=(SRC/'SmsIngressReview.kt').read_text()
  sql=re.search(r'"""\s*(UPDATE ingress_journal SET done=1.*?)"""',src,re.S).group(1)
  self.db.execute(sql,('event','configuration_changed'))
  self.assertEqual(self.db.execute('SELECT done,payload,reason FROM ingress_journal').fetchone(),(1,None,'review_dismissed'))
  self.assertEqual(self.db.execute('SELECT work_id FROM ingress').fetchone()[0],'old-owner')

 def test_cleanup_selects_only_completed_alert_free_rows(self):
  self.receipt('done');self.receipt('pending');self.receipt('alert')
  self.db.execute("UPDATE ingress_journal SET sim_snapshot='snapshot',evidence='test'")
  self.db.execute("UPDATE ingress_journal SET done=1 WHERE event_key!='pending'")
  self.db.execute("INSERT INTO ingress_alert(event_key,payload,created_at,thread_id) VALUES('alert','cipher',100,4)")
  src=(SRC/'SmsIngressMaintenance.kt').read_text()
  sql=re.search(r'"""\s*(SELECT event_key FROM ingress_journal WHERE done=1.*?)"""',src,re.S).group(1)
  self.assertEqual(self.db.execute(sql,('10000','')).fetchall(),[('done',)])
 def test_uncertain_review_rotates_after_a_failed_lookup(self):
  self.receipt('first');self.receipt('second')
  self.db.execute("UPDATE ingress_journal SET reason='provider_result_uncertain'")
  self.db.execute("INSERT INTO ingress_timing VALUES('first','provider_review',1000)")
  src=(SRC/'SmsIngressReview.kt').read_text()
  sql=re.search(r'"""\s*(SELECT j.event_key,j.received_at,j.reason FROM ingress_journal j.*?)"""',src,re.S).group(1)
  self.assertEqual(self.db.execute(sql).fetchall()[0][0],'second')

 def test_oem_sms_scan_is_bounded_when_limit_ignored(self):
  src=(SRC/'SmsRecoveryWorker.kt').read_text()
  self.assertIn('while (pageIds.size <= SCAN_PAGE_SIZE && cursor.moveToNext())',src)
  self.assertIn('if (pageIds.size > SCAN_PAGE_SIZE) exhausted = false',src)
  self.assertIn('bounded SMS scan rejected by Provider; retrying ordered query',src)
 def test_spool_telemetry_cannot_fail_durable_admission(self):
  src=(SRC/'SmsIngressSpool.kt').read_text()
  self.assertIn('file.finishWrite(output)\n        } catch',src)
  self.assertIn('runCatching {\n            context.getSharedPreferences("sms_ingress_spool_status"',src)

 def test_ui_repair_does_not_force_full_cursor_count(self):
  src=(SRC/'IncomingSmsUiSyncWorker.kt').read_text()
  self.assertNotIn('cursor.count',src)
  self.assertIn('inspected == 256 && cursor.moveToNext()',src)
 def test_service_acceptance_is_not_durable_receipt(self):
  src=(SRC/'SmsBroadcastDispatcher.kt').read_text()
  self.assertNotIn('handedOff = acceptedByService',src)
  self.assertIn('admission.run()',src)
  self.assertIn('persistWithFallback(',src)
 def test_permanent_forwarding_proof_keeps_content_out(self):
  src=(SRC/'SmsForwardingProof.kt').read_text()
  self.assertIn('ingress_forwarding_proof',src)
  self.assertNotIn('body TEXT',src)

 def test_forwarding_proof_comes_after_confirmed_admission(self):
  root=SRC
  code=(root/'SmsForwardingProof.kt').read_text()
  history=(root.parent/'forwarding'/'ForwardingHistoryStore.kt').read_text()
  scheduler=(root.parent/'forwarding'/'ForwardingWorkScheduler.kt').read_text()
  self.assertIn('private val app = context.applicationContext',code)
  start=history.index('fun registerQueued(')
  end=history.index('fun recordVerifiedSubmission(',start)
  self.assertNotIn('recordSmsQueueEvidence(workId, isTest)',history[start:end])
  self.assertIn('history.recordVerifiedSubmission(recordId)',scheduler)
  self.assertIn('works.any { it.id.toString() == requestId }',scheduler)
 def test_admission_overflow_does_not_unconditionally_run_on_main_thread(self):
  src=(SRC/'SmsBroadcastDispatcher.kt').read_text()
  self.assertIn('overflowAdmissionBusy.compareAndSet(false, true)',src)
  self.assertIn('sms-ingress-overflow',src)

if __name__=='__main__':unittest.main()
