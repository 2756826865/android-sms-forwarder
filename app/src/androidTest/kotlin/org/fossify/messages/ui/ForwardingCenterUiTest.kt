package org.fossify.messages.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.models.ForwardingShadowDelivery
import org.fossify.messages.models.OutboxTaskEntity
import org.fossify.messages.models.OutboxTaskState
import org.fossify.messages.models.OutboxTaskType
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.forwarding.ForwardingViewModel
import org.fossify.messages.ui.forwarding.repository.ForwardingCenterRepository
import org.fossify.messages.ui.forwarding.usecase.GetChannelHealthUseCase
import org.fossify.messages.ui.forwarding.usecase.GetForwardingCenterStateUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ForwardingCenterUiTest {

    private lateinit var context: Context
    private lateinit var repository: ForwardingCenterRepository
    private lateinit var getForwardingCenterStateUseCase: GetForwardingCenterStateUseCase
    private lateinit var getChannelHealthUseCase: GetChannelHealthUseCase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = ForwardingCenterRepository(context)
        getForwardingCenterStateUseCase = GetForwardingCenterStateUseCase(repository)
        getChannelHealthUseCase = GetChannelHealthUseCase(repository)
    }

    @Test
    fun testForwardingHistoryAndChannelHealth() = runBlocking {
        val now = System.currentTimeMillis()
        val channelName = "DingTalkBot"

        // 1. Insert 2 Successful Deliveries and 1 Failed
        val d1 = ForwardingShadowDelivery(
            deliveryId = "del-fc-1-" + UUID.randomUUID(),
            operationId = "op-fc-1",
            channel = channelName,
            state = "DELIVERED",
            createdAt = now - 5000,
            updatedAt = now - 4000
        )
        val d2 = ForwardingShadowDelivery(
            deliveryId = "del-fc-2-" + UUID.randomUUID(),
            operationId = "op-fc-2",
            channel = channelName,
            state = "DELIVERED",
            createdAt = now - 3000,
            updatedAt = now - 2000
        )
        val d3 = ForwardingShadowDelivery(
            deliveryId = "del-fc-3-" + UUID.randomUUID(),
            operationId = "op-fc-3",
            channel = channelName,
            state = "FAILED",
            createdAt = now - 1000,
            updatedAt = now - 500
        )
        val dao = context.getMessagesDB().ShadowDaos()
        dao.insertDelivery(d1)
        dao.insertDelivery(d2)
        dao.insertDelivery(d3)

        // 2. Compute Health via UseCase
        val channelHealthList = getChannelHealthUseCase()
        val health = channelHealthList.find { it.channel == channelName }

        assertNotNull(health)
        assertTrue((health?.totalDeliveries ?: 0) >= 3)
        assertTrue((health?.successCount ?: 0) >= 2)
        assertTrue((health?.failureCount ?: 0) >= 1)
        assertTrue((health?.successRate ?: 0.0) in 60.0..70.0)
    }

    @Test
    fun testOutboxTaskStatusInForwardingCenter() = runBlocking {
        val taskId = "task-fc-retry-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        val task = OutboxTaskEntity(
            taskId = taskId,
            taskType = OutboxTaskType.FORWARD_HTTP,
            sourceType = "FORWARDING_RULE",
            sourceId = "rule-fc",
            payloadHmac = "hmac-fc",
            state = OutboxTaskState.RETRY_WAITING.name,
            createdAt = now,
            updatedAt = now
        )
        context.getMessagesDB().OutboxTaskDao().insert(task)

        val state = getForwardingCenterStateUseCase()
        assertTrue(state.retryOutboxCount >= 1)
    }

    @Test
    fun testForwardingViewModelLoading() = runBlocking {
        val viewModel = ForwardingViewModel(getForwardingCenterStateUseCase)
        viewModel.loadForwardingCenter()
        Thread.sleep(150)

        val state = viewModel.uiState.value
        assertTrue(state.isSuccess)
    }
}
