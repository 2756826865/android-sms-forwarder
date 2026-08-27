package org.fossify.messages.databases

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.fossify.messages.models.SmsSendOperationEntity
import org.fossify.messages.models.SmsSendPartEntity
import org.fossify.messages.models.SmsSendState
import org.fossify.messages.models.SmsSendTriggerType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SmsSendDaoTest {

    private lateinit var db: MessagesDatabase
    private lateinit var dao: org.fossify.messages.interfaces.SmsSendDao

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, MessagesDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.SmsSendDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun sampleOperation(
        id: String = "send-op-1",
        state: String = SmsSendState.PENDING.name
    ) = SmsSendOperationEntity(
        sendOperationId = id,
        triggerType = SmsSendTriggerType.LEGACY_UNKNOWN.name,
        subscriptionId = 1,
        threadId = 100L,
        requireDeliveryReport = true,
        state = state
    )

    @Test
    fun insertAndRetrieve() = runBlocking {
        val operation = sampleOperation()
        dao.insertOperation(operation)

        val retrieved = dao.getOperationById("send-op-1")
        assertNotNull(retrieved)
        assertEquals(SmsSendTriggerType.LEGACY_UNKNOWN.name, retrieved!!.triggerType)
        assertEquals(SmsSendState.PENDING.name, retrieved.state)
        assertTrue(retrieved.requireDeliveryReport)
    }

    @Test
    fun updateProviderAssociation() = runBlocking {
        dao.insertOperation(sampleOperation())
        dao.updateProviderAssociation("send-op-1", 42L, "content://sms/42")

        val retrieved = dao.getOperationById("send-op-1")
        assertNotNull(retrieved)
        assertEquals(42L, retrieved!!.providerMessageId)
        assertEquals("content://sms/42", retrieved.messageUri)
    }

    @Test
    fun insertPartsAndRetrieve() = runBlocking {
        dao.insertOperation(sampleOperation())
        dao.insertPart(SmsSendPartEntity(sendOperationId = "send-op-1", partIndex = 0, partCount = 2))
        dao.insertPart(SmsSendPartEntity(sendOperationId = "send-op-1", partIndex = 1, partCount = 2))

        val parts = dao.getPartsByOperationId("send-op-1")
        assertEquals(2, parts.size)
        assertEquals(0, parts[0].partIndex)
        assertEquals(1, parts[1].partIndex)
    }

    @Test
    fun uniquePartConstraint() = runBlocking {
        dao.insertOperation(sampleOperation())
        val part = SmsSendPartEntity(sendOperationId = "send-op-1", partIndex = 0, partCount = 1)
        dao.insertPart(part)

        // Inserting a part with the same (operation_id, part_index) should replace
        dao.insertPart(part.copy(sentState = SmsSendState.SENT.name))
        val parts = dao.getPartsByOperationId("send-op-1")
        assertEquals(1, parts.size)
        assertEquals(SmsSendState.SENT.name, parts[0].sentState)
    }

    @Test
    fun updateState() = runBlocking {
        dao.insertOperation(sampleOperation())
        dao.updateState("send-op-1", SmsSendState.SUBMITTED.name)

        val retrieved = dao.getOperationById("send-op-1")
        assertNotNull(retrieved)
        assertEquals(SmsSendState.SUBMITTED.name, retrieved!!.state)
    }

    @Test
    fun deleteExpired() = runBlocking {
        val oldTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        val oldOp = sampleOperation(id = "send-op-old").copy(createdAt = oldTime)
        val newOp = sampleOperation(id = "send-op-new")

        dao.insertOperation(oldOp)
        dao.insertOperation(newOp)

        val cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(14)
        dao.deleteExpired(cutoff)

        assertNull(dao.getOperationById("send-op-old"))
        assertNotNull(dao.getOperationById("send-op-new"))
    }

    @Test
    fun getByProviderMessageId() = runBlocking {
        dao.insertOperation(sampleOperation().copy(providerMessageId = 99L))

        val retrieved = dao.getOperationByProviderMessageId(99L)
        assertNotNull(retrieved)
        assertEquals("send-op-1", retrieved!!.sendOperationId)

        assertNull(dao.getOperationByProviderMessageId(999L))
    }

    @Test
    fun createOperationWithParts() = runBlocking {
        val operation = sampleOperation()
        val parts = listOf(
            SmsSendPartEntity(sendOperationId = "send-op-1", partIndex = 0, partCount = 3),
            SmsSendPartEntity(sendOperationId = "send-op-1", partIndex = 1, partCount = 3),
            SmsSendPartEntity(sendOperationId = "send-op-1", partIndex = 2, partCount = 3)
        )

        dao.createOperationWithParts(operation, parts)

        assertNotNull(dao.getOperationById("send-op-1"))
        val retrievedParts = dao.getPartsByOperationId("send-op-1")
        assertEquals(3, retrievedParts.size)
    }
}
