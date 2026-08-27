package org.fossify.messages.messaging

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.helpers.SmsSendRepository
import org.fossify.messages.models.SmsSendDeliveryState
import org.fossify.messages.models.SmsSendPartEntity
import org.fossify.messages.models.SmsSendState
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SmsSendAggregationTest {

    @Test
    fun testSinglePart_sent_aggregatesToSendStateSent() {
        val parts = listOf(
            SmsSendPartEntity(
                sendOperationId = "op-single",
                partIndex = 0,
                partCount = 1,
                sentState = SmsSendState.SENT.name,
                sentResultCode = -1
            )
        )

        val sendState = SmsSendRepository.aggregateSendState(parts, SmsSendState.SUBMITTED)
        assertEquals(SmsSendState.SENT, sendState)
    }

    @Test
    fun testThreeParts_allSuccess_aggregatesToSendStateSent() {
        val parts = listOf(
            SmsSendPartEntity(sendOperationId = "op-3", partIndex = 0, partCount = 3, sentState = SmsSendState.SENT.name),
            SmsSendPartEntity(sendOperationId = "op-3", partIndex = 1, partCount = 3, sentState = SmsSendState.SENT.name),
            SmsSendPartEntity(sendOperationId = "op-3", partIndex = 2, partCount = 3, sentState = SmsSendState.SENT.name)
        )

        val sendState = SmsSendRepository.aggregateSendState(parts, SmsSendState.SUBMITTED)
        assertEquals(SmsSendState.SENT, sendState)
    }

    @Test
    fun testThreeParts_partialFailure_aggregatesToSendStateFailed() {
        val parts = listOf(
            SmsSendPartEntity(sendOperationId = "op-fail", partIndex = 0, partCount = 3, sentState = SmsSendState.SENT.name),
            SmsSendPartEntity(sendOperationId = "op-fail", partIndex = 1, partCount = 3, sentState = SmsSendState.FAILED.name),
            SmsSendPartEntity(sendOperationId = "op-fail", partIndex = 2, partCount = 3, sentState = SmsSendState.SENT.name)
        )

        val sendState = SmsSendRepository.aggregateSendState(parts, SmsSendState.SUBMITTED)
        assertEquals(SmsSendState.FAILED, sendState)
    }

    @Test
    fun testOutOfOrder_parts_consistentResult() {
        // Part 2 arrived first, Part 0 arrived second, Part 1 in-flight
        val inFlightParts = listOf(
            SmsSendPartEntity(sendOperationId = "op-order", partIndex = 0, partCount = 3, sentState = SmsSendState.SENT.name),
            SmsSendPartEntity(sendOperationId = "op-order", partIndex = 1, partCount = 3, sentState = null),
            SmsSendPartEntity(sendOperationId = "op-order", partIndex = 2, partCount = 3, sentState = SmsSendState.SENT.name)
        )
        val inFlightResult = SmsSendRepository.aggregateSendState(inFlightParts, SmsSendState.SUBMITTED)
        assertEquals(SmsSendState.SUBMITTED, inFlightResult)

        // Now Part 1 arrives as SENT
        val completedParts = listOf(
            inFlightParts[0],
            inFlightParts[1].copy(sentState = SmsSendState.SENT.name),
            inFlightParts[2]
        )
        val completedResult = SmsSendRepository.aggregateSendState(completedParts, inFlightResult)
        assertEquals(SmsSendState.SENT, completedResult)
    }

    @Test
    fun testMultipart_delivered_onlyLastPartHasCallback_aggregatesToDelivered() {
        // Android Multipart reality: Part 0 & 1 have no deliveryIntent (null/NOT_REQUESTED), only Part 2 receives DELIVERED
        val parts = listOf(
            SmsSendPartEntity(
                sendOperationId = "op-multi-deliv",
                partIndex = 0,
                partCount = 3,
                sentState = SmsSendState.SENT.name,
                deliveredState = null
            ),
            SmsSendPartEntity(
                sendOperationId = "op-multi-deliv",
                partIndex = 1,
                partCount = 3,
                sentState = SmsSendState.SENT.name,
                deliveredState = null
            ),
            SmsSendPartEntity(
                sendOperationId = "op-multi-deliv",
                partIndex = 2,
                partCount = 3,
                sentState = SmsSendState.SENT.name,
                deliveredState = SmsSendDeliveryState.DELIVERED.name
            )
        )

        val sendState = SmsSendRepository.aggregateSendState(parts, SmsSendState.SENT)
        val deliveryState = SmsSendRepository.aggregateDeliveryState(parts, requireDeliveryReport = true)

        assertEquals(SmsSendState.SENT, sendState)
        assertEquals(SmsSendDeliveryState.DELIVERED, deliveryState)
    }

    @Test
    fun testDeliveryFailed_keepsSendStateSent() {
        val parts = listOf(
            SmsSendPartEntity(
                sendOperationId = "op-deliv-fail",
                partIndex = 0,
                partCount = 1,
                sentState = SmsSendState.SENT.name,
                deliveredState = SmsSendDeliveryState.DELIVERY_FAILED.name
            )
        )

        val sendState = SmsSendRepository.aggregateSendState(parts, SmsSendState.SENT)
        val deliveryState = SmsSendRepository.aggregateDeliveryState(parts, requireDeliveryReport = true)

        // Send state must remain SENT, never degraded to send failure
        assertEquals(SmsSendState.SENT, sendState)
        assertEquals(SmsSendDeliveryState.DELIVERY_FAILED, deliveryState)
    }

    @Test
    fun testIdempotency_lateSuccessAfterFailed_cannotRegress() {
        val parts = listOf(
            SmsSendPartEntity(sendOperationId = "op-regress", partIndex = 0, partCount = 2, sentState = SmsSendState.FAILED.name),
            SmsSendPartEntity(sendOperationId = "op-regress", partIndex = 1, partCount = 2, sentState = SmsSendState.SENT.name)
        )

        // When current operation state is already FAILED, late SENT cannot flip it back
        val result = SmsSendRepository.aggregateSendState(parts, SmsSendState.FAILED)
        assertEquals(SmsSendState.FAILED, result)
    }
}
