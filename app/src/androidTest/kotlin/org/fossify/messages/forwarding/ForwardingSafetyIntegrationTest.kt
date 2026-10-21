package org.fossify.messages.forwarding

import android.content.Context
import org.fossify.messages.testing.ForwardingTestContext
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/** Isolated random task IDs. No SmsManager, real channel requests, or deletion of existing logs. */
class ForwardingSafetyIntegrationTest {
    private val context: Context get() = ForwardingTestContext.context
    private val store get() = ForwardingHistoryStore(context)
    private fun key() = "test-${UUID.randomUUID()}"
    private fun record() = store.registerQueued(key(), "sms_direct", "fake", "fake", 1, 1, true)
    private fun failed(): ForwardingHistoryRecord {
        val id = record(); store.markFailed(id, "fake failure"); return store.record(id)!!
    }
    private fun snapshot() = JSONObject().put("queueRequestId", UUID.randomUUID().toString()).put("queueData", "fake-data")

    @Test fun preSubmitFailureAllowsExplicitRetry() {
        val source = key(); val id = record(); var count = 0
        assertThrows(SmsDirectPreSubmitFailure::class.java) {
            SmsDirectSendGuard.sendPrepared(context, source, "13800000001", id) { error("fake prepare") }
        }
        assertEquals("pre_submit_failed", store.smsPhase(id))
        SmsDirectSendGuard.sendPrepared(context, source, "13800000001", id) { before -> before(key()); count++ }
        assertEquals(1, count)
    }
    @Test fun postSubmitFailureNeverAllowsSecondCharge() {
        val source = key(); val id = record(); var count = 0
        assertThrows(SmsDirectOutcomeUnknown::class.java) {
            SmsDirectSendGuard.sendPrepared(context, source, "13800000002", id) { before -> before(key()); count++; error("fake API") }
        }
        assertThrows(SmsDirectOutcomeUnknown::class.java) {
            SmsDirectSendGuard.sendPrepared(context, source, "13800000002", id) { before -> before(key()); count++ }
        }
        assertEquals(1, count)
    }
    @Test fun legacyClaimsRemainBlocked() {
        val source = key()
        SmsDirectSendGuard.sendOnce(context, source, "13800000003") {}
        assertThrows(SmsDirectOutcomeUnknown::class.java) {
            SmsDirectSendGuard.sendPrepared(context, source, "13800000003") { before -> before(key()); fail("duplicate") }
        }
    }
    @Test fun missingBoundaryIsPreparationFailure() {
        val id = record()
        assertThrows(SmsDirectPreSubmitFailure::class.java) {
            SmsDirectSendGuard.sendPrepared(context, key(), "13800000004", id) {}
        }
        assertEquals("pre_submit_failed", store.smsPhase(id))
    }
    @Test fun failedReceiptCannotBecomeSuccess() {
        val id = record(); val token = key()
        SmsDirectSendGuard.sendPrepared(context, key(), "13800000005", id) { before ->
            before(token); SmsDirectSendGuard.recordSentResult(context, token, false)
        }
        SmsDirectSendGuard.recordSentResult(context, token, true)
        store.markSuccess(id, "late worker"); store.markUnknown(id, "late observer")
        assertEquals("failed", store.smsPhase(id))
        assertEquals(ForwardingHistoryStore.STATUS_FAILED, store.record(id)!!.status)
    }
    @Test fun successfulReceiptSurvivesLateWorkerFailure() {
        val id = record(); val token = key()
        SmsDirectSendGuard.sendPrepared(context, key(), "13800000006", id) { before -> before(token) }
        SmsDirectSendGuard.recordSentResult(context, token, true)
        store.markFailed(id, "late failure")
        assertEquals(ForwardingHistoryStore.STATUS_SUCCESS, store.record(id)!!.status)
    }
    @Test fun mixedChannelReceiptDoesNotCompleteWholeRecord() {
        val id = record(); val token = key()
        SmsDirectSendGuard.sendPrepared(context, key(), "13800000007", id, receiptRecordId = "") { before -> before(token) }
        SmsDirectSendGuard.recordSentResult(context, token, true)
        assertNotEquals(ForwardingHistoryStore.STATUS_SUCCESS, store.record(id)!!.status)
    }
    @Test fun phaseCannotRegressAfterSubmission() {
        val id = record(); store.setSmsPhase(id, "submitting")
        store.setSmsPhase(id, "preparing"); store.setSmsPhase(id, "pre_submit_failed")
        assertEquals("submitting", store.smsPhase(id))
    }
    @Test fun retryIntentHasOneOwner() {
        val parent = failed(); val child = record(); val payload = snapshot()
        assertTrue(store.reserveSubmission(parent, "manual", child, payload))
        assertFalse(store.reserveSubmission(parent, "manual", child, snapshot()))
        assertTrue(store.hasPendingManualSubmission(parent.recordId))
        assertTrue(store.pendingSubmissions().any { it.getString("queueRequestId") == payload.getString("queueRequestId") })
    }
    @Test fun workerClaimBindsSnapshotAndBlocksDuplicateExecution() {
        val parent = failed(); val child = record(); val payload = snapshot()
        assertTrue(store.reserveSubmission(parent, "manual", child, payload))
        val uuid = payload.getString("queueRequestId")
        assertTrue(store.claimSubmissionExecution(uuid, false))
        assertEquals(uuid, store.retryPayload(child)!!.getString("queueRequestId"))
        assertFalse(store.claimSubmissionExecution(uuid, false))
        assertTrue(store.claimSubmissionExecution(uuid, true))
        assertFalse(store.hasPendingManualSubmission(parent.recordId))
    }
    @Test fun settledIntentStillHasExecutionGuard() {
        val parent = failed(); val child = record(); val payload = snapshot(); val uuid = payload.getString("queueRequestId")
        assertTrue(store.reserveSubmission(parent, "manual", child, payload))
        store.settleSubmission(uuid)
        assertTrue(store.claimSubmissionExecution(uuid, false))
        assertFalse(store.claimSubmissionExecution(uuid, false))
    }
    @Test fun staleParentSnapshotCannotReserve() {
        val parent = failed(); store.markUnknown(parent.recordId, "changed")
        assertFalse(store.reserveSubmission(parent, "manual", record(), snapshot()))
    }
    @Test fun oldManualGuardCannotBeReopened() {
        val parent = failed(); assertTrue(store.claimManualRetry(parent))
        assertFalse(store.reserveSubmission(store.record(parent.recordId)!!, "manual", record(), snapshot()))
    }
    @Test fun pendingAutomaticRepairBlocksManualRetry() {
        val id = record(); val parent = store.record(id)!!
        assertTrue(store.reserveSubmission(parent, "repair", id, snapshot()))
        store.markFailed(id, "fake stopped")
        assertFalse(store.reserveSubmission(store.record(id)!!, "manual", record(), snapshot()))
    }
    @Test fun unknownAckIsNotSafeToRetry() {
        val error = assertThrows(java.io.IOException::class.java) {
            DeliveryFailurePolicy.requireRemoteAccepted(false, "ack_timeout", "fake timeout")
        }
        assertTrue(DeliveryFailurePolicy.isUnknown(error))
        assertFalse(DeliveryFailurePolicy.isUnknown(SmsDirectPreSubmitFailure(java.io.IOException("prepare"))))
    }
    @Test fun wrappedUnknownRemainsUnknown() {
        val id = record()
        assertThrows(SmsDirectOutcomeUnknown::class.java) {
            SmsDirectSendGuard.sendPrepared(context, key(), "13800000008", id) {
                throw IllegalStateException("sender wrapper", SmsDirectOutcomeUnknown("old claim"))
            }
        }
        assertEquals("unknown", store.smsPhase(id))
    }

}
