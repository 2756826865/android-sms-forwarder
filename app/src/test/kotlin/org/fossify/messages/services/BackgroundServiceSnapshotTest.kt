package org.fossify.messages.services

import org.junit.Assert.*
import org.junit.Test

class BackgroundServiceSnapshotTest {
    @Test
    fun smsAndRemoteDemandAreIndependent() {
        assertFalse(BackgroundServiceSnapshot(false, 0, "").required)
        assertTrue(BackgroundServiceSnapshot(true, 0, "").required)
        assertTrue(BackgroundServiceSnapshot(false, 1, "").required)
        assertTrue(BackgroundServiceSnapshot(true, 6, "").required)
    }
}
