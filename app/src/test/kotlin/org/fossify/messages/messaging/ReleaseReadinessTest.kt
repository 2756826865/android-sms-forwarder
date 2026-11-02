package org.fossify.messages.messaging

import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.fossify.messages.helpers.CoalescingRefresh
import org.junit.Assert.*
import org.junit.Test

class ReleaseReadinessTest {
    @Test
    fun continuousInvalidationsPublishBeforeTheProducerStops() = runBlocking {
        val published = CompletableDeferred<Unit>()
        val refresh = CoalescingRefresh(this, 10) { published.complete(Unit) }
        val producer = launch {
            repeat(100) {
                refresh.request()
                delay(2)
            }
        }
        try {
            withTimeout(2000) { published.await() }
            assertTrue(
                "Refresh waited for the invalidation stream to become idle",
                producer.isActive,
            )
        } finally {
            producer.cancelAndJoin()
            refresh.cancel()
        }
    }

    @Test
    fun updatesDuringAnActiveReadGetOneFollowUpWithoutCancellingTheRead() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val followedUp = CompletableDeferred<Unit>()
        var reads = 0
        val refresh =
            CoalescingRefresh(this, 1) {
                reads++
                if (reads == 1) {
                    started.complete(Unit)
                    release.await()
                } else followedUp.complete(Unit)
            }
        try {
            refresh.request()
            withTimeout(2000) { started.await() }
            repeat(100) { refresh.request() }
            assertEquals(1, reads)
            release.complete(Unit)
            withTimeout(2000) { followedUp.await() }
            assertEquals(2, reads)
        } finally {
            refresh.cancel()
        }
    }

    @Test
    fun cancellationAndResumeDoNotLoseTheNewRunner() = runBlocking {
        var calls = 0
        val started = CompletableDeferred<Unit>()
        val resumed = CompletableDeferred<Unit>()
        val refresh =
            CoalescingRefresh(this, 1) {
                if (++calls == 1) {
                    started.complete(Unit)
                    awaitCancellation()
                } else resumed.complete(Unit)
            }
        try {
            refresh.request()
            withTimeout(2000) { started.await() }
            refresh.cancel()
            refresh.request()
            withTimeout(2000) { resumed.await() }
            assertEquals(2, calls)
        } finally {
            refresh.cancel()
        }
    }

    @Test
    fun queuedStorageCannotHoldTheBroadcastTokenPastItsDeadline() {
        val timer = ScheduledThreadPoolExecutor(1).apply { setRemoveOnCancelPolicy(true) }
        val expired = CountDownLatch(1)
        val released = AtomicInteger()
        try {
            val lease =
                BroadcastLease(timer, 20, { released.incrementAndGet() }, expired::countDown)
            assertTrue(expired.await(2, TimeUnit.SECONDS))
            // A stalled or queued admission eventually commits after framework ownership expires.
            repeat(10) { lease.complete() }
            assertEquals(1, released.get())
        } finally {
            timer.shutdownNow()
        }
    }

    @Test
    fun durableCompletionCancelsTheWatchdogAndFinishesOnce() {
        val timer = ScheduledThreadPoolExecutor(1).apply { setRemoveOnCancelPolicy(true) }
        val released = AtomicInteger()
        try {
            val lease =
                BroadcastLease(
                    timer,
                    60000,
                    { released.incrementAndGet() },
                    { fail("unexpected expiry") },
                )
            repeat(10) { lease.complete() }
            assertEquals(1, released.get())
            assertTrue(timer.queue.isEmpty())
        } finally {
            timer.shutdownNow()
        }
    }
}
