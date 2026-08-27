package org.fossify.messages.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.ShadowHmacHelper
import org.fossify.messages.models.SmsSendOperationEntity
import org.fossify.messages.models.SmsSendPartEntity
import org.fossify.messages.models.SmsSendState
import org.fossify.messages.models.SmsSendTriggerType
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.messages.MessageCenterViewModel
import org.fossify.messages.ui.messages.repository.MessageCenterRepository
import org.fossify.messages.ui.messages.usecase.GetMessageDetailTimelineUseCase
import org.fossify.messages.ui.messages.usecase.GetMessageHistoryUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class MessageCenterUiTest {

    private lateinit var context: Context
    private lateinit var repository: MessageCenterRepository
    private lateinit var getMessageHistoryUseCase: GetMessageHistoryUseCase
    private lateinit var getMessageDetailTimelineUseCase: GetMessageDetailTimelineUseCase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = MessageCenterRepository(context)
        getMessageHistoryUseCase = GetMessageHistoryUseCase(repository)
        getMessageDetailTimelineUseCase = GetMessageDetailTimelineUseCase(repository)
    }

    @Test
    fun testMessageHistoryReadingAndDeliveredStatus() = runBlocking {
        val opId = "op-msg-deliv-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        val sendOp = SmsSendOperationEntity(
            sendOperationId = opId,
            triggerType = SmsSendTriggerType.THREAD.name,
            addressHmac = ShadowHmacHelper.calculateHmac("10086"),
            bodyHmac = ShadowHmacHelper.calculateHmac("Delivered Text"),
            bodyLength = 14,
            subscriptionId = 1,
            threadId = 0L,
            requireDeliveryReport = true,
            state = SmsSendState.DELIVERED.name,
            sentAt = now - 2000,
            deliveredAt = now - 500,
            createdAt = now - 5000,
            updatedAt = now - 500
        )
        context.getMessagesDB().SmsSendDao().insertOperation(sendOp)

        val list = getMessageHistoryUseCase(50)
        val item = list.find { it.operationId == opId }

        assertNotNull(item)
        assertEquals(SmsSendState.DELIVERED.name, item?.state)
        assertEquals(SmsSendTriggerType.THREAD.name, item?.triggerType)
        assertNotNull(item?.deliveredAt)
    }

    @Test
    fun testMultipartStatusDisplay() = runBlocking {
        val opId = "op-msg-multipart-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        // 1. Insert Multi-part Operation
        val sendOp = SmsSendOperationEntity(
            sendOperationId = opId,
            triggerType = SmsSendTriggerType.NEW_CONVERSATION.name,
            addressHmac = ShadowHmacHelper.calculateHmac("10010"),
            bodyLength = 320,
            subscriptionId = 2,
            state = SmsSendState.SENT.name,
            sentAt = now - 1000,
            createdAt = now - 3000,
            updatedAt = now - 1000
        )
        context.getMessagesDB().SmsSendDao().insertOperation(sendOp)

        // 2. Insert 2 Parts
        val part0 = SmsSendPartEntity(
            partId = "part-0-" + UUID.randomUUID(),
            sendOperationId = opId,
            partIndex = 0,
            totalParts = 2,
            status = "DELIVERED",
            deliveryStatus = 0,
            createdAt = now - 3000,
            updatedAt = now - 1000
        )
        val part1 = SmsSendPartEntity(
            partId = "part-1-" + UUID.randomUUID(),
            sendOperationId = opId,
            partIndex = 1,
            totalParts = 2,
            status = "DELIVERED",
            deliveryStatus = 0,
            createdAt = now - 3000,
            updatedAt = now - 1000
        )
        context.getMessagesDB().SmsSendDao().insertPart(part0)
        context.getMessagesDB().SmsSendDao().insertPart(part1)

        val list = getMessageHistoryUseCase(50)
        val item = list.find { it.operationId == opId }

        assertNotNull(item)
        assertEquals(2, item?.partsCount)
        assertEquals(2, item?.partsDeliveredCount)
    }

    @Test
    fun testUnknownStatusDisplay() = runBlocking {
        val opId = "op-msg-unknown-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        val sendOp = SmsSendOperationEntity(
            sendOperationId = opId,
            triggerType = SmsSendTriggerType.REMOTE_SMS_COMMAND.name,
            state = SmsSendState.UNKNOWN_AFTER_SUBMIT.name,
            submittedAt = now - (35 * 60_000L),
            createdAt = now - (35 * 60_000L),
            updatedAt = now
        )
        context.getMessagesDB().SmsSendDao().insertOperation(sendOp)

        val list = getMessageHistoryUseCase(50)
        val item = list.find { it.operationId == opId }

        assertNotNull(item)
        assertEquals(SmsSendState.UNKNOWN_AFTER_SUBMIT.name, item?.state)
    }

    @Test
    fun testDetailTimelineGeneration() = runBlocking {
        val opId = "op-timeline-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        val sendOp = SmsSendOperationEntity(
            sendOperationId = opId,
            triggerType = SmsSendTriggerType.SCHEDULED_ALARM.name,
            state = SmsSendState.SENT.name,
            submittedAt = now - 2000,
            sentAt = now - 1000,
            createdAt = now - 5000,
            updatedAt = now - 1000
        )
        context.getMessagesDB().SmsSendDao().insertOperation(sendOp)

        val timeline = getMessageDetailTimelineUseCase(opId)
        assertNotNull(timeline)
        assertTrue((timeline?.stages?.size ?: 0) >= 3)
    }

    @Test
    fun testMessageCenterViewModelLoading() = runBlocking {
        val viewModel = MessageCenterViewModel(getMessageHistoryUseCase)
        viewModel.loadMessageHistory()
        Thread.sleep(150)

        val state = viewModel.uiState.value
        assertTrue(state.isSuccess)
    }
}
