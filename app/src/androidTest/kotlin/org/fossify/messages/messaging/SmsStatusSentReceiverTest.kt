package org.fossify.messages.messaging

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.SmsSendRepository
import org.fossify.messages.models.SmsSendContext
import org.fossify.messages.models.SmsSendState
import org.fossify.messages.models.SmsSendTriggerType
import org.fossify.messages.receivers.SendStatusReceiver
import org.fossify.messages.receivers.SmsStatusSentReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SmsStatusSentReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: SmsStatusSentReceiver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        receiver = SmsStatusSentReceiver()
    }

    @Test
    fun testSentReceiver_recordsSuccessToRepository() = runBlocking {
        val opId = "test-sent-op-" + UUID.randomUUID()
        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.UI,
            address = "10086",
            body = "Test sent receipt",
            subscriptionId = 1,
            threadId = 100L,
            requireDeliveryReport = false,
            messageUri = "content://sms/5001"
        )
        SmsSendRepository.createOperation(context, opId, sendContext)
        SmsSendRepository.recordParts(context, opId, 1)

        // Allow async insertion to settle
        Thread.sleep(150)

        val intent = Intent(SendStatusReceiver.SMS_SENT_ACTION).apply {
            data = Uri.parse("content://sms/5001")
            putExtra(SendStatusReceiver.EXTRA_SEND_OPERATION_ID, opId)
            putExtra(SendStatusReceiver.EXTRA_PART_INDEX, 0)
            putExtra(SendStatusReceiver.EXTRA_PART_COUNT, 1)
            putExtra(SendStatusReceiver.EXTRA_IS_LAST_PART, true)
            putExtra(SendStatusReceiver.EXTRA_THREAD_ID, 100L)
            putExtra(SendStatusReceiver.EXTRA_ADDRESS, "10086")
        }

        receiver.updateAppDatabase(context, intent, Activity.RESULT_OK)

        // Allow async repo update to settle
        Thread.sleep(200)

        val dao = context.getMessagesDB().SmsSendDao()
        val op = dao.getOperationById(opId)
        val parts = dao.getPartsByOperationId(opId)

        assertNotNull(op)
        assertEquals(SmsSendState.SENT.name, op?.state)
        assertEquals(1, parts.size)
        assertEquals(SmsSendState.SENT.name, parts[0].sentState)
        assertEquals(Activity.RESULT_OK, parts[0].sentResultCode)
    }

    @Test
    fun testSentReceiver_recordsFailureToRepository() = runBlocking {
        val opId = "test-sent-op-failed-" + UUID.randomUUID()
        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.UI,
            address = "10086",
            body = "Test failed receipt",
            subscriptionId = 1,
            threadId = 101L,
            requireDeliveryReport = false,
            messageUri = "content://sms/5002"
        )
        SmsSendRepository.createOperation(context, opId, sendContext)
        SmsSendRepository.recordParts(context, opId, 1)

        Thread.sleep(150)

        val failureCode = SmsManager.RESULT_ERROR_GENERIC_FAILURE
        val intent = Intent(SendStatusReceiver.SMS_SENT_ACTION).apply {
            data = Uri.parse("content://sms/5002")
            putExtra(SendStatusReceiver.EXTRA_SEND_OPERATION_ID, opId)
            putExtra(SendStatusReceiver.EXTRA_PART_INDEX, 0)
            putExtra(SendStatusReceiver.EXTRA_PART_COUNT, 1)
            putExtra(SendStatusReceiver.EXTRA_IS_LAST_PART, true)
            putExtra(SendStatusReceiver.EXTRA_THREAD_ID, 101L)
            putExtra(SendStatusReceiver.EXTRA_ADDRESS, "10086")
        }

        receiver.updateAppDatabase(context, intent, failureCode)

        Thread.sleep(200)

        val dao = context.getMessagesDB().SmsSendDao()
        val op = dao.getOperationById(opId)
        val parts = dao.getPartsByOperationId(opId)

        assertNotNull(op)
        assertEquals(SmsSendState.FAILED.name, op?.state)
        assertEquals(1, parts.size)
        assertEquals(SmsSendState.FAILED.name, parts[0].sentState)
        assertEquals(failureCode, parts[0].sentResultCode)
    }

    @Test
    fun testSentReceiver_nullOperationId_legacySafe() {
        val intent = Intent(SendStatusReceiver.SMS_SENT_ACTION).apply {
            data = Uri.parse("content://sms/5003")
            putExtra(SendStatusReceiver.EXTRA_THREAD_ID, 102L)
            putExtra(SendStatusReceiver.EXTRA_ADDRESS, "10086")
        }

        // Must not crash when EXTRA_SEND_OPERATION_ID is null
        receiver.updateAppDatabase(context, intent, Activity.RESULT_OK)
    }
}
