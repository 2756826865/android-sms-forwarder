package org.fossify.messages.remote.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** One default-network callback; coalesces handovers and releases registrations when unused. */
internal class RemoteNetworkMonitor(context: Context, private val changed: () -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val scheduler = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "remote-network-change").apply { isDaemon = true }
    }
    private var registered = false
    private var generation = 0L
    private var scheduled: ScheduledFuture<*>? = null
    private var lastSignature = ""
    private val callback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = signal()

            override fun onLost(network: Network) = signal()

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                signal()
        }

    // Framework/OEM boundary: retain durable state and report unclassified platform failures.
    @Suppress("TooGenericExceptionCaught")
    @Synchronized
    fun update(enabled: Boolean) {
        if (enabled == registered) return
        if (enabled) {
            try {
                connectivity.registerDefaultNetworkCallback(callback)
                registered = true
            } catch (error: Exception) {
                Log.w("RemoteNetwork", "registration failed: ${error.javaClass.simpleName}")
            }
        } else {
            generation++
            scheduled?.cancel(false)
            scheduled = null
            try {
                connectivity.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {}
            registered = false
            lastSignature = ""
        }
    }

    @Synchronized
    private fun signal() {
        if (!registered) return
        val network = connectivity.activeNetwork
        val caps = network?.let(connectivity::getNetworkCapabilities)
        val signature =
            "$network:" +
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) +
                ":" +
                caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        if (signature == lastSignature) return
        lastSignature = signature
        val token = ++generation
        scheduled?.cancel(false)
        scheduled =
            scheduler.schedule(
                {
                    val current = synchronized(this) { registered && generation == token }
                    if (current) changed()
                },
                NETWORK_DEBOUNCE_MS,
                TimeUnit.MILLISECONDS,
            )
    }

    companion object {
        private const val NETWORK_DEBOUNCE_MS = 500L
    }
}
