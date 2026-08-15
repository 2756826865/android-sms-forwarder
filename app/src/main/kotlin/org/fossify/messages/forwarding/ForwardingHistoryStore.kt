package org.fossify.messages.forwarding

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import org.json.JSONArray
import org.json.JSONObject

data class ForwardingHistoryRecord(
    val recordId: String,
    val workId: String,
    val channel: String,
    val sender: String,
    val body: String,
    val receivedAt: Long,
    val subscriptionId: Int,
    val status: String,
    val attempts: Int,
    val detail: String,
    val updatedAt: Long,
    val isTest: Boolean,
)

class ForwardingHistoryStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun records(): List<ForwardingHistoryRecord> = synchronized(lock) {
        decode(prefs.getString(KEY_RECORDS, "[]").orEmpty())
            .sortedByDescending { it.updatedAt }
    }

    fun registerQueued(
        workId: String,
        channel: String,
        sender: String,
        body: String,
        receivedAt: Long,
        subscriptionId: Int,
        isTest: Boolean,
    ): String = synchronized(lock) {
        val recordId = recordId(workId, channel)
        val current = decode(prefs.getString(KEY_RECORDS, "[]").orEmpty()).toMutableList()
        val existing = current.firstOrNull { it.recordId == recordId }
        current.removeAll { it.recordId == recordId }
        val waitingForNetwork = channel in ForwardingChannels.networkChannels && !isNetworkAvailable()
        current += ForwardingHistoryRecord(
            recordId = recordId,
            workId = workId,
            channel = channel,
            sender = sender.take(MAX_SENDER_LENGTH),
            body = body.take(MAX_BODY_LENGTH),
            receivedAt = receivedAt,
            subscriptionId = subscriptionId,
            status = if (waitingForNetwork) STATUS_WAITING_NETWORK else STATUS_QUEUED,
            attempts = existing?.attempts ?: 0,
            detail = if (waitingForNetwork) "等待网络连接" else "已加入发送队列",
            updatedAt = System.currentTimeMillis(),
            isTest = isTest,
        )
        persist(current)
        recordId
    }

    fun registerSkipped(
        workId: String,
        channel: String,
        sender: String,
        body: String,
        receivedAt: Long,
        subscriptionId: Int,
        detail: String,
    ) {
        val recordId = registerQueued(
            workId = workId,
            channel = channel,
            sender = sender,
            body = body,
            receivedAt = receivedAt,
            subscriptionId = subscriptionId,
            isTest = false,
        )
        markSkipped(recordId, detail)
    }

    fun markRunning(recordId: String) = update(recordId) {
        it.copy(
            status = STATUS_RUNNING,
            attempts = it.attempts + 1,
            detail = "正在发送",
            updatedAt = System.currentTimeMillis(),
        )
    }

    fun markSuccess(recordId: String, detail: String) = update(recordId) {
        it.copy(status = STATUS_SUCCESS, detail = detail, updatedAt = System.currentTimeMillis())
    }

    fun markRetry(recordId: String, detail: String) = update(recordId) {
        it.copy(status = STATUS_RETRY, detail = detail, updatedAt = System.currentTimeMillis())
    }

    fun markFailed(recordId: String, detail: String) = update(recordId) {
        it.copy(status = STATUS_FAILED, detail = detail, updatedAt = System.currentTimeMillis())
    }

    fun markSkipped(recordId: String, detail: String) = update(recordId) {
        it.copy(status = STATUS_SKIPPED, detail = detail, updatedAt = System.currentTimeMillis())
    }

    fun clear() = synchronized(lock) {
        prefs.edit().remove(KEY_RECORDS).commit()
    }

    private fun update(recordId: String, transform: (ForwardingHistoryRecord) -> ForwardingHistoryRecord) =
        synchronized(lock) {
            val current = decode(prefs.getString(KEY_RECORDS, "[]").orEmpty()).toMutableList()
            val index = current.indexOfFirst { it.recordId == recordId }
            if (index < 0) return@synchronized
            current[index] = transform(current[index])
            persist(current)
        }

    private fun persist(records: List<ForwardingHistoryRecord>) {
        val kept = records.sortedByDescending { it.updatedAt }.take(MAX_RECORDS)
        val encoded = JSONArray().apply { kept.forEach { put(encode(it)) } }.toString()
        prefs.edit().putString(KEY_RECORDS, encoded).commit()
    }

    private fun encode(record: ForwardingHistoryRecord) = JSONObject()
        .put("recordId", record.recordId)
        .put("workId", record.workId)
        .put("channel", record.channel)
        .put("sender", record.sender)
        .put("body", record.body)
        .put("receivedAt", record.receivedAt)
        .put("subscriptionId", record.subscriptionId)
        .put("status", record.status)
        .put("attempts", record.attempts)
        .put("detail", record.detail)
        .put("updatedAt", record.updatedAt)
        .put("isTest", record.isTest)

    private fun decode(raw: String): List<ForwardingHistoryRecord> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val recordId = item.optString("recordId")
                val channel = item.optString("channel")
                if (recordId.isBlank() || channel.isBlank()) continue
                add(
                    ForwardingHistoryRecord(
                        recordId = recordId,
                        workId = item.optString("workId"),
                        channel = channel,
                        sender = item.optString("sender"),
                        body = item.optString("body"),
                        receivedAt = item.optLong("receivedAt"),
                        subscriptionId = item.optInt("subscriptionId", -1),
                        status = item.optString("status", STATUS_FAILED),
                        attempts = item.optInt("attempts"),
                        detail = item.optString("detail"),
                        updatedAt = item.optLong("updatedAt"),
                        isTest = item.optBoolean("isTest"),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun isNetworkAvailable(): Boolean {
        val manager = appContext.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_WAITING_NETWORK = "waiting_network"
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_RETRY = "retry"
        const val STATUS_FAILED = "failed"
        const val STATUS_SKIPPED = "skipped"

        private const val PREFS_NAME = "forwarding_history"
        private const val KEY_RECORDS = "records"
        private const val MAX_RECORDS = 200
        private const val MAX_SENDER_LENGTH = 160
        private const val MAX_BODY_LENGTH = 2_000
        private val lock = Any()

        fun recordId(workId: String, channel: String) = "$workId::$channel"
    }
}
