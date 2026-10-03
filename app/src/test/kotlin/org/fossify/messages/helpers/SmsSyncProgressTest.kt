package org.fossify.messages.helpers

import org.junit.Assert.*
import org.junit.Test

class SmsSyncProgressTest {
    @Test fun onlyOneForegroundSyncCanOwnProgress() {
        SmsSyncProgress.update(SmsSyncProgress.State())
        assertTrue(SmsSyncProgress.tryStart(10))
        assertFalse(SmsSyncProgress.tryStart(20))
        assertEquals(10, SmsSyncProgress.state.value.total)
        SmsSyncProgress.update(SmsSyncProgress.State(false, 10, 10, 0))
        assertTrue(SmsSyncProgress.tryStart(20))
        SmsSyncProgress.update(SmsSyncProgress.State())
    }
}
