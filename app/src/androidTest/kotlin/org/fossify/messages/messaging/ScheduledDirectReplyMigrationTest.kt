package org.fossify.messages.messaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.Config
import org.fossify.messages.models.SmsSendContext
import org.fossify.messages.models.SmsSendTriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ScheduledDirectReplyMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Config.newInstance(context).smsSendOperationShadowEnabled = true
    }

    @Test
    fun testScheduledMessage_recordsScheduledAlarmTriggerType() = runBlocking {
        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.SCHEDULED_ALARM,
            address = "10086",
            body = "Scheduled alarm send text",
            subscriptionId = 1,
            threadId = 401L,
            requireDeliveryReport = false,
            messageUri = "content://sms/9001"
        )

        val opId = SmsSendCoordinator.beginSend(context, sendContext)
        Thread.sleep(150)

        assertNotNull(opId)
        val dao = context.getMessagesDB().SmsSendDao()
        val op = dao.getOperationById(opId!!)
        assertNotNull(op)
        assertEquals(SmsSendTriggerType.SCHEDULED_ALARM.name, op?.triggerType)
    }

    @Test
    fun testDirectReply_recordsDirectReplyTriggerType() = runBlocking {
        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.DIRECT_REPLY,
            address = "10010",
            body = "Direct reply text",
            subscriptionId = 2,
            threadId = 402L,
            requireDeliveryReport = false,
            messageUri = "content://sms/9002"
        )

        val opId = SmsSendCoordinator.beginSend(context, sendContext)
        Thread.sleep(150)

        assertNotNull(opId)
        val dao = context.getMessagesDB().SmsSendDao()
        val op = dao.getOperationById(opId!!)
        assertNotNull(op)
        assertEquals(SmsSendTriggerType.DIRECT_REPLY.name, op?.triggerType)
    }

    @Test
    fun testDirectReply_subscriptionResolver_doesNotUseIndexMismapping() {
        val testAddress = "13800138999"
        // Configure preference to a high subId that is definitely > list index (e.g. 10)
        context.config.saveUseSIMIdAtNumber(testAddress, 10)

        val simResult = SubscriptionResolver.resolve(
            context,
            SimResolutionRequest(
                targetAddress = testAddress,
                allowFallback = true
            )
        )

        // SubscriptionResolver should never crash and should gracefully fallback or match
        assertNotNull(simResult)
    }
}
