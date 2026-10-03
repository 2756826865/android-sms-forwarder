package org.fossify.messages.forwarding

import android.content.Context

/** One-hop fallback only for a preflight failure before any request has started. */
class ForwardingFallbackConfig(context: Context) {
    private val prefs = context.getSharedPreferences("forwarding_fallback", Context.MODE_PRIVATE)
    fun target(primary: String): String = prefs.getString(primary, "").orEmpty()
    fun set(primary: String, backup: String) { prefs.edit().putString(primary, backup).apply() }
    fun resolve(primary: String, instances: List<ForwardingChannelInstance>): ForwardingChannelInstance? {
        val id = target(primary)
        return instances.firstOrNull {
            it.id == id && it.id != primary && it.enabled && it.hasDispatchConfiguration() &&
                it.channelType != ForwardingChannels.SMS_DIRECT && it.channelType != ForwardingChannels.CHANNEL_GROUP
        }
    }
}
