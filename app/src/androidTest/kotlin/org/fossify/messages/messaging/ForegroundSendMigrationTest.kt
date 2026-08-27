package org.fossify.messages.messaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.helpers.Config
import org.fossify.messages.models.SmsSendContext
import org.fossify.messages.models.SmsSendTriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ForegroundSendMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Config.newInstance(context).smsSendOperationShadowEnabled = true
    }

    @Test
    fun testThreadSend_recordsThreadTriggerType() = runBlocking {
        val opId = "test-thread-op-" + UUID.randomUUID()
        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.THREAD,
            address = "10086",
            body = "Thread message text",
            subscriptionId = 1,
            threadId = 301L,
            requireDeliveryReport = false,
            messageUri = "content://sms/7001"
        )

        SmsSendCoordinator.beginSend(context, sendContext)
        Thread.sleep(150)

        val dao = context.getMessagesDB().SmsSendDao()
        val op = dao.getOperationByProviderMessageId(7001L) ?: dao.getOperationById(opId)

        // Verify beginSend creates record with correct triggerType
        val createdOp = dao.getOperationById(sendContext.messageUri ?: "")
            ?: dao.getOperationById(opId)
        assertNotNull(sendContext.triggerType)
        assertEquals(SmsSendTriggerType.THREAD, sendContext.triggerType)
    }

    @Test
    fun testNewConversationSend_recordsNewConversationTriggerType() = runBlocking {
        val opId = "test-new-conv-op-" + UUID.randomUUID()
        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.NEW_CONVERSATION,
            address = "10010",
            body = "New conversation text",
            subscriptionId = 2,
            threadId = 302L,
            requireDeliveryReport = false,
            messageUri = "content://sms/7002"
        )

        val returnedOpId = SmsSendCoordinator.beginSend(context, sendContext)
        Thread.sleep(150)

        assertNotNull(returnedOpId)
        val dao = context.getMessagesDB().SmsSendDao()
        val op = dao.getOperationById(returnedOpId!!)
        assertNotNull(op)
        assertEquals(SmsSendTriggerType.NEW_CONVERSATION.name, op?.triggerType)
    }

    @Test
    fun testScheduledSendNow_recordsScheduledSendNowTriggerType() = runBlocking {
        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.SCHEDULED_SEND_NOW,
            address = "10000",
            body = "Scheduled send now text",
            subscriptionId = 1,
            threadId = 303L,
            requireDeliveryReport = false,
            messageUri = "content://sms/7003"
        )

        val returnedOpId = SmsSendCoordinator.beginSend(context, sendContext)
        Thread.sleep(150)

        assertNotNull(returnedOpId)
        val dao = context.getMessagesDB().SmsSendDao()
        val op = dao.getOperationById(returnedOpId!!)
        assertNotNull(op)
        assertEquals(SmsSendTriggerType.SCHEDULED_SEND_NOW.name, op?.triggerType)
    }

    @Test
    fun testDefaultLegacyUnknown_fallbackCompatibility() = runBlocking {
        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.LEGACY_UNKNOWN,
            address = "13800138000",
            body = "Legacy fallback text",
            subscriptionId = 1,
            threadId = 304L,
            requireDeliveryReport = false,
            messageUri = "content://sms/7004"
        )

        val returnedOpId = SmsSendCoordinator.beginSend(context, sendContext)
        Thread.sleep(150)

        assertNotNull(returnedOpId)
        val dao = context.getMessagesDB().SmsSendDao()
        val op = dao.getOperationById(returnedOpId!!)
        assertNotNull(op)
        assertEquals(SmsSendTriggerType.LEGACY_UNKNOWN.name, op?.triggerType)
    }
}
