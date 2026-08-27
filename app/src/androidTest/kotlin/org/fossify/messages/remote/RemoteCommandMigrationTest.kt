package org.fossify.messages.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.RemoteCommandRepository
import org.fossify.messages.helpers.ShadowHmacHelper
import org.fossify.messages.models.RemoteCommandContext
import org.fossify.messages.models.RemoteCommandExecutionEntity
import org.fossify.messages.models.RemoteCommandSourceType
import org.fossify.messages.models.RemoteCommandState
import org.fossify.messages.models.RemoteCommandType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RemoteCommandMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testTableCreationAndEntityWrite() = runBlocking {
        val commandId = "cmd-" + UUID.randomUUID()
        val targetHmac = ShadowHmacHelper.calculateHmac("13800138000")
        val payloadHmac = ShadowHmacHelper.calculateHmac("Test Payload Content") ?: ""
        val requesterHmac = ShadowHmacHelper.calculateHmac("13900139000") ?: ""
        val now = System.currentTimeMillis()

        val entity = RemoteCommandExecutionEntity(
            commandId = commandId,
            sourceType = RemoteCommandSourceType.SMS,
            sourceMessageKey = "msg-key-101",
            commandType = RemoteCommandType.SEND_SMS,
            targetHmac = targetHmac,
            payloadHmac = payloadHmac,
            payloadLength = 20,
            requestedSimMode = 1,
            requesterHmac = requesterHmac,
            receivedAt = now,
            authorized = true,
            authorizationReason = "WHITELIST_MATCH",
            executionState = RemoteCommandState.RECEIVED.name,
            createdAt = now,
            updatedAt = now
        )

        val dao = context.getMessagesDB().RemoteCommandDao()
        val rowId = dao.insertIgnore(entity)
        assertNotEquals(-1L, rowId)

        val queried = dao.findById(commandId)
        assertNotNull(queried)
        assertEquals(commandId, queried?.commandId)
        assertEquals(RemoteCommandSourceType.SMS, queried?.sourceType)
        assertEquals("msg-key-101", queried?.sourceMessageKey)
        assertEquals(RemoteCommandType.SEND_SMS, queried?.commandType)
        assertEquals(targetHmac, queried?.targetHmac)
        assertEquals(payloadHmac, queried?.payloadHmac)
        assertEquals(20, queried?.payloadLength)
        assertEquals(1, queried?.requestedSimMode)
        assertEquals(requesterHmac, queried?.requesterHmac)
        assertTrue(queried?.authorized == true)
        assertEquals("WHITELIST_MATCH", queried?.authorizationReason)
        assertEquals(RemoteCommandState.RECEIVED.name, queried?.executionState)
    }

    @Test
    fun testStateTransitions_ReceivedToSuccess() = runBlocking {
        val commandId = "cmd-" + UUID.randomUUID()
        val entity = RemoteCommandExecutionEntity(
            commandId = commandId,
            sourceType = RemoteCommandSourceType.DINGTALK,
            sourceMessageKey = "dingtalk-msg-" + UUID.randomUUID(),
            commandType = RemoteCommandType.SEND_SMS,
            targetHmac = ShadowHmacHelper.calculateHmac("10086"),
            payloadHmac = ShadowHmacHelper.calculateHmac("Query Balance") ?: "",
            payloadLength = 13,
            requestedSimMode = 0,
            requesterHmac = ShadowHmacHelper.calculateHmac("dingtalk-user-1") ?: "",
            receivedAt = System.currentTimeMillis(),
            authorized = false,
            authorizationReason = "PENDING",
            executionState = RemoteCommandState.RECEIVED.name
        )

        val dao = context.getMessagesDB().RemoteCommandDao()
        dao.insertIgnore(entity)

        // 1. Transition to AUTHORIZED
        dao.recordAuthorization(commandId, authorized = true, reason = "WHITELIST_APPROVED", state = RemoteCommandState.AUTHORIZED.name)
        var current = dao.findById(commandId)
        assertEquals(RemoteCommandState.AUTHORIZED.name, current?.executionState)
        assertTrue(current?.authorized == true)
        assertEquals("WHITELIST_APPROVED", current?.authorizationReason)

        // 2. Transition to RUNNING
        dao.updateState(commandId, RemoteCommandState.RUNNING.name)
        current = dao.findById(commandId)
        assertEquals(RemoteCommandState.RUNNING.name, current?.executionState)

        // 3. Transition to SUCCESS
        val opId = "send-op-12345"
        dao.recordExecutionResult(
            commandId = commandId,
            state = RemoteCommandState.SUCCESS.name,
            sendOperationId = opId,
            completedAt = System.currentTimeMillis(),
            errorClass = null,
            errorHmac = null
        )
        current = dao.findById(commandId)
        assertEquals(RemoteCommandState.SUCCESS.name, current?.executionState)
        assertEquals(opId, current?.sendOperationId)
        assertNotNull(current?.completedAt)
        assertNull(current?.errorClass)
    }

    @Test
    fun testRejectedState() = runBlocking {
        val commandId = "cmd-" + UUID.randomUUID()
        val entity = RemoteCommandExecutionEntity(
            commandId = commandId,
            sourceType = RemoteCommandSourceType.SMS,
            sourceMessageKey = "unauthorized-msg-" + UUID.randomUUID(),
            commandType = RemoteCommandType.SEND_SMS,
            targetHmac = ShadowHmacHelper.calculateHmac("10010"),
            payloadHmac = ShadowHmacHelper.calculateHmac("Unauthorized Send") ?: "",
            payloadLength = 17,
            requestedSimMode = 0,
            requesterHmac = ShadowHmacHelper.calculateHmac("13800000000") ?: "",
            receivedAt = System.currentTimeMillis(),
            authorized = false,
            authorizationReason = "NOT_IN_WHITELIST",
            executionState = RemoteCommandState.RECEIVED.name
        )

        val dao = context.getMessagesDB().RemoteCommandDao()
        dao.insertIgnore(entity)

        dao.recordAuthorization(commandId, authorized = false, reason = "NOT_IN_WHITELIST", state = RemoteCommandState.REJECTED.name)
        val current = dao.findById(commandId)
        assertEquals(RemoteCommandState.REJECTED.name, current?.executionState)
        assertTrue(current?.authorized == false)
        assertEquals("NOT_IN_WHITELIST", current?.authorizationReason)
    }

    @Test
    fun testPermanentIdempotency_duplicateClaim() = runBlocking {
        val key = "shared-msg-key-" + UUID.randomUUID()
        val payload = "Same Payload Text"

        val cmdContext = RemoteCommandContext(
            sourceType = RemoteCommandSourceType.SMS,
            sourceMessageKey = key,
            commandType = RemoteCommandType.SEND_SMS,
            rawTarget = "13800138111",
            rawPayload = payload,
            requestedSimMode = 0,
            rawRequester = "13900139222"
        )

        // First Claim -> NewCommand
        val result1 = RemoteCommandRepository.claimOrGetDuplicate(context, cmdContext, authorized = true, authorizationReason = "WHITELIST_MATCH")
        assertTrue(result1 is RemoteCommandRepository.ClaimResult.NewCommand)
        val firstCmdId = (result1 as RemoteCommandRepository.ClaimResult.NewCommand).commandId

        // Second Claim with identical (sourceType, sourceMessageKey, payloadHash) -> Duplicate
        val result2 = RemoteCommandRepository.claimOrGetDuplicate(context, cmdContext, authorized = true, authorizationReason = "WHITELIST_MATCH")
        assertTrue(result2 is RemoteCommandRepository.ClaimResult.Duplicate)
        val dup = result2 as RemoteCommandRepository.ClaimResult.Duplicate
        assertEquals(firstCmdId, dup.existingCommandId)
        assertNotNull(dup.existingEntity)
    }

    @Test
    fun testPrivacyProtection_noPlaintextInDatabase() = runBlocking {
        val targetPlain = "13812345678"
        val payloadPlain = "TOP_SECRET_PASSWORD_OR_COMMAND"
        val requesterPlain = "13987654321"

        val cmdContext = RemoteCommandContext(
            sourceType = RemoteCommandSourceType.SMS,
            sourceMessageKey = "privacy-key-" + UUID.randomUUID(),
            commandType = RemoteCommandType.SEND_SMS,
            rawTarget = targetPlain,
            rawPayload = payloadPlain,
            requestedSimMode = 1,
            rawRequester = requesterPlain
        )

        val claimResult = RemoteCommandRepository.claimOrGetDuplicate(context, cmdContext)
        assertTrue(claimResult is RemoteCommandRepository.ClaimResult.NewCommand)
        val commandId = (claimResult as RemoteCommandRepository.ClaimResult.NewCommand).commandId

        val record = context.getMessagesDB().RemoteCommandDao().findById(commandId)
        assertNotNull(record)

        // Verify that plaintext strings DO NOT appear anywhere in the database record fields
        assertNotEquals(targetPlain, record?.targetHmac)
        assertNotEquals(payloadPlain, record?.payloadHmac)
        assertNotEquals(requesterPlain, record?.requesterHmac)

        // Verify that calculated HMAC matches
        assertEquals(ShadowHmacHelper.calculateHmac(targetPlain), record?.targetHmac)
        assertEquals(ShadowHmacHelper.calculateHmac(payloadPlain), record?.payloadHmac)
        assertEquals(ShadowHmacHelper.calculateHmac(requesterPlain), record?.requesterHmac)
        assertEquals(payloadPlain.length, record?.payloadLength)
    }
}
