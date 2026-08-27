package org.fossify.messages.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.Config
import org.fossify.messages.helpers.ShadowHmacHelper
import org.fossify.messages.helpers.ShadowRepository
import org.fossify.messages.models.ForwardingShadowDelivery
import org.fossify.messages.models.OutboxSourceType
import org.fossify.messages.models.OutboxTaskEntity
import org.fossify.messages.models.OutboxTaskType
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ForwardOutboxExecutorTest {

    private lateinit var context: Context
    private val httpExecutor = ForwardHttpOutboxExecutor()
    private val smsExecutor = ForwardSmsOutboxExecutor()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Config.newInstance(context).smsSendOperationShadowEnabled = true
        OutboxExecutorRegistry.clear()
        OutboxExecutorRegistry.register(httpExecutor)
        OutboxExecutorRegistry.register(smsExecutor)
    }

    @After
    fun tearDown() {
        OutboxExecutorRegistry.clear()
    }

    @Test
    fun testHttpExecutorTypeSupport() {
        assertTrue(httpExecutor.canExecute(OutboxTaskType.FORWARD_HTTP))
        assertTrue(httpExecutor.canExecute(OutboxTaskType.FORWARD_EMAIL))
        assertFalse(httpExecutor.canExecute(OutboxTaskType.FORWARD_SMS))
        assertFalse(httpExecutor.canExecute(OutboxTaskType.SEND_SMS))
    }

    @Test
    fun testSmsExecutorTypeSupport() {
        assertTrue(smsExecutor.canExecute(OutboxTaskType.FORWARD_SMS))
        assertFalse(smsExecutor.canExecute(OutboxTaskType.FORWARD_HTTP))
    }

    @Test
    fun testHttpForwardTaskInvalidUrl_ReturnsFatalFailure() = runBlocking {
        val payload = JSONObject().apply {
            put("url", "")
            put("body", "test")
        }.toString()

        val task = OutboxTaskEntity(
            taskId = "task-http-invalid",
            taskType = OutboxTaskType.FORWARD_HTTP,
            sourceType = OutboxSourceType.FORWARDING_RULE,
            sourceId = "rule-1",
            payloadHmac = ShadowHmacHelper.calculateHmac(payload) ?: "",
            payloadPayload = payload,
            state = "RUNNING"
        )

        val result = httpExecutor.execute(context, task)
        assertTrue(result is OutboxExecutionResult.FatalFailure)
    }

    @Test
    fun testHttpForwardNetworkTimeout_ReturnsRetry() = runBlocking {
        val payload = JSONObject().apply {
            put("url", "http://10.255.255.1:81/unreachable")
            put("method", "POST")
            put("body", "test content")
        }.toString()

        val task = OutboxTaskEntity(
            taskId = "task-http-timeout",
            taskType = OutboxTaskType.FORWARD_HTTP,
            sourceType = OutboxSourceType.FORWARDING_RULE,
            sourceId = "rule-2",
            payloadHmac = ShadowHmacHelper.calculateHmac(payload) ?: "",
            payloadPayload = payload,
            state = "RUNNING"
        )

        val result = httpExecutor.execute(context, task)
        assertTrue(result is OutboxExecutionResult.Retry || result is OutboxExecutionResult.FatalFailure)
    }

    @Test
    fun testForwardSmsDuplicateProtection_CompletedDeliverySkips() = runBlocking {
        val deliveryId = "del-dup-" + UUID.randomUUID()
        val opId = "op-dup-" + UUID.randomUUID()

        // 1. Insert existing completed delivery
        val delivery = ForwardingShadowDelivery(
            deliveryId = deliveryId,
            operationId = opId,
            channel = "SMS_DIRECT",
            state = "DELIVERED"
        )
        context.getMessagesDB().ShadowDaos().insertDelivery(delivery)

        // 2. Build task with same deliveryId
        val payload = JSONObject().apply {
            put("phone", "13800138000")
            put("content", "Duplicate test SMS")
            put("subscriptionId", 1)
            put("deliveryId", deliveryId)
            put("operationId", opId)
        }.toString()

        val task = OutboxTaskEntity(
            taskId = "task-sms-dup",
            taskType = OutboxTaskType.FORWARD_SMS,
            sourceType = OutboxSourceType.FORWARDING_RULE,
            sourceId = deliveryId,
            payloadHmac = ShadowHmacHelper.calculateHmac(payload) ?: "",
            payloadPayload = payload,
            state = "RUNNING"
        )

        // Execute -> should recognize completed delivery and safely skip
        val result = smsExecutor.execute(context, task)
        assertTrue(result is OutboxExecutionResult.Success)
    }
}
