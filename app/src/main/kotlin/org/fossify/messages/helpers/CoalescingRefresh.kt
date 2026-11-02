package org.fossify.messages.helpers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Confined to the owning UI scope. Requests never cancel an active read. */
internal class CoalescingRefresh(
    private val scope: CoroutineScope,
    private val intervalMs: Long = 60L,
    private val refresh: suspend () -> Unit,
) {
    private var dirty = false
    private var runner: Job? = null

    fun request() {
        dirty = true
        if (runner?.isActive == true) return
        runner = scope.launch {
            try {
                do {
                    delay(intervalMs)
                    dirty = false
                    refresh()
                } while (dirty)
            } finally {
                if (runner === currentCoroutineContext()[Job]) runner = null
            }
        }
    }

    fun cancel() {
        dirty = false
        runner?.cancel()
    }
}
