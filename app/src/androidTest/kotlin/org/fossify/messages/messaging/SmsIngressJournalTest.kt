package org.fossify.messages.messaging

import java.util.UUID
import org.fossify.messages.forwarding.ForwardingSimSnapshot
import org.fossify.messages.testing.ForwardingTestContext
import org.junit.Assert.*
import org.junit.Test

class SmsIngressJournalTest {
    @Test
    fun executionSnapshotDoesNotChangeWhenSimOrNameChanges() {
        val journal = SmsIngressSnapshots(ForwardingTestContext.context)
        val request = UUID.randomUUID().toString()
        val before = ForwardingSimSnapshot(0, "工作卡", "", "工作卡", 7)
        assertEquals(before, journal.freezeExecutionSnapshot(request, before))
        assertEquals(
            before,
            journal.freezeExecutionSnapshot(request, ForwardingSimSnapshot(1, "新卡", "", "新卡", 42)),
        )
    }

    @Test
    fun providerOwnershipIsScopedToTheOriginalRowDate() {
        val context = ForwardingTestContext.context
        val key = UUID.randomUUID().toString()
        val db = SmsIngressDatabase.get(context).writableDatabase
        db.execSQL(
            "INSERT INTO ingress_journal(event_key,pdu_hash,raw_sub,confirmed_sub,slot,evidence,observed_at,received_at) VALUES(?,?,?,?,?,?,?,?)",
            arrayOf<Any?>(key, "fake", 7, 7, 0, "test", 10000, 10000),
        )
        val journal = SmsIngressJournal(context)
        journal.providerAttempt(key)
        journal.providerResult(key, 42)
        assertTrue(SmsIngressRecovery(context).ownsProvider(42, 10000))
        assertFalse(SmsIngressRecovery(context).ownsProvider(42, 20000))
        db.rawQuery("SELECT notification_claimed FROM ingress_journal WHERE event_key=?", arrayOf(key)).use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0)) // Provider ownership is not a notification publication acknowledgement.
        }
        journal.complete(key)
        assertTrue(journal.receipt(key)!!.done)
    }
}
