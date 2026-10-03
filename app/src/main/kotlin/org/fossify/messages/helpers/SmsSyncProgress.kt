package org.fossify.messages.helpers

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-process foreground synchronization progress; no message contents. */
object SmsSyncProgress {
    data class State(val running: Boolean = false, val completed: Int = 0, val total: Int = 0, val failed: Int = 0)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    fun tryStart(total: Int = 0): Boolean {
        while (true) {
            val current = mutable.value
            if (current.running) return false
            if (mutable.compareAndSet(current, State(running = true, total = total))) return true
        }
    }
    fun update(state: State) { mutable.value = state }
}
