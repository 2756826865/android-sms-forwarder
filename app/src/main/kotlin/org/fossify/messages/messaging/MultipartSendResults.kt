package org.fossify.messages.messaging

import android.app.Activity
import android.content.Context
import android.content.Intent
import org.fossify.messages.receivers.SendStatusReceiver
import org.json.JSONObject

/** Actual sent callbacks, independent of the optional shadow diagnostics switch. */
internal object MultipartSendResults {
    const val EXTRA_PENDING = "multipart_result_pending"
    const val EXTRA_RESULT = "multipart_result_code"

    fun aggregate(partCount: Int, results: Map<Int, Int>): Int? {
        val valid = results.filterKeys { it in 0 until partCount }
        valid.values.firstOrNull { it != Activity.RESULT_OK }?.let { return it }
        return if (partCount > 0 && valid.size == partCount) Activity.RESULT_OK else null
    }

    @Synchronized
    fun record(context: Context, intent: Intent, resultCode: Int): Int? {
        val count = intent.getIntExtra(SendStatusReceiver.EXTRA_PART_COUNT, 1)
        if (count <= 1) return resultCode
        val index = intent.getIntExtra(SendStatusReceiver.EXTRA_PART_INDEX, -1)
        val id = intent.getStringExtra(SendStatusReceiver.EXTRA_SEND_OPERATION_ID).orEmpty()
        if (id.isBlank() || index !in 0 until count) return resultCode // Legacy callbacks.
        val prefs = context.getSharedPreferences("multipart_sent_results", Context.MODE_PRIVATE)
        val key = "result_$id"
        val state = runCatching { JSONObject(prefs.getString(key, "{}").orEmpty()) }.getOrDefault(JSONObject())
        val parts = state.optJSONObject("parts") ?: JSONObject()
        // A duplicate success callback cannot erase a previous failure for that part.
        if (!parts.has(index.toString()) || parts.optInt(index.toString()) == Activity.RESULT_OK) {
            parts.put(index.toString(), resultCode)
        }
        val now = System.currentTimeMillis()
        state.put("parts", parts).put("updatedAt", now)
        val editor = prefs.edit().putString(key, state.toString())
        prefs.all.forEach { (oldKey, value) ->
            if (oldKey != key) {
                val updated = runCatching { JSONObject(value as String).optLong("updatedAt") }.getOrDefault(0L)
                if (now - updated > 7 * 24 * 60 * 60 * 1000L) editor.remove(oldKey)
            }
        }
        check(editor.commit()) { "Unable to persist multipart sent results" }
        return aggregate(count, buildMap {
            for (i in 0 until count) if (parts.has(i.toString())) put(i, parts.getInt(i.toString()))
        })
    }
}
