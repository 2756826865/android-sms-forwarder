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
import org.fossify.messages.models.SmsSendOperationEntity
import org.fossify.messages.models.SmsSendState
import org.fossify.messages.models.SmsSendTriggerType
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.dashboard.DashboardViewModel
import org.fossify.messages.ui.repository.DashboardDataRepository
import org.fossify.messages.ui.usecase.GetDashboardStatsUseCase
import org.fossify.messages.ui.usecase.GetForwardingStatusUseCase
import org.fossify.messages.ui.usecase.GetRecoveryRecordsUseCase
import org.fossify.messages.ui.usecase.GetRemoteCommandHistoryUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class UIArchitectureTest {

    private lateinit var context: Context
    private lateinit var repository: DashboardDataRepository
    private lateinit var getDashboardStatsUseCase: GetDashboardStatsUseCase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = DashboardDataRepository(context)
        getDashboardStatsUseCase = GetDashboardStatsUseCase(repository)
    }

    @Test
    fun testViewModelCreationAndInitialState() {
        val viewModel = DashboardViewModel(getDashboardStatsUseCase)
        assertNotNull(viewModel)
        assertEquals(UiState.Idle, viewModel.uiState.value)
    }

    @Test
    fun testRepositoryReadsFactsCleanly() = runBlocking {
        val stats = repository.getDashboardStats()
        assertNotNull(stats)
        assertNotNull(stats.deviceProfile)
        assertTrue(stats.lastUpdated > 0)
    }

    @Test
    fun testUseCaseCallingPipeline() = runBlocking {
        val stats = getDashboardStatsUseCase()
        assertNotNull(stats)

        val recoveryUseCase = GetRecoveryRecordsUseCase(repository)
        val recoveryRecords = recoveryUseCase(10)
        assertNotNull(recoveryRecords)

        val remoteHistoryUseCase = GetRemoteCommandHistoryUseCase(repository)
        val commands = remoteHistoryUseCase(10)
        assertNotNull(commands)

        val forwardingStatusUseCase = GetForwardingStatusUseCase(repository)
        val deliveries = forwardingStatusUseCase(10)
        assertNotNull(deliveries)
    }

    @Test
    fun testDashboardDataAggregation() = runBlocking {
        val now = System.currentTimeMillis()
        val opId = "op-ui-test-" + UUID.randomUUID()
        val taskId = "task-ui-test-" + UUID.randomUUID()
        val deliveryId = "del-ui-test-" + UUID.randomUUID()

        // 1. Insert an SmsSendOperation created today
        val sendOp = SmsSendOperationEntity(
            sendOperationId = opId,
            triggerType = SmsSendTriggerType.THREAD.name,
            state = SmsSendState.SENT.name,
            createdAt = now,
            updatedAt = now
        )
        context.getMessagesDB().SmsSendDao().insertOperation(sendOp)

        // 2. Insert an OutboxTask in PENDING state
        val outboxTask = OutboxTaskEntity(
            taskId = taskId,
            taskType = OutboxTaskType.FORWARD_HTTP,
            sourceType = "FORWARDING_RULE",
            sourceId = "rule-ui",
            payloadHmac = "hmac-ui",
            state = OutboxTaskState.PENDING.name,
            createdAt = now,
            updatedAt = now
        )
        context.getMessagesDB().OutboxTaskDao().insert(outboxTask)

        // 3. Insert a ForwardingDelivery in DELIVERED state
        val delivery = ForwardingShadowDelivery(
            deliveryId = deliveryId,
            operationId = "op-shadow",
            channel = "PushPlus",
            state = "DELIVERED",
            createdAt = now,
            updatedAt = now
        )
        context.getMessagesDB().ShadowDaos().insertDelivery(delivery)

        // 4. Query via UseCase
        val stats = getDashboardStatsUseCase()
        assertTrue(stats.todaySentCount >= 1)
        assertTrue(stats.pendingOutboxCount >= 1)
        assertTrue(stats.todayForwardSuccessCount >= 1)
    }

    @Test
    fun testViewModelSynchronousRefresh() = runBlocking {
        val viewModel = DashboardViewModel(getDashboardStatsUseCase)
        val stats = viewModel.refreshSync()
        assertNotNull(stats)
        assertTrue(viewModel.uiState.value.isSuccess)
        assertEquals(stats, viewModel.uiState.value.getOrNull())
    }
}
