package org.fossify.messages.recovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.ShadowHmacHelper
import org.fossify.messages.models.OutboxSourceType
import org.fossify.messages.models.OutboxTaskEntity
import org.fossify.messages.models.OutboxTaskState
import org.fossify.messages.models.OutboxTaskType
import org.fossify.messages.models.RecoveryAction
import org.fossify.messages.models.RecoveryObjectType
import org.fossify.messages.models.RecoveryTriggerSource
import org.fossify.messages.outbox.OutboxExecutor
import org.fossify.messages.outbox.OutboxExecutorRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class RecoveryEngineTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        OutboxExecutorRegistry.clear()
    }

    @After
    fun tearDown() {
        OutboxExecutorRegistry.clear()
    }

    @Test
    fun testDeadlockedRunningTask_RecoveredToPending() = runBlocking {
        val taskId = "task-deadlock-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        // 1. Create a deadlocked RUNNING task with expired lock
        val task = OutboxTaskEntity(
            taskId = taskId,
            taskType = OutboxTaskType.FORWARD_HTTP,
            sourceType = OutboxSourceType.FORWARDING_RULE,
            sourceId = "rule-101",
            payloadHmac = ShadowHmacHelper.calculateHmac("payload 1") ?: "",
            payloadPayload = "{}",
            state = OutboxTaskState.RUNNING.name,
            lockedBy = "crashed-worker-99",
            lockExpiresAt = now - 15_000L, // expired 15 seconds ago
            createdAt = now - 60_000L,
            updatedAt = now - 60_000L
        )

        val outboxDao = context.getMessagesDB().OutboxTaskDao()
        outboxDao.insert(task)

        // 2. Run Recovery scan
        val summary = RecoveryEngine.runRecoveryScan(context, triggerSource = RecoveryTriggerSource.STARTUP)
        assertTrue(summary.unlockedTasks >= 1)
        assertTrue(summary.totalRecovered >= 1)

        // 3. Verify task state is now PENDING and lock is cleared
        val recoveredTask = outboxDao.findById(taskId)
        assertNotNull(recoveredTask)
        assertEquals(OutboxTaskState.PENDING.name, recoveredTask?.state)
        assertNull(recoveredTask?.lockedBy)
        assertNull(recoveredTask?.lockExpiresAt)

        // 4. Verify RecoveryRecord was written
        val records = context.getMessagesDB().RecoveryRecordDao().queryLatest(10)
        val matching = records.find { it.objectId == taskId }
        assertNotNull(matching)
        assertEquals(RecoveryObjectType.OUTBOX_TASK, matching?.objectType)
        assertEquals(OutboxTaskState.RUNNING.name, matching?.initialStatus)
        assertEquals(OutboxTaskState.PENDING.name, matching?.recoveredStatus)
        assertEquals(RecoveryAction.UNLOCK_TASK, matching?.actionTaken)
    }

    @Test
    fun testDueRetryTask_AdvancedToPending() = runBlocking {
        val taskId = "task-due-retry-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        // 1. Create a RETRY_WAITING task whose nextRetryAt has arrived
        val task = OutboxTaskEntity(
            taskId = taskId,
            taskType = OutboxTaskType.FORWARD_HTTP,
            sourceType = OutboxSourceType.FORWARDING_RULE,
            sourceId = "rule-102",
            payloadHmac = ShadowHmacHelper.calculateHmac("payload 2") ?: "",
            payloadPayload = "{}",
            state = OutboxTaskState.RETRY_WAITING.name,
            attemptCount = 1,
            maxAttempts = 3,
            nextRetryAt = now - 5_000L, // ready 5 seconds ago
            createdAt = now - 60_000L,
            updatedAt = now - 60_000L
        )

        val outboxDao = context.getMessagesDB().OutboxTaskDao()
        outboxDao.insert(task)

        // 2. Run Recovery scan
        val summary = RecoveryEngine.runRecoveryScan(context, triggerSource = RecoveryTriggerSource.PERIODIC_WORKER)
        assertTrue(summary.resetPendingTasks >= 1)

        // 3. Verify task state is now PENDING
        val recoveredTask = outboxDao.findById(taskId)
        assertNotNull(recoveredTask)
        assertEquals(OutboxTaskState.PENDING.name, recoveredTask?.state)

        // 4. Verify RecoveryRecord
        val records = context.getMessagesDB().RecoveryRecordDao().queryLatest(10)
        val matching = records.find { it.objectId == taskId }
        assertNotNull(matching)
        assertEquals(RecoveryObjectType.OUTBOX_TASK, matching?.objectType)
        assertEquals(OutboxTaskState.RETRY_WAITING.name, matching?.initialStatus)
        assertEquals(OutboxTaskState.PENDING.name, matching?.recoveredStatus)
        assertEquals(RecoveryAction.RESET_PENDING, matching?.actionTaken)
    }

    @Test
    fun testRecoveryScanDoesNotInvokeTaskExecutors() = runBlocking {
        var executorCalled = false
        val testExecutor = object : OutboxExecutor {
            override fun canExecute(taskType: String): Boolean = true
            override suspend fun execute(context: Context, task: OutboxTaskEntity): org.fossify.messages.outbox.OutboxExecutionResult {
                executorCalled = true
                return org.fossify.messages.outbox.OutboxExecutionResult.Success
            }
        }
        OutboxExecutorRegistry.register(testExecutor)

        // Run recovery scan
        RecoveryEngine.runRecoveryScan(context, triggerSource = RecoveryTriggerSource.MANUAL)

        // Executor must NOT be called during recovery scan
        assertFalse(executorCalled)
    }
}
