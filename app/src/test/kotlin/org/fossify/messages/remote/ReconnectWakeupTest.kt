package org.fossify.messages.remote

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectWakeupTest {
    @Test
    fun signalBeforeWaitingIsNotLost() {
        val wakeup = ReconnectWakeup()
        wakeup.signal()
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertTrue(
                executor
                    .submit<Boolean> {
                        wakeup.await(30000)
                        true
                    }
                    .get(2, TimeUnit.SECONDS)
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun networkChangeWakesOneExistingLoop() {
        val wakeup = ReconnectWakeup()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val waiting =
                executor.submit<Boolean> {
                    wakeup.await(30000)
                    true
                }
            try {
                waiting.get(30, TimeUnit.MILLISECONDS)
                error("backoff unexpectedly finished")
            } catch (_: TimeoutException) {}
            wakeup.signal()
            assertTrue(waiting.get(2, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
        }
    }
}
