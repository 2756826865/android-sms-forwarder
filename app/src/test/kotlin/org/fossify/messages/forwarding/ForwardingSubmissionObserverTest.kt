package org.fossify.messages.forwarding

import com.google.common.util.concurrent.ListenableFuture
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask

class ForwardingSubmissionObserverTest {
    private class Pending : FutureTask<Unit>({ Unit }), ListenableFuture<Unit> {
        private val callbacks = mutableListOf<Pair<Runnable, Executor>>()
        override fun addListener(listener: Runnable, executor: Executor) = synchronized(callbacks) {
            if (isDone) executor.execute(listener) else { callbacks.add(listener to executor); Unit }
        }
        override fun done() = synchronized(callbacks) {
            callbacks.forEach { (listener, executor) -> executor.execute(listener) }
            callbacks.clear()
        }
        fun succeed() { set(Unit) }
        fun fail() { setException(IllegalStateException("enqueue failed")) }
    }
    private val direct = Executor { it.run() }

    @Test fun delayedSubmissionDoesNotPretendToBeAccepted() {
        val pending = Pending()
        var accepted = 0
        var rejected = 0
        ForwardingSubmissionObserver.observe(pending, direct, { accepted++ }, { rejected++ }, { fail("log") })
        assertEquals(0, accepted)
        assertEquals(0, rejected)
        pending.succeed()
        assertEquals(1, accepted)
        assertEquals(0, rejected)
    }
    @Test fun asynchronousFailureIsObserved() {
        val pending = Pending()
        var rejected = 0
        ForwardingSubmissionObserver.observe(pending, direct, { fail("accepted") }, { rejected++ }, { fail("log") })
        pending.fail()
        assertEquals(1, rejected)
    }
    @Test fun cancelledSubmissionIsNotAccepted() {
        val pending = Pending()
        var rejected = 0
        ForwardingSubmissionObserver.observe(pending, direct, { fail("accepted") }, { rejected++ }, { fail("log") })
        pending.cancel(false)
        assertEquals(1, rejected)
    }
    @Test fun logFailureAfterAcceptanceIsNotSubmissionFailure() {
        val pending = Pending()
        var loggingErrors = 0
        ForwardingSubmissionObserver.observe(pending, direct, { error("database unavailable") },
            { fail("must not record enqueue failure") }, { loggingErrors++ })
        pending.succeed()
        assertEquals(1, loggingErrors)
    }
    @Test fun alreadyCompletedOperationStillNotifies() {
        val pending = Pending().apply { succeed() }
        var accepted = 0
        ForwardingSubmissionObserver.observe(pending, direct, { accepted++ }, { fail("rejected") }, { fail("log") })
        assertEquals(1, accepted)
    }
    @Test fun lateCompletionCannotOverwriteSendingSuccess() {
        val pending = Pending()
        var status = "queued"
        var attempts = 0
        ForwardingSubmissionObserver.observe(pending, direct, {
            if (ForwardingQueuePolicy.canUpdateSubmission(status, attempts)) status = "queued"
        }, { fail("rejected") }, { fail("log") })
        status = "success"
        attempts = 1
        pending.succeed()
        assertEquals("success", status)
    }
}
