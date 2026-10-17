package org.fossify.messages.helpers

/** Main-thread only. Coalesces UI events while the home activity is not resumed. */
internal class HomeRefreshGate {
    private var resumed = false
    private var pending = false

    fun request(): Boolean {
        if (resumed) return true
        pending = true
        return false
    }

    fun resume(): Boolean {
        resumed = true
        val needsRefresh = pending
        pending = false
        return needsRefresh
    }

    fun pause() {
        resumed = false
    }
}
