package org.fossify.messages.messaging

/** A replay already owns durable data and is not constrained by a broadcast's short budget. */
internal object RecoveryExecutionPolicy {
    fun mayContinue(replay: Boolean, elapsedMs: Long, broadcastBudgetMs: Long): Boolean =
        replay || elapsedMs < broadcastBudgetMs
}
