package org.fossify.messages.helpers

import org.fossify.messages.models.Message
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ThreadMessageMergeTest {
    private fun message(id: Long, body: String, date: Int = 10, mms: Boolean = false, scheduled: Boolean = false) = Message(
        id = id, body = body, type = 1, status = 0, participants = arrayListOf(), date = date,
        read = true, threadId = 1, isMMS = mms, attachment = null, senderPhoneNumber = "10086",
        senderName = "", senderPhotoUri = "", subscriptionId = 1, isScheduled = scheduled
    )

    @Test fun providerUpdatesReplaceStaleCacheWithoutDroppingLocalFallback() {
        val result = mergeThreadMessageSources(
            provider = listOf(message(1, "fresh")), cached = listOf(message(1, "old"), message(2, "local"))
        )
        assertEquals(2, result.size)
        assertEquals("fresh", result.first { it.id == 1L }.body)
        assertEquals("local", result.first { it.id == 2L }.body)
    }

    @Test fun smsAndMmsCanHaveTheSameProviderId() {
        val result = mergeThreadMessageSources(listOf(message(1, "sms"), message(1, "mms", mms = true)), emptyList())
        assertEquals(2, result.size)
    }

    @Test fun cachedRecordsRespectPagingAndScheduledExclusion() {
        val result = mergeThreadMessageSources(emptyList(), listOf(
            message(1, "older", date = 9), message(2, "boundary", date = 10), message(3, "scheduled", date = 8, scheduled = true)
        ), dateBefore = 10, includeScheduled = false)
        assertEquals(listOf(1L), result.map { it.id })
        assertFalse(result.any { it.isScheduled })
    }
}
