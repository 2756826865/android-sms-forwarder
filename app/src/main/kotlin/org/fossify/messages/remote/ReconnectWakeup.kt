package org.fossify.messages.remote

/** One coalesced network signal interrupts a backoff without creating another connection loop. */
internal class ReconnectWakeup {
    private val monitor = Object()
    private var signalled = false

    fun signal() =
        synchronized(monitor) {
            signalled = true
            monitor.notifyAll()
        }

    @Throws(InterruptedException::class)
    fun await(delayMs: Long) =
        synchronized(monitor) {
            if (!signalled) monitor.wait(delayMs.coerceAtLeast(1L))
            signalled = false
        }
}
