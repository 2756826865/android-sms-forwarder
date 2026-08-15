package org.fossify.messages.remote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class DingTalkCommandDeduplicator(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun claim(messageId: String, now: Long = System.currentTimeMillis()): Boolean {
        val normalizedId = messageId.trim()
        if (normalizedId.isEmpty()) return true

        synchronized(lock) {
            val recent = decode(prefs.getString(KEY_RECENT_MESSAGES, "[]").orEmpty())
                .filter { now - it.receivedAt in 0L..RETENTION_MS }
            if (recent.any { it.messageId == normalizedId }) return false

            val updated = (recent + Entry(normalizedId, now)).takeLast(MAX_RECENT_MESSAGES)
            val encoded = JSONArray().apply {
                updated.forEach { entry ->
                    put(
                        JSONObject()
                            .put("messageId", entry.messageId)
                            .put("receivedAt", entry.receivedAt),
                    )
                }
            }.toString()
            check(prefs.edit().putString(KEY_RECENT_MESSAGES, encoded).commit()) {
                "Unable to persist DingTalk message deduplication state"
            }
            return true
        }
    }

    private fun decode(value: String): List<Entry> = runCatching {
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val messageId = item.optString("messageId").trim()
                val receivedAt = item.optLong("receivedAt")
                if (messageId.isNotEmpty() && receivedAt > 0L) add(Entry(messageId, receivedAt))
            }
        }
    }.getOrDefault(emptyList())

    private data class Entry(val messageId: String, val receivedAt: Long)

    companion object {
        private const val PREFS_NAME = "dingtalk_command_dedup"
        private const val KEY_RECENT_MESSAGES = "recent_messages"
        private const val MAX_RECENT_MESSAGES = 200
        private const val RETENTION_MS = 7 * 24 * 60 * 60 * 1000L
        private val lock = Any()
    }
}
