package org.fossify.messages.recovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.ShadowHmacHelper
import org.fossify.messages.models.ForwardingShadowDelivery
import org.fossify.messages.models.OutboxSourceType
import org.fossify.messages.models.OutboxTaskEntity
import org.fossify.messages.models.OutboxTaskState
import org.fossify.messages.models.OutboxTaskType
import org.fossify.messages.models.RecoveryAction
import org.fossify.messages.models.RecoveryObjectType
import org.fossify.messages.models.RecoveryTriggerSource
import org.fossify.messages.models.RemoteCommandExecutionEntity
import org.fossify.messages.models.RemoteCommandSourceType
import org.fossify.messages.models.RemoteCommandState
import org.fossify.messages.models.RemoteCommandType
import org.fossify.messages.models.SmsSendOperationEntity
import org.fossify.messages.models.SmsSendState
import org.fossify.messages.models.SmsSendTriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecoveryStateAlignmentTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testSmsSubmittedTimeout_MarkedUnknownAfterSubmit_NoSmsResend() = runBlocking {
        val opId = "op-timeout-" + UUID.randomUUID()
        val now = System.currentTimeMillis()
        val staleSubmittedAt = now - (35 * 60_000L) // 35 minutes ago

        val sendOp = SmsSendOperationEntity(
            sendOperationId = opId,
            triggerType = SmsSendTriggerType.REMOTE_SMS_COMMAND.name,
            addressHmac = ShadowHmacHelper.calculateHmac("10086"),
            bodyHmac = ShadowHmacHelper.calculateHmac("Timeout text"),
            bodyLength = 12,
            subscriptionId = 1,
            threadId = 0L,
            requireDeliveryReport = true,
            state = SmsSendState.SUBMITTED.name,
            submittedAt = staleSubmittedAt,
            createdAt = staleSubmittedAt,
            updatedAt = staleSubmittedAt
        )
        val smsDao = context.getMessagesDB().SmsSendDao()
        smsDao.insertOperation(sendOp)

        // Run recovery scan
        val summary = RecoveryEngine.runRecoveryScan(context, triggerSource = RecoveryTriggerSource.MANUAL)
        assertTrue(summary.markedUnknownCount >= 1)

        // Verify state is updated to UNKNOWN_AFTER_SUBMIT
        val recoveredOp = smsDao.getOperationById(opId)
        assertNotNull(recoveredOp)
        assertEquals(SmsSendState.UNKNOWN_AFTER_SUBMIT.name, recoveredOp?.state)

        // Verify RecoveryRecord was written
        val records = context.getMessagesDB().RecoveryRecordDao().queryLatest(10)
        val matching = records.find { it.objectId == opId }
        assertNotNull(matching)
        assertEquals(RecoveryObjectType.SMS_OPERATION, matching?.objectType)
        assertEquals(SmsSendState.SUBMITTED.name, matching?.initialStatus)
        assertEquals(SmsSendState.UNKNOWN_AFTER_SUBMIT.name, matching?.recoveredStatus)
        assertEquals(RecoveryAction.MARK_UNKNOWN, matching?.actionTaken)
    }

    @Test
    fun testRemoteCommandRunning_SmsSuccess_AlignedToSuccess() = runBlocking {
        val cmdId = "cmd-sync-succ-" + UUID.randomUUID()
        val opId = "op-sync-succ-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        // 1. Insert successful SmsSendOperation
        val sendOp = SmsSendOperationEntity(
            sendOperationId = opId,
            triggerType = SmsSendTriggerType.REMOTE_SMS_COMMAND.name,
            addressHmac = ShadowHmacHelper.calculateHmac("10086"),
            bodyHmac = ShadowHmacHelper.calculateHmac("Cmd text"),
            bodyLength = 8,
            subscriptionId = 1,
            threadId = 0L,
            requireDeliveryReport = false,
            state = SmsSendState.SENT.name,
            sentAt = now - 5000,
            createdAt = now - 10000,
            updatedAt = now - 5000
        )
        context.getMessagesDB().SmsSendDao().insertOperation(sendOp)

        // 2. Insert RUNNING RemoteCommand referencing opId
        val remoteCmd = RemoteCommandExecutionEntity(
            commandId = cmdId,
            sourceType = RemoteCommandSourceType.SMS,
            sourceMessageKey = "msg-sync-1",
            commandType = RemoteCommandType.SEND_SMS,
            targetHmac = ShadowHmacHelper.calculateHmac("10086"),
            payloadHmac = ShadowHmacHelper.calculateHmac("Cmd text") ?: "",
            payloadLength = 8,
            requestedSimMode = 0,
            requesterHmac = ShadowHmacHelper.calculateHmac("13800138000") ?: "",
            receivedAt = now - 10000,
            authorized = true,
            authorizationReason = "WHITELIST_MATCH",
            executionState = RemoteCommandState.RUNNING.name,
            sendOperationId = opId
        )
        val cmdDao = context.getMessagesDB().RemoteCommandDao()
        cmdDao.insertOrReplace(remoteCmd)

        // Run recovery scan
        RecoveryEngine.runRecoveryScan(context, triggerSource = RecoveryTriggerSource.STARTUP)

        // Verify RemoteCommand executionState is now SUCCESS
        val recoveredCmd = cmdDao.findById(cmdId)
        assertNotNull(recoveredCmd)
        assertEquals(RemoteCommandState.SUCCESS.name, recoveredCmd?.executionState)
    }

    @Test
    fun testRemoteCommandRunning_SmsFailed_AlignedToFailed() = runBlocking {
        val cmdId = "cmd-sync-fail-" + UUID.randomUUID()
        val opId = "op-sync-fail-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        // 1. Insert failed SmsSendOperation
        val sendOp = SmsSendOperationEntity(
            sendOperationId = opId,
            triggerType = SmsSendTriggerType.REMOTE_SMS_COMMAND.name,
            addressHmac = ShadowHmacHelper.calculateHmac("10086"),
            bodyHmac = ShadowHmacHelper.calculateHmac("Cmd fail text"),
            bodyLength = 13,
            subscriptionId = 1,
            threadId = 0L,
            requireDeliveryReport = false,
            state = SmsSendState.FAILED.name,
            errorClass = "SmsGenericFailure",
            failedAt = now - 5000,
            createdAt = now - 10000,
            updatedAt = now - 5000
        )
        context.getMessagesDB().SmsSendDao().insertOperation(sendOp)

        // 2. Insert RUNNING RemoteCommand
        val remoteCmd = RemoteCommandExecutionEntity(
            commandId = cmdId,
            sourceType = RemoteCommandSourceType.SMS,
            sourceMessageKey = "msg-sync-2",
            commandType = RemoteCommandType.SEND_SMS,
            targetHmac = ShadowHmacHelper.calculateHmac("10086"),
            payloadHmac = ShadowHmacHelper.calculateHmac("Cmd fail text") ?: "",
            payloadLength = 13,
            requestedSimMode = 0,
            requesterHmac = ShadowHmacHelper.calculateHmac("13800138000") ?: "",
            receivedAt = now - 10000,
            authorized = true,
            authorizationReason = "WHITELIST_MATCH",
            executionState = RemoteCommandState.RUNNING.name,
            sendOperationId = opId
        )
        val cmdDao = context.getMessagesDB().RemoteCommandDao()
        cmdDao.insertOrReplace(remoteCmd)

        // Run recovery scan
        RecoveryEngine.runRecoveryScan(context, triggerSource = RecoveryTriggerSource.PERIODIC_WORKER)

        // Verify RemoteCommand is now FAILED
        val recoveredCmd = cmdDao.findById(cmdId)
        assertNotNull(recoveredCmd)
        assertEquals(RemoteCommandState.FAILED.name, recoveredCmd?.executionState)
    }

    @Test
    fun testOutboxRunning_UnderlyingFactSuccess_AlignedToSuccess() = runBlocking {
        val taskId = "task-sync-succ-" + UUID.randomUUID()
        val cmdId = "cmd-fact-succ-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        // 1. RemoteCommand is SUCCESS
        val remoteCmd = RemoteCommandExecutionEntity(
            commandId = cmdId,
            sourceType = RemoteCommandSourceType.SMS,
            sourceMessageKey = "msg-fact-1",
            commandType = RemoteCommandType.SEND_SMS,
            targetHmac = ShadowHmacHelper.calculateHmac("10086"),
            payloadHmac = ShadowHmacHelper.calculateHmac("Payload") ?: "",
            payloadLength = 7,
            requestedSimMode = 0,
            requesterHmac = ShadowHmacHelper.calculateHmac("13800138000") ?: "",
            receivedAt = now - 10000,
            authorized = true,
            authorizationReason = "WHITELIST_MATCH",
            executionState = RemoteCommandState.SUCCESS.name
        )
        context.getMessagesDB().RemoteCommandDao().insertOrReplace(remoteCmd)

        // 2. OutboxTask is RUNNING referencing cmdId
        val task = OutboxTaskEntity(
            taskId = taskId,
            taskType = OutboxTaskType.SEND_SMS,
            sourceType = OutboxSourceType.REMOTE_COMMAND,
            sourceId = cmdId,
            payloadHmac = ShadowHmacHelper.calculateHmac("Payload") ?: "",
            payloadPayload = "{}",
            state = OutboxTaskState.RUNNING.name,
            createdAt = now - 10000,
            updatedAt = now - 10000
        )
        val outboxDao = context.getMessagesDB().OutboxTaskDao()
        outboxDao.insert(task)

        // Run recovery scan
        RecoveryEngine.runRecoveryScan(context, triggerSource = RecoveryTriggerSource.MANUAL)

        // Verify OutboxTask is aligned to SUCCESS
        val recoveredTask = outboxDao.findById(taskId)
        assertNotNull(recoveredTask)
        assertEquals(OutboxTaskState.SUCCESS.name, recoveredTask?.state)
    }

    @Test
    fun testForwardingDeliveryRunning_OutboxSuccess_AlignedToDelivered() = runBlocking {
        val deliveryId = "del-sync-" + UUID.randomUUID()
        val taskId = "task-for-del-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        // 1. Delivery is RUNNING
        val delivery = ForwardingShadowDelivery(
            deliveryId = deliveryId,
            operationId = "op-for-del",
            channel = "PushPlus",
            state = "RUNNING"
        )
        val shadowDao = context.getMessagesDB().ShadowDaos()
        shadowDao.insertDelivery(delivery)

        // 2. OutboxTask is SUCCESS
        val task = OutboxTaskEntity(
            taskId = taskId,
            taskType = OutboxTaskType.FORWARD_HTTP,
            sourceType = OutboxSourceType.FORWARDING_RULE,
            sourceId = deliveryId,
            payloadHmac = ShadowHmacHelper.calculateHmac("HTTP Payload") ?: "",
            payloadPayload = "{}",
            state = OutboxTaskState.SUCCESS.name,
            createdAt = now - 5000,
            updatedAt = now - 5000
        )
        context.getMessagesDB().OutboxTaskDao().insert(task)

        // Run recovery scan
        RecoveryEngine.runRecoveryScan(context, triggerSource = RecoveryTriggerSource.MANUAL)

        // Verify Delivery is now DELIVERED
        val recoveredDelivery = shadowDao.getDeliveryById(deliveryId)
        assertNotNull(recoveredDelivery)
        assertEquals("DELIVERED", recoveredDelivery?.state)
    }
}
