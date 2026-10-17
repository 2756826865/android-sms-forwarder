package org.fossify.messages.helpers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeRefreshGateTest {
    @Test fun backgroundEventsAreMergedIntoOneResumeRefresh() {
        val gate = HomeRefreshGate()
        repeat(100) { assertFalse(gate.request()) }
        assertTrue(gate.resume())
        assertFalse(gate.resume())
    }

    @Test fun foregroundEventsRunImmediately() {
        val gate = HomeRefreshGate()
        assertFalse(gate.resume())
        assertTrue(gate.request())
        assertFalse(gate.resume())
    }

    @Test fun pauseDefersUntilNextResumeWithoutLosingChange() {
        val gate = HomeRefreshGate()
        gate.resume()
        gate.pause()
        assertFalse(gate.request())
        assertTrue(gate.resume())
        assertTrue(gate.request())
    }

    @Test fun unchangedBackgroundDoesNotTriggerExtraSync() {
        val gate = HomeRefreshGate()
        gate.resume()
        gate.pause()
        assertFalse(gate.resume())
    }
}
