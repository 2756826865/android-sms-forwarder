package org.fossify.messages.messaging

import android.content.Context
import java.security.MessageDigest

/** Reject changed routing rather than re-route an interrupted event through today's settings. */
internal class SmsIngressRouting(context: Context) {
    private val app = context.applicationContext
    private val db
        get() = SmsIngressDatabase.get(app).writableDatabase

    fun allowed(key: String): Boolean {
        val original =
            db.rawQuery(
                    "SELECT routing_digest FROM ingress_journal WHERE event_key=?",
                    arrayOf(key),
                )
                .use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
        if (original == null || original == digest()) return true
        SmsIngressJournal(app).reason(key, "configuration_changed")
        return false
    }

    fun digest(): String {
        val text = buildString {
            for (name in
                listOf("forwarding_rules", "multi_channel_forwarding", "pushplus_forwarding")) {
                app.getSharedPreferences(name, Context.MODE_PRIVATE).all.toSortedMap().forEach {
                    (key, value) ->
                    if (
                        !key.contains("log", true) &&
                            !key.contains("status", true) &&
                            !key.startsWith("last", true)
                    ) {
                        val normalized =
                            if (value is Set<*>)
                                value.map { it.toString() }.sorted().joinToString("\u0000")
                            else value.toString()
                        append(name.length)
                            .append(':')
                            .append(name)
                            .append(key.length)
                            .append(':')
                            .append(key)
                        append(normalized.length).append(':').append(normalized)
                    }
                }
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it.toInt() and UBYTE_MASK)
        }
    }

    companion object {
        private const val UBYTE_MASK = 0xff
    }
}
