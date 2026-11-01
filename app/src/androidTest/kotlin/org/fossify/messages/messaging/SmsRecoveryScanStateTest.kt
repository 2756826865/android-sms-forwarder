package org.fossify.messages.messaging

import android.content.Context
import org.fossify.messages.testing.ForwardingTestContext
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SmsRecoveryScanStateTest {
    private val context
        get() = ForwardingTestContext.context

    @Before
    fun reset() {
        context
            .getSharedPreferences("sms_recovery_scan_v128", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun firstInstallDoesNotReplayRequestedHistory() {
        val state = SmsRecoveryScanState(context)
        state.request(1)
        assertEquals(10000L, state.begin(0, 10000, 1).date)
    }

    @Test
    fun newerDeeperRequestSurvivesCompletionOfRunningPage() {
        val state = SmsRecoveryScanState(context)
        state.request(400000)
        val first = state.begin(500000, 600000, 1)
        state.checkpoint(first, 450000, 12)
        state.request(100000)
        val resumed = SmsRecoveryScanState(context).begin(500000, 700000, 1)
        assertEquals(450000L, resumed.date)
        assertEquals(12L, resumed.id)
        state.complete(resumed)
        assertTrue(state.hasRequest())
        assertEquals(100000L, state.begin(600000, 700000, 1).date)
    }

    @Test
    fun requestAfterRunnerCompletionIsNotLost() {
        val state = SmsRecoveryScanState(context)
        state.complete(state.begin(500000, 600000, 1))
        state.request(100000)
        assertTrue(state.hasRequest())
        assertEquals(100000L, SmsRecoveryScanState(context).begin(600000, 700000, 1).date)
    }
}
