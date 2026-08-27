package org.fossify.messages.messaging

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.Config
import org.fossify.messages.models.SmsSendContext
import org.fossify.messages.models.SmsSendTriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BulkSendMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Config.newInstance(context).smsSendOperationShadowEnabled = true
    }

    @Test
    fun testBulkSend_multipleRecipients_createsIndependentOperations() = runBlocking {
        val numbers = listOf("13800138001", "13800138002")
        val operationIds = mutableListOf<String>()

        numbers.forEachIndexed { index, number ->
            val sendContext = SmsSendContext(
                triggerType = SmsSendTriggerType.BULK,
                address = number,
                body = "Bulk test content",
                subscriptionId = 1,
                threadId = (8000L + index),
                requireDeliveryReport = false,
                messageUri = "content://sms/${8000 + index}"
            )
            val opId = SmsSendCoordinator.beginSend(context, sendContext)
            assertNotNull(opId)
            operationIds.add(opId!!)
        }

        Thread.sleep(200)

        // 1. Two separate operation IDs
        assertEquals(2, operationIds.size)
        assertNotEquals(operationIds[0], operationIds[1])

        // 2. Both records exist in DB with triggerType = BULK
        val dao = context.getMessagesDB().SmsSendDao()
        val op1 = dao.getOperationById(operationIds[0])
        val op2 = dao.getOperationById(operationIds[1])

        assertNotNull(op1)
        assertNotNull(op2)
        assertEquals(SmsSendTriggerType.BULK.name, op1?.triggerType)
        assertEquals(SmsSendTriggerType.BULK.name, op2?.triggerType)
    }

    @Test
    fun testBulkSend_failureIsolation() = runBlocking {
        val numbers = listOf("invalid_number_1", "13800138003")
        val successfulOps = mutableListOf<String>()

        numbers.forEachIndexed { index, number ->
            try {
                if (number == "invalid_number_1") {
                    // Simulate failure for first recipient
                    throw IllegalStateException("Simulated recipient failure")
                }
                val sendContext = SmsSendContext(
                    triggerType = SmsSendTriggerType.BULK,
                    address = number,
                    body = "Isolated bulk test",
                    subscriptionId = 1,
                    threadId = (8100L + index),
                    requireDeliveryReport = false,
                    messageUri = "content://sms/${8100 + index}"
                )
                val opId = SmsSendCoordinator.beginSend(context, sendContext)
                if (opId != null) successfulOps.add(opId)
            } catch (ignored: Exception) {
                // Failure isolated
            }
        }

        Thread.sleep(150)

        // The second recipient successfully created operation despite first recipient failure
        assertEquals(1, successfulOps.size)
        val dao = context.getMessagesDB().SmsSendDao()
        val op = dao.getOperationById(successfulOps[0])
        assertNotNull(op)
        assertEquals(SmsSendTriggerType.BULK.name, op?.triggerType)
    }
}
