package org.fossify.messages.remote.runtime

import android.content.Context

internal class RemoteNetworkMonitor(context: Context, changed: () -> Unit) {
    fun update(enabled: Boolean) {}
}
