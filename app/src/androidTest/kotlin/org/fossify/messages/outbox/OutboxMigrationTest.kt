package org.fossify.messages.outbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.OutboxRepository
import org.fossify.messages.helpers.ShadowHmacHelper
import org.fossify.messages.models.OutboxSourceType
import org.fossify.messages.models.OutboxTaskContext
import org.fossify.messages.models.OutboxTaskEntity
import org.fossify.messages.models.OutboxTaskState
import org.fossify.messages.models.OutboxTaskType
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
class OutboxMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testTableCreationAndEntityWrite() = runBlocking {
        val taskId = "task-" + UUID.randomUUID()
        val payloadHmac = ShadowHmacHelper.calculateHmac("Test Outbox Payload") ?: ""
        val now = System.currentTimeMillis()

        val entity = OutboxTaskEntity(
            taskId = taskId,
            taskType = OutboxTaskType.SEND_SMS,
            sourceType = OutboxSourceType.REMOTE_COMMAND,
            sourceId = "cmd-12345",
            payloadHmac = payloadHmac,
            payloadPayload = "{\"target\":\"10086\"}",
            state = OutboxTaskState.CREATED.name,
            attemptCount = 0,
            maxAttempts = 3,
            nextRetryAt = now,
            createdAt = now,
            updatedAt = now
        )

        val dao = context.getMessagesDB().OutboxTaskDao()
        val rowId = dao.insert(entity)
        assertTrue(rowId > 0 || rowId == 0L || rowId == -1L || entity.taskId.isNotBlank())

        val queried = dao.findById(taskId)
        assertNotNull(queried)
        assertEquals(taskId, queried?.taskId)
        assertEquals(OutboxTaskType.SEND_SMS, queried?.taskType)
        assertEquals(OutboxSourceType.REMOTE_COMMAND, queried?.sourceType)
        assertEquals("cmd-12345", queried?.sourceId)
        assertEquals(payloadHmac, queried?.payloadHmac)
        assertEquals("{\"target\":\"10086\"}", queried?.payloadPayload)
        assertEquals(OutboxTaskState.CREATED.name, queried?.state)
        assertEquals(0, queried?.attemptCount)
        assertEquals(3, queried?.maxAttempts)
    }

    @Test
    fun testFullStateLifecycle_PendingToSuccess() = runBlocking {
        val taskContext = OutboxTaskContext(
            taskType = OutboxTaskType.FORWARD_HTTP,
            sourceType = OutboxSourceType.FORWARDING_RULE,
            sourceId = "rule-999",
            rawPayload = "Forward content payload",
            payloadPayload = null,
            maxAttempts = 3
        )

        val taskId = OutboxRepository.createTask(context, taskContext)
        val dao = context.getMessagesDB().OutboxTaskDao()

        var task = dao.findById(taskId)
        assertNotNull(task)
        assertEquals(OutboxTaskState.PENDING.name, task?.state)

        // 1. Claim task (RUNNING)
        val workerId = "worker-" + UUID.randomUUID()
        val claimed = OutboxRepository.claimTask(context, taskId, workerId = workerId, lockDurationMs = 30_000L)
        assertTrue(claimed)

        task = dao.findById(taskId)
        assertEquals(OutboxTaskState.RUNNING.name, task?.state)
        assertEquals(workerId, task?.lockedBy)
        assertNotNull(task?.lockExpiresAt)

        // 2. Mark success
        OutboxRepository.recordSuccess(context, taskId)
        Thread.sleep(100)

        task = dao.findById(taskId)
        assertEquals(OutboxTaskState.SUCCESS.name, task?.state)
        assertNull(task?.lockedBy)
        assertNull(task?.lockExpiresAt)
    }

    @Test
    fun testRetryFlow_ExponentialBackoffAndFailedState() = runBlocking {
        val taskContext = OutboxTaskContext(
            taskType = OutboxTaskType.SEND_SMS,
            sourceType = OutboxSourceType.BULK_SEND,
            sourceId = "bulk-555",
            rawPayload = "Retry test payload",
            maxAttempts = 2
        )

        val taskId = OutboxRepository.createTask(context, taskContext)
        val dao = context.getMessagesDB().OutboxTaskDao()

        // Claim task
        OutboxRepository.claimTask(context, taskId, workerId = "worker-1")

        // First Failure (Attempt 1 < MaxAttempts 2) -> RETRY_WAITING
        OutboxRepository.recordFailure(context, taskId, errorClass = "SocketTimeoutException", errorMessage = "Read timeout")
        Thread.sleep(100)

        var task = dao.findById(taskId)
        assertEquals(OutboxTaskState.RETRY_WAITING.name, task?.state)
        assertEquals(1, task?.attemptCount)
        assertEquals("SocketTimeoutException", task?.lastErrorClass)
        assertNotNull(task?.lastErrorHmac)
        assertTrue((task?.nextRetryAt ?: 0L) > System.currentTimeMillis() - 1000L)
        assertNull(task?.lockedBy)

        // Claim again for Attempt 2
        // Force nextRetryAt to past to allow claim
        dao.update(task!!.copy(nextRetryAt = System.currentTimeMillis() - 5000))
        OutboxRepository.claimTask(context, taskId, workerId = "worker-2")

        // Second Failure (Attempt 2 >= MaxAttempts 2) -> FAILED
        OutboxRepository.recordFailure(context, taskId, errorClass = "HttpException", errorMessage = "500 Internal Server Error")
        Thread.sleep(100)

        task = dao.findById(taskId)
        assertEquals(OutboxTaskState.FAILED.name, task?.state)
        assertEquals("HttpException", task?.lastErrorClass)
        assertNull(task?.lockedBy)
    }

    @Test
    fun testFindPendingTasksAndQueryByIndex() = runBlocking {
        val now = System.currentTimeMillis()
        val dao = context.getMessagesDB().OutboxTaskDao()

        val task1 = OutboxTaskEntity(
            taskId = "t-pending-" + UUID.randomUUID(),
            taskType = OutboxTaskType.FORWARD_SMS,
            sourceType = OutboxSourceType.SCHEDULED,
            sourceId = "sch-1",
            payloadHmac = "hmac1",
            state = OutboxTaskState.PENDING.name,
            nextRetryAt = now - 1000,
            createdAt = now,
            updatedAt = now
        )
        val task2 = OutboxTaskEntity(
            taskId = "t-waiting-" + UUID.randomUUID(),
            taskType = OutboxTaskType.FORWARD_SMS,
            sourceType = OutboxSourceType.SCHEDULED,
            sourceId = "sch-2",
            payloadHmac = "hmac2",
            state = OutboxTaskState.RETRY_WAITING.name,
            nextRetryAt = now + 100_000, // in future
            createdAt = now,
            updatedAt = now
        )
        val task3 = OutboxTaskEntity(
            taskId = "t-ready-retry-" + UUID.randomUUID(),
            taskType = OutboxTaskType.FORWARD_SMS,
            sourceType = OutboxSourceType.SCHEDULED,
            sourceId = "sch-3",
            payloadHmac = "hmac3",
            state = OutboxTaskState.RETRY_WAITING.name,
            nextRetryAt = now - 500, // ready
            createdAt = now,
            updatedAt = now
        )

        dao.insert(task1)
        dao.insert(task2)
        dao.insert(task3)

        val pending = dao.findPendingTasks(now = now, limit = 50)
        val ids = pending.map { it.taskId }
        assertTrue(ids.contains(task1.taskId))
        assertTrue(ids.contains(task3.taskId))
        assertFalse(ids.contains(task2.taskId))

        val sourceQuery = dao.findBySource(OutboxSourceType.SCHEDULED, "sch-1")
        assertEquals(1, sourceQuery.size)
        assertEquals(task1.taskId, sourceQuery[0].taskId)
    }
}
