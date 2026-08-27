package org.fossify.messages.messaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
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
class RemoteSmsMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Config.newInstance(context).smsSendOperationShadowEnabled = true
    }

    @Test
    fun testSmsDirectTest_recordsSmsDirectTestTriggerType() = runBlocking {
        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.SMS_DIRECT_TEST,
            address = "10086",
            body = "SMS Direct Test content",
            subscriptionId = 1,
            threadId = 601L,
            requireDeliveryReport = false,
            messageUri = "content://sms/9201"
        )

        val opId = SmsSendCoordinator.beginSend(context, sendContext)
        Thread.sleep(150)

        assertNotNull(opId)
        val dao = context.getMessagesDB().SmsSendDao()
        val op = dao.getOperationById(opId!!)
        assertNotNull(op)
        assertEquals(SmsSendTriggerType.SMS_DIRECT_TEST.name, op?.triggerType)
    }

    @Test
    fun testForwardingSmsDirect_recordsForwardingSmsDirectTriggerType() = runBlocking {
        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.FORWARDING_SMS_DIRECT,
            address = "13800138000",
            body = "Forwarding SMS Direct content",
            subscriptionId = 1,
            threadId = 602L,
            requireDeliveryReport = false,
            messageUri = "content://sms/9202"
        )

        val opId = SmsSendCoordinator.beginSend(context, sendContext)
        Thread.sleep(150)

        assertNotNull(opId)
        val dao = context.getMessagesDB().SmsSendDao()
        val op = dao.getOperationById(opId!!)
        assertNotNull(op)
        assertEquals(SmsSendTriggerType.FORWARDING_SMS_DIRECT.name, op?.triggerType)
    }

    @Test
    fun testRemoteSmsCommand_recordsRemoteSmsCommandTriggerType() = runBlocking {
        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.REMOTE_SMS_COMMAND,
            address = "10010",
            body = "Remote SMS command content",
            subscriptionId = 1,
            threadId = 603L,
            requireDeliveryReport = false,
            messageUri = "content://sms/9203"
        )

        val opId = SmsSendCoordinator.beginSend(context, sendContext)
        Thread.sleep(150)

        assertNotNull(opId)
        val dao = context.getMessagesDB().SmsSendDao()
        val op = dao.getOperationById(opId!!)
        assertNotNull(op)
        assertEquals(SmsSendTriggerType.REMOTE_SMS_COMMAND.name, op?.triggerType)
    }

    @Test
    fun testRemoteDingTalkCommand_recordsRemoteDingTalkCommandTriggerType() = runBlocking {
        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.REMOTE_DINGTALK_COMMAND,
            address = "10000",
            body = "Remote DingTalk command content",
            subscriptionId = 1,
            threadId = 604L,
            requireDeliveryReport = false,
            messageUri = "content://sms/9204"
        )

        val opId = SmsSendCoordinator.beginSend(context, sendContext)
        Thread.sleep(150)

        assertNotNull(opId)
        val dao = context.getMessagesDB().SmsSendDao()
        val op = dao.getOperationById(opId!!)
        assertNotNull(op)
        assertEquals(SmsSendTriggerType.REMOTE_DINGTALK_COMMAND.name, op?.triggerType)
    }

    @Test
    fun testLegacyUnknown_backwardCompatibility() = runBlocking {
        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.LEGACY_UNKNOWN,
            address = "10086",
            body = "Legacy unknown fallback",
            subscriptionId = 1,
            threadId = 605L,
            requireDeliveryReport = false,
            messageUri = "content://sms/9205"
        )

        val opId = SmsSendCoordinator.beginSend(context, sendContext)
        Thread.sleep(150)

        assertNotNull(opId)
        val dao = context.getMessagesDB().SmsSendDao()
        val op = dao.getOperationById(opId!!)
        assertNotNull(op)
        assertEquals(SmsSendTriggerType.LEGACY_UNKNOWN.name, op?.triggerType)
    }
}
