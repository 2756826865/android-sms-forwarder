package org.fossify.messages.messaging

import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persist the small raw envelope before admission; slow/queued processing never owns a broadcast
 * token.
 */
internal object DurableIngressHandoff {
    /** Commit the encrypted spool, or commit the Journal BEFORE acknowledging intake. */
    fun persistWithFallback(primary: () -> Unit, fallback: () -> Unit): Boolean =
        try {
            primary()
            true
        } catch (_: Exception) {
            fallback()
            false
        }

    fun dispatch(
        executor: Executor,
        process: () -> Unit,
        persist: () -> Unit,
        finish: () -> Unit,
    ): Boolean {
        try {
            persist()
        } finally {
            finish()
        }
        val entered = AtomicBoolean(false)
        try {
            executor.execute {
                entered.set(true)
                process()
            }
            return true
        } catch (error: RejectedExecutionException) {
            if (entered.get()) throw error
            return false // The raw envelope is durable and will be drained by recovery.
        }
    }
}
