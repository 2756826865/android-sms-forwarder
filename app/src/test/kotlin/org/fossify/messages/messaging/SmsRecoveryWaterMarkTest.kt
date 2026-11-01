package org.fossify.messages.messaging

import org.junit.Assert.assertEquals
import org.junit.Test

class SmsRecoveryWaterMarkTest {
    @Test
    fun firstInstallIgnoresDeepHistory() {
        val floor = SmsRecoveryPlan.floor(0, 100, 10000)
        assertEquals(10000L, SmsRecoveryPlan.since(0, 100, floor, 10000),
        )
    }

    @Test
    fun longOfflineIntervalIsNotSilentlyTruncatedToSixHours() {
        val now = 100_000_000L
        val last = now - 10 * 60 * 60 * 1000
        assertEquals(last - 60_000, SmsRecoveryPlan.since(last, Long.MAX_VALUE, 1, now))
    }

    @Test
    fun olderRequestExtendsScanWithoutRewindingCompletedWatermark() {
        assertEquals(100000L, SmsRecoveryPlan.since(500000, 100000, 1, 600000))
    }

    @Test
    fun scansNeverCrossInstallationBoundary() {
        assertEquals(200000L, SmsRecoveryPlan.since(500000, 1, 200000, 600000))
    }

    @Test
    fun normalOverlapIsRetained() {
        assertEquals(440000L, SmsRecoveryPlan.since(500000, Long.MAX_VALUE, 1, 600000))
    }
}
