package org.fossify.messages.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.ShadowHmacHelper
import org.fossify.messages.models.RemoteCommandExecutionEntity
import org.fossify.messages.models.RemoteCommandSourceType
import org.fossify.messages.models.RemoteCommandState
import org.fossify.messages.models.RemoteCommandType
import org.fossify.messages.models.SmsSendOperationEntity
import org.fossify.messages.models.SmsSendState
import org.fossify.messages.models.SmsSendTriggerType
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.remote.RemoteControlViewModel
import org.fossify.messages.ui.remote.repository.RemoteControlRepository
import org.fossify.messages.ui.remote.usecase.RemoteAuthorizationUseCase
import org.fossify.messages.ui.remote.usecase.RemoteCommandHistoryUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RemoteControlUiTest {

    private lateinit var context: Context
    private lateinit var repository: RemoteControlRepository
    private lateinit var remoteCommandHistoryUseCase: RemoteCommandHistoryUseCase
    private lateinit var remoteAuthorizationUseCase: RemoteAuthorizationUseCase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = RemoteControlRepository(context)
        remoteCommandHistoryUseCase = RemoteCommandHistoryUseCase(repository)
        remoteAuthorizationUseCase = RemoteAuthorizationUseCase(repository)
    }

    @Test
    fun testRemoteHistoryReadingAndStateDisplay() = runBlocking {
        val cmdId = "cmd-ui-succ-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        val remoteCmd = RemoteCommandExecutionEntity(
            commandId = cmdId,
            sourceType = RemoteCommandSourceType.SMS,
            sourceMessageKey = "msg-ui-1",
            commandType = RemoteCommandType.SEND_SMS,
            targetHmac = ShadowHmacHelper.calculateHmac("10086"),
            payloadHmac = ShadowHmacHelper.calculateHmac("Test Cmd") ?: "",
            payloadLength = 8,
            requestedSimMode = 0,
            requesterHmac = ShadowHmacHelper.calculateHmac("13800138000") ?: "",
            receivedAt = now - 5000,
            authorized = true,
            authorizationReason = "WHITELIST_MATCH",
            executionState = RemoteCommandState.SUCCESS.name,
            createdAt = now - 5000,
            updatedAt = now - 1000
        )
        context.getMessagesDB().RemoteCommandDao().insertOrReplace(remoteCmd)

        val list = remoteCommandHistoryUseCase(50)
        val item = list.find { it.commandId == cmdId }

        assertNotNull(item)
        assertEquals(RemoteCommandSourceType.SMS, item?.sourceType)
        assertEquals(RemoteCommandState.SUCCESS.name, item?.executionState)
        assertTrue(item?.authorized == true)
    }

    @Test
    fun testDingTalkSourceDisplay() = runBlocking {
        val cmdId = "cmd-ui-dt-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        val remoteCmd = RemoteCommandExecutionEntity(
            commandId = cmdId,
            sourceType = RemoteCommandSourceType.DINGTALK,
            sourceMessageKey = "stream-msg-ui-1",
            commandType = RemoteCommandType.SEND_SMS,
            targetHmac = ShadowHmacHelper.calculateHmac("10010"),
            payloadHmac = ShadowHmacHelper.calculateHmac("DT Payload") ?: "",
            payloadLength = 10,
            requestedSimMode = 1,
            requesterHmac = ShadowHmacHelper.calculateHmac("staff_123") ?: "",
            receivedAt = now - 3000,
            authorized = true,
            authorizationReason = "DINGTALK_TOKEN_VALID",
            executionState = RemoteCommandState.RECEIVED.name,
            createdAt = now - 3000,
            updatedAt = now - 3000
        )
        context.getMessagesDB().RemoteCommandDao().insertOrReplace(remoteCmd)

        val fullState = remoteCommandHistoryUseCase.getFullState(50)
        val item = fullState.commands.find { it.commandId == cmdId }

        assertNotNull(item)
        assertEquals(RemoteCommandSourceType.DINGTALK, item?.sourceType)
        assertEquals(RemoteCommandState.RECEIVED.name, item?.executionState)
        assertTrue(fullState.isDingTalkConnected)
    }

    @Test
    fun testAssociatedSmsSendOperationDisplay() = runBlocking {
        val cmdId = "cmd-ui-assoc-" + UUID.randomUUID()
        val opId = "op-ui-assoc-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        // 1. Insert Send Operation
        val sendOp = SmsSendOperationEntity(
            sendOperationId = opId,
            triggerType = SmsSendTriggerType.REMOTE_SMS_COMMAND.name,
            addressHmac = ShadowHmacHelper.calculateHmac("10086"),
            bodyLength = 10,
            state = SmsSendState.DELIVERED.name,
            sentAt = now - 2000,
            deliveredAt = now - 500,
            createdAt = now - 4000,
            updatedAt = now - 500
        )
        context.getMessagesDB().SmsSendDao().insertOperation(sendOp)

        // 2. Insert Command referencing opId
        val remoteCmd = RemoteCommandExecutionEntity(
            commandId = cmdId,
            sourceType = RemoteCommandSourceType.SMS,
            sourceMessageKey = "msg-ui-assoc",
            commandType = RemoteCommandType.SEND_SMS,
            targetHmac = ShadowHmacHelper.calculateHmac("10086"),
            payloadHmac = "hmac",
            payloadLength = 10,
            requestedSimMode = 0,
            requesterHmac = "hmac_req",
            receivedAt = now - 4000,
            authorized = true,
            authorizationReason = "WHITELIST",
            executionState = RemoteCommandState.SUCCESS.name,
            sendOperationId = opId,
            createdAt = now - 4000,
            updatedAt = now - 500
        )
        context.getMessagesDB().RemoteCommandDao().insertOrReplace(remoteCmd)

        val list = remoteCommandHistoryUseCase(50)
        val item = list.find { it.commandId == cmdId }

        assertNotNull(item)
        assertEquals(opId, item?.sendOperationId)
        assertNotNull(item?.associatedSendOperation)
        assertEquals(SmsSendState.DELIVERED.name, item?.associatedSmsState)
    }

    @Test
    fun testRemoteControlViewModelLoading() = runBlocking {
        val viewModel = RemoteControlViewModel(remoteCommandHistoryUseCase)
        viewModel.loadRemoteControl()
        Thread.sleep(150)

        val state = viewModel.uiState.value
        assertTrue(state.isSuccess)
    }
}
