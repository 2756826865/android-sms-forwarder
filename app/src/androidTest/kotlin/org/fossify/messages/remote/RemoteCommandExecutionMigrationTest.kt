package org.fossify.messages.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.RemoteCommandRepository
import org.fossify.messages.models.RemoteCommandContext
import org.fossify.messages.models.RemoteCommandSourceType
import org.fossify.messages.models.RemoteCommandState
import org.fossify.messages.models.RemoteCommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RemoteCommandExecutionMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testSmsCommandNormalLifecycle_ReceivedToSuccess() = runBlocking {
        val messageKey = "sms-norm-" + UUID.randomUUID()
        val cmdContext = RemoteCommandContext(
            sourceType = RemoteCommandSourceType.SMS,
            sourceMessageKey = messageKey,
            commandType = RemoteCommandType.SEND_SMS,
            rawTarget = "10086",
            rawPayload = "CXLL",
            requestedSimMode = 0,
            rawRequester = "13800138000"
        )

        val claim = RemoteCommandRepository.claimOrGetDuplicate(context, cmdContext)
        assertTrue(claim is RemoteCommandRepository.ClaimResult.NewCommand)
        val commandId = (claim as RemoteCommandRepository.ClaimResult.NewCommand).commandId

        val dao = context.getMessagesDB().RemoteCommandDao()
        var entity = dao.findById(commandId)
        assertEquals(RemoteCommandState.RECEIVED.name, entity?.executionState)

        // 1. Authorize
        RemoteCommandRepository.recordAuthorization(context, commandId, authorized = true, reason = "WHITELIST_MATCH")
        Thread.sleep(100)
        entity = dao.findById(commandId)
        assertEquals(RemoteCommandState.AUTHORIZED.name, entity?.executionState)
        assertTrue(entity?.authorized == true)

        // 2. Running
        RemoteCommandRepository.recordRunning(context, commandId)
        Thread.sleep(100)
        entity = dao.findById(commandId)
        assertEquals(RemoteCommandState.RUNNING.name, entity?.executionState)

        // 3. Success
        val opId = "op-" + UUID.randomUUID()
        RemoteCommandRepository.recordExecutionSuccess(context, commandId, sendOperationId = opId)
        Thread.sleep(100)
        entity = dao.findById(commandId)
        assertEquals(RemoteCommandState.SUCCESS.name, entity?.executionState)
        assertEquals(opId, entity?.sendOperationId)
        assertNotNull(entity?.completedAt)
    }

    @Test
    fun testUnauthorizedSmsCommand_Rejected() = runBlocking {
        val messageKey = "sms-unauth-" + UUID.randomUUID()
        val cmdContext = RemoteCommandContext(
            sourceType = RemoteCommandSourceType.SMS,
            sourceMessageKey = messageKey,
            commandType = RemoteCommandType.SEND_SMS,
            rawTarget = "10086",
            rawPayload = "CXLL",
            requestedSimMode = 0,
            rawRequester = "13999999999"
        )

        val claim = RemoteCommandRepository.claimOrGetDuplicate(context, cmdContext)
        assertTrue(claim is RemoteCommandRepository.ClaimResult.NewCommand)
        val commandId = (claim as RemoteCommandRepository.ClaimResult.NewCommand).commandId

        val dao = context.getMessagesDB().RemoteCommandDao()

        RemoteCommandRepository.recordAuthorization(context, commandId, authorized = false, reason = "NOT_AUTHORIZED")
        Thread.sleep(100)

        val entity = dao.findById(commandId)
        assertEquals(RemoteCommandState.REJECTED.name, entity?.executionState)
        assertTrue(entity?.authorized == false)
        assertEquals("NOT_AUTHORIZED", entity?.authorizationReason)
    }

    @Test
    fun testDuplicateCommand_BlockedByPermanentIdempotency() = runBlocking {
        val messageKey = "sms-dup-" + UUID.randomUUID()
        val cmdContext = RemoteCommandContext(
            sourceType = RemoteCommandSourceType.SMS,
            sourceMessageKey = messageKey,
            commandType = RemoteCommandType.SEND_SMS,
            rawTarget = "10010",
            rawPayload = "101",
            requestedSimMode = 1,
            rawRequester = "13800138000"
        )

        val claim1 = RemoteCommandRepository.claimOrGetDuplicate(context, cmdContext)
        assertTrue(claim1 is RemoteCommandRepository.ClaimResult.NewCommand)
        val commandId1 = (claim1 as RemoteCommandRepository.ClaimResult.NewCommand).commandId

        val claim2 = RemoteCommandRepository.claimOrGetDuplicate(context, cmdContext)
        assertTrue(claim2 is RemoteCommandRepository.ClaimResult.Duplicate)
        val duplicate = claim2 as RemoteCommandRepository.ClaimResult.Duplicate
        assertEquals(commandId1, duplicate.existingCommandId)
    }

    @Test
    fun testDingTalkSource_RecordedCorrectly() = runBlocking {
        val msgId = "dingtalk-msg-" + UUID.randomUUID()
        val cmdContext = RemoteCommandContext(
            sourceType = RemoteCommandSourceType.DINGTALK,
            sourceMessageKey = msgId,
            commandType = RemoteCommandType.SEND_SMS,
            rawTarget = "10000",
            rawPayload = "10001",
            requestedSimMode = 2,
            rawRequester = "dingtalk-stream"
        )

        val claim = RemoteCommandRepository.claimOrGetDuplicate(context, cmdContext)
        assertTrue(claim is RemoteCommandRepository.ClaimResult.NewCommand)
        val commandId = (claim as RemoteCommandRepository.ClaimResult.NewCommand).commandId

        val dao = context.getMessagesDB().RemoteCommandDao()
        val entity = dao.findById(commandId)
        assertNotNull(entity)
        assertEquals(RemoteCommandSourceType.DINGTALK, entity?.sourceType)
        assertEquals(msgId, entity?.sourceMessageKey)
        assertEquals(2, entity?.requestedSimMode)
    }

    @Test
    fun testExecutionFailure_RecordedCorrectly() = runBlocking {
        val messageKey = "sms-fail-" + UUID.randomUUID()
        val cmdContext = RemoteCommandContext(
            sourceType = RemoteCommandSourceType.SMS,
            sourceMessageKey = messageKey,
            commandType = RemoteCommandType.SEND_SMS,
            rawTarget = "10086",
            rawPayload = "FAIL_PAYLOAD",
            requestedSimMode = 0,
            rawRequester = "13800138000"
        )

        val claim = RemoteCommandRepository.claimOrGetDuplicate(context, cmdContext)
        assertTrue(claim is RemoteCommandRepository.ClaimResult.NewCommand)
        val commandId = (claim as RemoteCommandRepository.ClaimResult.NewCommand).commandId

        val dao = context.getMessagesDB().RemoteCommandDao()

        RemoteCommandRepository.recordRunning(context, commandId)
        Thread.sleep(100)

        val errorClass = "SimUnavailableException"
        val errorMsg = "No available SIM card in slot 1"
        RemoteCommandRepository.recordExecutionFailure(context, commandId, errorClass = errorClass, errorMessage = errorMsg)
        Thread.sleep(100)

        val entity = dao.findById(commandId)
        assertEquals(RemoteCommandState.FAILED.name, entity?.executionState)
        assertEquals(errorClass, entity?.errorClass)
        assertNotNull(entity?.errorHmac)
        assertNotNull(entity?.completedAt)
        assertNull(entity?.sendOperationId)
    }
}
