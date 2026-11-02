package org.fossify.messages.messaging

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A framework token is not a business success ACK. Storage may complete after expiry. */
internal class BroadcastLease(
    scheduler: ScheduledExecutorService,
    timeoutMs: Long,
    private val finish: () -> Unit,
    onExpired: () -> Unit,
) {
    private val released = AtomicBoolean(false)
    private val deadline =
        scheduler.schedule(
            {
                if (released.compareAndSet(false, true)) {
                    try {
                        finish()
                    } finally {
                        onExpired()
                    }
                }
            },
            timeoutMs,
            TimeUnit.MILLISECONDS,
        )

    fun complete() {
        if (released.compareAndSet(false, true)) {
            try {
                finish()
            } finally {
                deadline.cancel(false)
            }
        }
    }
}
