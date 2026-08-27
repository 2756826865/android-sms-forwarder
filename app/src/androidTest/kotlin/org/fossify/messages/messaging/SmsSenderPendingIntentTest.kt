package org.fossify.messages.messaging

import android.app.Application
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.receivers.SendStatusReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsSenderPendingIntentTest {

    private lateinit var app: Application
    private lateinit var smsSender: SmsSender
    private val testUri = Uri.parse("content://sms/1001")

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        smsSender = SmsSender.getInstance(app)
    }

    @Test
    fun testSinglePartSendStatusIntent_hasCorrectExtras() {
        val opId = "test-operation-single-001"
        val intent = smsSender.getSendStatusIntent(
            requestUri = testUri,
            subId = 1,
            guardKey = "guard-123",
            threadId = 42L,
            address = "10086",
            sendOperationId = opId,
            partIndex = 0,
            partCount = 1,
            isLastPart = true
        )

        assertEquals(testUri, intent.data)
        assertEquals(1, intent.getIntExtra(SendStatusReceiver.EXTRA_SUB_ID, -1))
        assertEquals(42L, intent.getLongExtra(SendStatusReceiver.EXTRA_THREAD_ID, -1L))
        assertEquals("10086", intent.getStringExtra(SendStatusReceiver.EXTRA_ADDRESS))
        assertEquals("guard-123", intent.getStringExtra(SendStatusReceiver.EXTRA_SEND_GUARD_KEY))
        assertEquals(opId, intent.getStringExtra(SendStatusReceiver.EXTRA_SEND_OPERATION_ID))
        assertEquals(0, intent.getIntExtra(SendStatusReceiver.EXTRA_PART_INDEX, -1))
        assertEquals(1, intent.getIntExtra(SendStatusReceiver.EXTRA_PART_COUNT, -1))
        assertTrue(intent.getBooleanExtra(SendStatusReceiver.EXTRA_IS_LAST_PART, false))
    }

    @Test
    fun testMultiPartSendStatusIntent_hasCorrectPartIndices() {
        val opId = "test-operation-multi-002"
        val totalParts = 3

        for (i in 0 until totalParts) {
            val isLast = (i == totalParts - 1)
            val intent = smsSender.getSendStatusIntent(
                requestUri = testUri,
                subId = 2,
                guardKey = "guard-multi",
                threadId = 99L,
                address = "13800138000",
                sendOperationId = opId,
                partIndex = i,
                partCount = totalParts,
                isLastPart = isLast
            )

            assertEquals(opId, intent.getStringExtra(SendStatusReceiver.EXTRA_SEND_OPERATION_ID))
            assertEquals(i, intent.getIntExtra(SendStatusReceiver.EXTRA_PART_INDEX, -1))
            assertEquals(totalParts, intent.getIntExtra(SendStatusReceiver.EXTRA_PART_COUNT, -1))
            assertEquals(isLast, intent.getBooleanExtra(SendStatusReceiver.EXTRA_IS_LAST_PART, !isLast))
        }
    }

    @Test
    fun testNullOperationId_legacyCompatibility() {
        val intent = smsSender.getSendStatusIntent(
            requestUri = testUri,
            subId = 1,
            guardKey = "",
            threadId = 10L,
            address = "10010",
            sendOperationId = null,
            partIndex = 0,
            partCount = 1,
            isLastPart = true
        )

        assertNull(intent.getStringExtra(SendStatusReceiver.EXTRA_SEND_OPERATION_ID))
        assertEquals(0, intent.getIntExtra(SendStatusReceiver.EXTRA_PART_INDEX, -1))
        assertEquals(1, intent.getIntExtra(SendStatusReceiver.EXTRA_PART_COUNT, -1))
        assertTrue(intent.getBooleanExtra(SendStatusReceiver.EXTRA_IS_LAST_PART, false))
    }

    @Test
    fun testDeliveredStatusIntent_hasCorrectExtras() {
        val opId = "test-operation-delivered-003"
        val intent = smsSender.getDeliveredStatusIntent(
            requestUri = testUri,
            subId = 1,
            sendOperationId = opId,
            partIndex = 2,
            partCount = 3,
            isLastPart = true
        )

        assertEquals(testUri, intent.data)
        assertEquals(1, intent.getIntExtra(SendStatusReceiver.EXTRA_SUB_ID, -1))
        assertEquals(opId, intent.getStringExtra(SendStatusReceiver.EXTRA_SEND_OPERATION_ID))
        assertEquals(2, intent.getIntExtra(SendStatusReceiver.EXTRA_PART_INDEX, -1))
        assertEquals(3, intent.getIntExtra(SendStatusReceiver.EXTRA_PART_COUNT, -1))
        assertTrue(intent.getBooleanExtra(SendStatusReceiver.EXTRA_IS_LAST_PART, false))
    }
}
