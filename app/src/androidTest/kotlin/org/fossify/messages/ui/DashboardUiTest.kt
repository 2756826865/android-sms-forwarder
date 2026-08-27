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
import org.fossify.messages.models.RecoveryAction
import org.fossify.messages.models.RecoveryObjectType
import org.fossify.messages.models.RecoveryRecordEntity
import org.fossify.messages.models.RecoveryTriggerSource
import org.fossify.messages.models.SmsSendOperationEntity
import org.fossify.messages.models.SmsSendState
import org.fossify.messages.models.SmsSendTriggerType
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.dashboard.DashboardViewModel
import org.fossify.messages.ui.repository.DashboardDataRepository
import org.fossify.messages.ui.usecase.GetDashboardStatsUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DashboardUiTest {

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
    fun testDashboardViewModelLoadsSuccessfully() = runBlocking {
        val viewModel = DashboardViewModel(getDashboardStatsUseCase)
        val stats = viewModel.refreshSync()

        assertNotNull(stats)
        assertTrue(viewModel.uiState.value.isSuccess)
        assertNotNull(stats.deviceProfile)
        assertTrue(stats.lastUpdated > 0)
    }

    @Test
    fun testDashboardStatisticsReflectDatabaseFacts() = runBlocking {
        val now = System.currentTimeMillis()
        val opId = "op-dash-test-" + UUID.randomUUID()
        val taskId = "task-dash-test-" + UUID.randomUUID()
        val recId = "rec-dash-test-" + UUID.randomUUID()

        // 1. Insert SMS Operation (SENT)
        val sendOp = SmsSendOperationEntity(
            sendOperationId = opId,
            triggerType = SmsSendTriggerType.BULK.name,
            state = SmsSendState.SENT.name,
            createdAt = now,
            updatedAt = now
        )
        context.getMessagesDB().SmsSendDao().insertOperation(sendOp)

        // 2. Insert OutboxTask (RETRY_WAITING)
        val task = OutboxTaskEntity(
            taskId = taskId,
            taskType = OutboxTaskType.SEND_SMS,
            sourceType = "BULK_SEND",
            sourceId = opId,
            payloadHmac = "hmac-dash",
            state = OutboxTaskState.RETRY_WAITING.name,
            createdAt = now,
            updatedAt = now
        )
        context.getMessagesDB().OutboxTaskDao().insert(task)

        // 3. Insert RecoveryRecord
        val record = RecoveryRecordEntity(
            recordId = recId,
            scanTime = now,
            triggerSource = RecoveryTriggerSource.STARTUP,
            objectType = RecoveryObjectType.OUTBOX_TASK,
            objectId = taskId,
            initialStatus = OutboxTaskState.RUNNING.name,
            recoveredStatus = OutboxTaskState.PENDING.name,
            actionTaken = RecoveryAction.UNLOCK_TASK,
            detailMessage = "Dash test unlock",
            createdAt = now
        )
        context.getMessagesDB().RecoveryRecordDao().insert(record)

        // 4. Query stats
        val stats = getDashboardStatsUseCase()
        assertTrue(stats.todaySentCount >= 1)
        assertTrue(stats.todaySuccessCount >= 1)
        assertTrue(stats.retryOutboxCount >= 1)
        assertTrue(stats.totalRecoveryEvents >= 1)
        assertEquals(RecoveryAction.UNLOCK_TASK, stats.lastRecoveryAction)
    }

    @Test
    fun testEmptyDatabaseProducesSafeZeroCounts() = runBlocking {
        val stats = getDashboardStatsUseCase()
        assertNotNull(stats)
        assertTrue(stats.todaySentCount >= 0)
        assertTrue(stats.todayFailedSendCount >= 0)
        assertTrue(stats.pendingOutboxCount >= 0)
        assertTrue(stats.totalRecoveryEvents >= 0)
    }
}
