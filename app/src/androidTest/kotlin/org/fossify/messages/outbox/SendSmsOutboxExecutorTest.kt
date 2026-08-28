package org.fossify.messages.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.Config
import org.fossify.messages.helpers.OutboxRepository
import org.fossify.messages.helpers.RemoteCommandRepository
import org.fossify.messages.helpers.ShadowHmacHelper
import org.fossify.messages.models.OutboxSourceType
import org.fossify.messages.models.OutboxTaskContext
import org.fossify.messages.models.OutboxTaskEntity
import org.fossify.messages.models.OutboxTaskType
import org.fossify.messages.models.RemoteCommandContext
import org.fossify.messages.models.RemoteCommandExecutionEntity
import org.fossify.messages.models.RemoteCommandSourceType
import org.fossify.messages.models.RemoteCommandState
import org.fossify.messages.models.RemoteCommandType
import org.fossify.messages.models.SmsSendContext
import org.fossify.messages.messaging.SmsSendCoordinator
import org.fossify.messages.models.SmsSendOperationEntity
import org.fossify.messages.models.SmsSendState
import org.fossify.messages.models.SmsSendTriggerType
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SendSmsOutboxExecutorTest {

    private lateinit var context: Context
    private val executor = SendSmsOutboxExecutor()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Config.newInstance(context).smsSendOperationShadowEnabled = true
        OutboxExecutorRegistry.clear()
        OutboxExecutorRegistry.register(executor)
    }

    @After
    fun tearDown() {
        OutboxExecutorRegistry.clear()
    }

    @Test
    fun testExecuteSendSms_SuccessFlow() = runBlocking {
        val payload = JSONObject().apply {
            put("target", "10086")
            put("body", "Test Outbox SMS Content")
            put("subscriptionId", 1)
            put("requireDeliveryReport", false)
            put("triggerType", SmsSendTriggerType.REMOTE_SMS_COMMAND.name)
        }.toString()

        val task = OutboxTaskEntity(
            taskId = "task-" + UUID.randomUUID(),
            taskType = OutboxTaskType.SEND_SMS,
            sourceType = OutboxSourceType.REMOTE_COMMAND,
            sourceId = "cmd-source-1",
            payloadHmac = ShadowHmacHelper.calculateHmac(payload) ?: "",
            payloadPayload = payload,
            state = "RUNNING",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        val result = executor.execute(context, task)
        assertTrue(result is OutboxExecutionResult.Success || result is OutboxExecutionResult.Retry)
    }

    @Test
    fun testDuplicateSendProtection_ExistingSubmittedOperationSkips() = runBlocking {
        val commandId = "cmd-dup-protect-" + UUID.randomUUID()
        val sendOpId = "op-dup-protect-" + UUID.randomUUID()

        // 1. Create existing SmsSendOperation in SUBMITTED state
        val sendOp = SmsSendOperationEntity(
            sendOperationId = sendOpId,
            triggerType = SmsSendTriggerType.REMOTE_SMS_COMMAND.name,
            addressHmac = ShadowHmacHelper.calculateHmac("10086"),
            bodyHmac = ShadowHmacHelper.calculateHmac("Existing Content"),
            bodyLength = 16,
            subscriptionId = 1,
            threadId = 0L,
            requireDeliveryReport = false,
            messageUri = "content://sms/9991",
            state = SmsSendState.SUBMITTED.name
        )
        context.getMessagesDB().SmsSendDao().insertOperation(sendOp)

        // 2. Create RemoteCommandExecution referencing sendOpId
        val remoteCmd = RemoteCommandExecutionEntity(
            commandId = commandId,
            sourceType = RemoteCommandSourceType.SMS,
            sourceMessageKey = "msg-protect-1",
            commandType = RemoteCommandType.SEND_SMS,
            targetHmac = ShadowHmacHelper.calculateHmac("10086"),
            payloadHmac = ShadowHmacHelper.calculateHmac("Existing Content") ?: "",
            payloadLength = 16,
            requestedSimMode = 0,
            requesterHmac = ShadowHmacHelper.calculateHmac("13800138000") ?: "",
            receivedAt = System.currentTimeMillis(),
            authorized = true,
            authorizationReason = "WHITELIST_MATCH",
            executionState = RemoteCommandState.RUNNING.name,
            sendOperationId = sendOpId
        )
        context.getMessagesDB().RemoteCommandDao().insertOrReplace(remoteCmd)

        // 3. Construct OutboxTask pointing to the same commandId
        val payload = JSONObject().apply {
            put("target", "10086")
            put("body", "Existing Content")
            put("subscriptionId", 1)
            put("triggerType", SmsSendTriggerType.REMOTE_SMS_COMMAND.name)
        }.toString()

        val task = OutboxTaskEntity(
            taskId = "task-" + UUID.randomUUID(),
            taskType = OutboxTaskType.SEND_SMS,
            sourceType = OutboxSourceType.REMOTE_COMMAND,
            sourceId = commandId,
            payloadHmac = ShadowHmacHelper.calculateHmac(payload) ?: "",
            payloadPayload = payload,
            state = "RUNNING"
        )

        // Execute task -> should skip re-sending and immediately return Success
        val result = executor.execute(context, task)
        assertTrue(result is OutboxExecutionResult.Success)
    }

    @Test
    fun testInvalidPayload_ReturnsFatalFailure() = runBlocking {
        // Missing payloadPayload
        val taskNoPayload = OutboxTaskEntity(
            taskId = "task-empty-payload",
            taskType = OutboxTaskType.SEND_SMS,
            sourceType = OutboxSourceType.REMOTE_COMMAND,
            sourceId = "cmd-1",
            payloadHmac = "hmac",
            payloadPayload = null,
            state = "RUNNING"
        )
        val result1 = executor.execute(context, taskNoPayload)
        assertTrue(result1 is OutboxExecutionResult.FatalFailure)

        // Blank target in payload
        val blankTargetPayload = JSONObject().apply {
            put("target", "   ")
            put("body", "Some content")
        }.toString()
        val taskBlankTarget = OutboxTaskEntity(
            taskId = "task-blank-target",
            taskType = OutboxTaskType.SEND_SMS,
            sourceType = OutboxSourceType.REMOTE_COMMAND,
            sourceId = "cmd-2",
            payloadHmac = "hmac",
            payloadPayload = blankTargetPayload,
            state = "RUNNING"
        )
        val result2 = executor.execute(context, taskBlankTarget)
        assertTrue(result2 is OutboxExecutionResult.FatalFailure)
    }

    @Test
    fun testCanExecuteOnlySendSmsType() {
        assertTrue(executor.canExecute(OutboxTaskType.SEND_SMS))
        assertFalse(executor.canExecute("FORWARD_HTTP"))
        assertFalse(executor.canExecute("UNKNOWN_TYPE"))
    }
}
