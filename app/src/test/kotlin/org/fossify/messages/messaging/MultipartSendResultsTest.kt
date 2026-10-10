package org.fossify.messages.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MultipartSendResultsTest {
    @Test fun waitsForAllPartsEvenWhenLastPartArrivesFirst() {
        assertNull(MultipartSendResults.aggregate(3, mapOf(2 to -1)))
        assertNull(MultipartSendResults.aggregate(3, mapOf(2 to -1, 0 to -1)))
        assertEquals(-1, MultipartSendResults.aggregate(3, mapOf(2 to -1, 0 to -1, 1 to -1)))
    }
    @Test fun failureWinsOverLaterSuccessfulParts() {
        assertEquals(4, MultipartSendResults.aggregate(3, mapOf(0 to 4, 1 to -1, 2 to -1)))
    }
    @Test fun invalidPartCannotCompleteMessage() {
        assertNull(MultipartSendResults.aggregate(2, mapOf(0 to -1, 5 to -1)))
    }
}
