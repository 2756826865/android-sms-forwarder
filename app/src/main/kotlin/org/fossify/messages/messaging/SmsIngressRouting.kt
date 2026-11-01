package org.fossify.messages.messaging

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import org.fossify.messages.forwarding.MultiForwardConfig
import org.json.JSONArray
import org.json.JSONObject

/** Compare semantic destinations/rendering; runtime service switches are not routing changes. */
internal class SmsIngressRouting(context: Context) {
    private val app = context.applicationContext
    private val db
        get() = SmsIngressDatabase.get(app).writableDatabase

    /**
     * A damaged forwarding credential must never stop an incoming SMS from reaching
     * the encrypted receipt journal. An unavailable snapshot permanently disables
     * automatic forwarding for this receipt, even if credentials are later replaced.
     */
    fun admissionDigest(): String =
        try {
            digest()
        } catch (error: Exception) {
            Log.w(TAG, "Routing snapshot unavailable; SMS admission remains enabled", error)
            UNAVAILABLE_SNAPSHOT
        }

    fun allowed(key: String): Boolean {
        val original =
            db.rawQuery(
                    "SELECT routing_digest FROM ingress_journal WHERE event_key=?",
                    arrayOf(key),
                )
                .use { if (it.moveToFirst()) it.getString(0) else null }
        if (original == UNAVAILABLE_SNAPSHOT) return block(key, "configuration_unavailable")

        try {
            val current = digest()
            if (original == null || original == current) return true
            if (original.startsWith("v2:") && original == digest(rawCredentials = true)) {
                db.execSQL("UPDATE ingress_journal SET routing_digest=? WHERE event_key=?", arrayOf(current, key))
                return true
            }
            // Upgrade an unchanged v5 digest only when equivalence is provable.
            if (!original.startsWith("v2:") && original == digest(legacy = true)) {
                db.execSQL(
                    "UPDATE ingress_journal SET routing_digest=? WHERE event_key=?",
                    arrayOf(current, key),
                )
                return true
            }
        } catch (error: Exception) {
            Log.w(TAG, "Routing snapshot cannot be verified; forwarding paused", error)
            return block(key, "configuration_unavailable")
        }
        return block(key, "configuration_changed")
    }

    private fun block(key: String, reason: String): Boolean {
        // A failed or ambiguous Provider write outranks routing review. Do not
        // hide the Provider failure behind a secondary configuration warning.
        db.execSQL(
            "UPDATE ingress_journal SET reason=? WHERE event_key=? AND reason NOT IN ('provider_result_uncertain','payload_unreadable')",
            arrayOf(reason, key),
        )
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
        internal const val UNAVAILABLE_SNAPSHOT = "v3:unavailable"
        private const val TAG = "SmsIngressRouting"
        private const val UBYTE_MASK = 0xff
    }
}
