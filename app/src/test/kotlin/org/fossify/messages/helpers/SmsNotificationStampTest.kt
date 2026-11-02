package org.fossify.messages.helpers

import org.junit.Assert.*
import org.junit.Test

class SmsNotificationStampTest {
    @Test
    fun aNewerFilteredRowWithoutAPostedNotificationCannotSuppressAnAlert() {
        val older = SmsNotificationStamp(4, 10, 100)
        assertFalse(older.isSupersededBy(null))
    }

    @Test
    fun anActuallyPostedNewerAlertWinsIndependentOfCallbackOrder() {
        val older = SmsNotificationStamp(4, 10, 100)
        val newer = SmsNotificationStamp(4, 11, 101)
        assertTrue(older.isSupersededBy(newer))
        assertFalse(newer.isSupersededBy(older))
    }

    @Test
    fun equalDatesUseMessageIdButNotUnrelatedThreads() {
        val older = SmsNotificationStamp(4, 10, 100)
        assertTrue(older.isSupersededBy(SmsNotificationStamp(4, 11, 100)))
        assertFalse(older.isSupersededBy(SmsNotificationStamp(5, 11, 101)))
        assertFalse(older.isSupersededBy(older))
    }

    @Test
    fun unavailableDatesDoNotInventAnOrdering() {
        assertFalse(
            SmsNotificationStamp(4, 10, -1).isSupersededBy(SmsNotificationStamp(4, 11, 100))
        )
        assertFalse(
            SmsNotificationStamp(4, 10, 100).isSupersededBy(SmsNotificationStamp(4, 11, -1))
        )
    }
}
