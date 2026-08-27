package org.fossify.messages.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.OutboxRepository
import org.fossify.messages.models.OutboxSourceType
import org.fossify.messages.models.OutboxTaskContext
import org.fossify.messages.models.OutboxTaskEntity
import org.fossify.messages.models.OutboxTaskState
import org.fossify.messages.models.OutboxTaskType
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
class OutboxDispatcherTest {

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
    fun testPendingTaskClaimedAndDispatchedSuccess() = runBlocking {
        var executed = false
        val testExecutor = object : OutboxExecutor {
            override fun canExecute(taskType: String): Boolean = taskType == "TEST_DISPATCH_SUCCESS"
            override suspend fun execute(context: Context, task: OutboxTaskEntity): OutboxExecutionResult {
                executed = true
                return OutboxExecutionResult.Success
            }
        }
        OutboxExecutorRegistry.register(testExecutor)

        val taskId = OutboxRepository.createTask(
            context,
            OutboxTaskContext(
                taskType = "TEST_DISPATCH_SUCCESS",
                sourceType = OutboxSourceType.REMOTE_COMMAND,
                sourceId = "test-src-1",
                rawPayload = "dispatch payload 1"
            )
        )

        val summary = OutboxDispatcher.dispatchOnce(context, workerId = "worker-success-test")
        assertTrue(summary.claimedCount >= 1)
        assertTrue(summary.successCount >= 1)
        assertTrue(executed)

        Thread.sleep(100)
        val task = context.getMessagesDB().OutboxTaskDao().findById(taskId)
        assertEquals(OutboxTaskState.SUCCESS.name, task?.state)
        assertNull(task?.lockedBy)
    }

    @Test
    fun testDuplicateDispatchersCannotClaimSameTaskConcurrently() = runBlocking {
        val taskId = OutboxRepository.createTask(
            context,
            OutboxTaskContext(
                taskType = "TEST_LOCK_COLLISION",
                sourceType = OutboxSourceType.REMOTE_COMMAND,
                sourceId = "test-src-2",
                rawPayload = "dispatch payload 2"
            )
        )

        // Worker A claims task
        val claimedByA = OutboxRepository.claimTask(context, taskId, workerId = "worker-A", lockDurationMs = 60_000L)
        assertTrue(claimedByA)

        // Worker B attempts to claim the same task -> must fail
        val claimedByB = OutboxRepository.claimTask(context, taskId, workerId = "worker-B", lockDurationMs = 60_000L)
        assertFalse(claimedByB)

        val task = context.getMessagesDB().OutboxTaskDao().findById(taskId)
        assertEquals("worker-A", task?.lockedBy)
        assertEquals(OutboxTaskState.RUNNING.name, task?.state)
    }

    @Test
    fun testRetryableFailureTransitionsToRetryWaiting() = runBlocking {
        val testExecutor = object : OutboxExecutor {
            override fun canExecute(taskType: String): Boolean = taskType == "TEST_DISPATCH_RETRY"
            override suspend fun execute(context: Context, task: OutboxTaskEntity): OutboxExecutionResult {
                return OutboxExecutionResult.Retry(errorClass = "IOException", errorMessage = "Network timeout")
            }
        }
        OutboxExecutorRegistry.register(testExecutor)

        val taskId = OutboxRepository.createTask(
            context,
            OutboxTaskContext(
                taskType = "TEST_DISPATCH_RETRY",
                sourceType = OutboxSourceType.FORWARDING_RULE,
                sourceId = "test-src-3",
                rawPayload = "dispatch payload 3",
                maxAttempts = 3
            )
        )

        val summary = OutboxDispatcher.dispatchOnce(context, workerId = "worker-retry-test")
        assertTrue(summary.retryCount >= 1)

        Thread.sleep(100)
        val task = context.getMessagesDB().OutboxTaskDao().findById(taskId)
        assertEquals(OutboxTaskState.RETRY_WAITING.name, task?.state)
        assertEquals(1, task?.attemptCount)
        assertEquals("IOException", task?.lastErrorClass)
        assertNotNull(task?.lastErrorHmac)
        assertTrue((task?.nextRetryAt ?: 0L) > System.currentTimeMillis() - 1000L)
        assertNull(task?.lockedBy)
    }

    @Test
    fun testLockExpiredTaskCanBeReclaimed() = runBlocking {
        val taskId = OutboxRepository.createTask(
            context,
            OutboxTaskContext(
                taskType = "TEST_EXPIRED_LOCK",
                sourceType = OutboxSourceType.BULK_SEND,
                sourceId = "test-src-4",
                rawPayload = "dispatch payload 4"
            )
        )

        val dao = context.getMessagesDB().OutboxTaskDao()
        // Simulate a crashed worker whose lock has expired 10 seconds ago
        val expiredTask = dao.findById(taskId)!!.copy(
            state = OutboxTaskState.RUNNING.name,
            lockedBy = "crashed-worker-old",
            lockExpiresAt = System.currentTimeMillis() - 10_000L
        )
        dao.update(expiredTask)

        // New worker attempts to claim expired task -> must succeed
        val reclaimed = OutboxRepository.claimTask(context, taskId, workerId = "new-alive-worker", lockDurationMs = 30_000L)
        assertTrue(reclaimed)

        val current = dao.findById(taskId)
        assertEquals("new-alive-worker", current?.lockedBy)
        assertEquals(OutboxTaskState.RUNNING.name, current?.state)
    }
}
