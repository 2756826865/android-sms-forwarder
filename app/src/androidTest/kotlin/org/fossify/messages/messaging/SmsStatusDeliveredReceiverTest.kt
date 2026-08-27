package org.fossify.messages.messaging

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Telephony.Sms
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.SmsSendRepository
import org.fossify.messages.models.SmsSendContext
import org.fossify.messages.models.SmsSendState
import org.fossify.messages.models.SmsSendTriggerType
import org.fossify.messages.receivers.SendStatusReceiver
import org.fossify.messages.receivers.SmsStatusDeliveredReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SmsStatusDeliveredReceiverTest {

    private lateinit var context: Context
    private lateinit var receiver: SmsStatusDeliveredReceiver

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        receiver = SmsStatusDeliveredReceiver()
    }

    @Test
    fun testDeliveredReceiver_recordsSuccessToRepository() = runBlocking {
        val opId = "test-deliv-op-" + UUID.randomUUID()
        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.UI,
            address = "10086",
            body = "Test delivered receipt",
            subscriptionId = 1,
            threadId = 200L,
            requireDeliveryReport = true,
            messageUri = "content://sms/6001"
        )
        SmsSendRepository.createOperation(context, opId, sendContext)
        SmsSendRepository.recordParts(context, opId, 1)

        Thread.sleep(150)

        // Set status via reflection or intent execution simulation
        val intent = Intent(SendStatusReceiver.SMS_DELIVERED_ACTION).apply {
            data = Uri.parse("content://sms/6001")
            putExtra(SendStatusReceiver.EXTRA_SEND_OPERATION_ID, opId)
            putExtra(SendStatusReceiver.EXTRA_PART_INDEX, 0)
            putExtra(SendStatusReceiver.EXTRA_PART_COUNT, 1)
            putExtra(SendStatusReceiver.EXTRA_IS_LAST_PART, true)
        }

        // Invoke updateAppDatabase (which records to shadow repo)
        receiver.updateAppDatabase(context, intent, Activity.RESULT_OK)

        Thread.sleep(200)

        val dao = context.getMessagesDB().SmsSendDao()
        val parts = dao.getPartsByOperationId(opId)

        assertEquals(1, parts.size)
        assertNotNull(parts[0].deliveredAt)
    }

    @Test
    fun testDeliveredReceiver_multipart_recordsPerPart() = runBlocking {
        val opId = "test-deliv-multi-" + UUID.randomUUID()
        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.BULK,
            address = "10010",
            body = "Test multi delivered receipt",
            subscriptionId = 2,
            threadId = 201L,
            requireDeliveryReport = true,
            messageUri = "content://sms/6002"
        )
        SmsSendRepository.createOperation(context, opId, sendContext)
        SmsSendRepository.recordParts(context, opId, 3)

        Thread.sleep(150)

        // Record Part 0 Delivered
        SmsSendRepository.recordDeliveredResult(context, opId, 0, Sms.STATUS_COMPLETE)
        // Record Part 1 Delivered
        SmsSendRepository.recordDeliveredResult(context, opId, 1, Sms.STATUS_COMPLETE)

        Thread.sleep(200)

        val dao = context.getMessagesDB().SmsSendDao()
        val parts = dao.getPartsByOperationId(opId)

        assertEquals(3, parts.size)
        assertEquals(SmsSendState.DELIVERED.name, parts[0].deliveredState)
        assertEquals(SmsSendState.DELIVERED.name, parts[1].deliveredState)
        assertNull(parts[2].deliveredState) // Part 2 not yet delivered
    }

    @Test
    fun testDeliveredReceiver_nullUri_safe() {
        val intent = Intent(SendStatusReceiver.SMS_DELIVERED_ACTION).apply {
            data = null // Null URI test
            putExtra(SendStatusReceiver.EXTRA_SEND_OPERATION_ID, "test-op-null-uri")
            putExtra(SendStatusReceiver.EXTRA_PART_INDEX, 0)
        }

        // Must not crash and must not execute blind date desc updates
        receiver.updateAndroidDatabase(context, intent, Activity.RESULT_OK)
        receiver.updateAppDatabase(context, intent, Activity.RESULT_OK)
    }

    @Test
    fun testDeliveredReceiver_nullOperationId_legacySafe() {
        val intent = Intent(SendStatusReceiver.SMS_DELIVERED_ACTION).apply {
            data = Uri.parse("content://sms/6003")
        }

        // Must not crash when EXTRA_SEND_OPERATION_ID is null
        receiver.updateAppDatabase(context, intent, Activity.RESULT_OK)
    }
}
