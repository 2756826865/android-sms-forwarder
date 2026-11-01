package org.fossify.messages.messaging

import android.content.Context
import java.security.MessageDigest
import org.fossify.messages.forwarding.MultiForwardConfig
import org.json.JSONArray
import org.json.JSONObject

/** Compare semantic destinations/rendering; runtime service switches are not routing changes. */
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
                .use { if (it.moveToFirst()) it.getString(0) else null }
        val current = digest()
        if (original == null || original == current) return true
        if (original.startsWith("v2:") && original == digest(rawCredentials = true)) {
            db.execSQL("UPDATE ingress_journal SET routing_digest=? WHERE event_key=?", arrayOf(current, key))
            return true
        }
        // Upgrade an unchanged v5 digest. Never silently accept an unprovable historical change.
        if (!original.startsWith("v2:") && original == digest(legacy = true)) {
            db.execSQL(
                "UPDATE ingress_journal SET routing_digest=? WHERE event_key=?",
                arrayOf(current, key),
            )
            return true
        }
        SmsIngressJournal(app).reason(key, "configuration_changed")
        return false
    }

    fun digest(legacy: Boolean = false, rawCredentials: Boolean = false): String {
        val text = buildString {
            for (name in
                listOf("forwarding_rules", "multi_channel_forwarding", "pushplus_forwarding")) {
                app.getSharedPreferences(name, Context.MODE_PRIVATE).all.toSortedMap().forEach {
                    (key, value) ->
                    val included =
                        if (legacy)
                            !key.contains("log", true) &&
                                !key.contains("status", true) &&
                                !key.startsWith("last", true)
                        else RoutingDigestPolicy.includes(name, key)
                    if (included) {
                        val normalized = normalize(name, key, value, legacy, rawCredentials)
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
        val hash =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") {
                "%02x".format(it.toInt() and UBYTE_MASK)
            }
        return if (legacy) hash else if (rawCredentials) "v2:$hash" else "v3:$hash"
    }

    private fun normalize(name: String, key: String, value: Any?, legacy: Boolean, rawCredentials: Boolean): String =
        when {
            value is Set<*> -> value.map { it.toString() }.sorted().joinToString("\u0000")
            !legacy && key == "channel_instances" -> canonicalChannels()
            !legacy && !rawCredentials && isPushPlusToken(name, key) ->
                org.fossify.messages.forwarding.PushPlusConfig(app)
                    .readLegacyTokenForRouting(value.toString())
            !legacy && !rawCredentials && value is String &&
                org.fossify.messages.forwarding.AndroidKeystoreCipher.looksLikeCiphertext(value) ->
                org.fossify.messages.forwarding.AndroidKeystoreCipher.decrypt(value).also {
                    check(it.isNotEmpty()) { "routing_credential_unavailable" }
                }
            else -> value.toString()
        }

    private fun isPushPlusToken(name: String, key: String): Boolean =
        name == "pushplus_forwarding" && key == "token"

    private fun canonicalChannels(): String =
        MultiForwardConfig(app)
            .channelInstances()
            .sortedBy { it.id }
            .joinToString("\u0000") { item ->
                listOf(
                        item.id,
                        item.channelType,
                        item.enabled.toString(),
                        canonicalJson(JSONObject(item.configJson)),
                    )
                    .joinToString("\u0001")
            }

    private fun canonicalJson(value: Any?): String =
        when (value) {
            is JSONObject ->
                value.keys().asSequence().sorted().joinToString(prefix = "{", postfix = "}") {
                    JSONObject.quote(it) + ":" + canonicalJson(value.get(it))
                }
            is JSONArray ->
                (0 until value.length()).joinToString(prefix = "[", postfix = "]") {
                    canonicalJson(value.get(it))
                }
            is String -> JSONObject.quote(value)
            else -> value.toString()
        }

    companion object {
        private const val UBYTE_MASK = 0xff
    }
}
