package org.fossify.messages.helpers

/** Small orchestration boundary: failed apply/commit must retain or restore the old snapshot. */
internal object RestoreTransaction {
    fun run(apply: () -> Boolean, commit: () -> Unit, rollback: () -> Boolean): Boolean {
        return try {
            if (apply()) { commit(); true }
            else { rollback(); false }
        } catch (_: Exception) {
            runCatching { rollback() }
            false
        }
    }
}
