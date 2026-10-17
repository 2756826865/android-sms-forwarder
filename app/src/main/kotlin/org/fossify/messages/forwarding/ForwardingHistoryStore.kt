package org.fossify.messages.forwarding

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.fossify.messages.models.Events
import org.greenrobot.eventbus.EventBus

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

    private val database: SQLiteDatabase
        get() = ForwardingHistoryDatabase.get(appContext).writableDatabase.also(::migrateLegacy)

    /** Stable tie order prevents duplicates at page boundaries. Compatibility callers get bounded history. */
    fun records(limit: Int = MAX_RECORDS, offset: Int = 0): List<ForwardingHistoryRecord> = synchronized(lock) {
        require(limit in 1..MAX_RECORDS && offset >= 0)
        database.rawQuery(
            "SELECT payload FROM history ORDER BY updated_at DESC, record_id DESC LIMIT ? OFFSET ?",
            arrayOf(limit.toString(), offset.toString())
        ).use { cursor -> buildList {
            while (cursor.moveToNext()) addAll(decode("[${cursor.getString(0)}]"))
        } }
    }

    fun record(id: String): ForwardingHistoryRecord? = synchronized(lock) {
        readRecord(database, id)
    }

    // Execution guards are deliberately separate from user-clearable display history.
    fun groupStatus(recordId: String): String? = synchronized(lock) {
        database.rawQuery("SELECT status FROM group_execution WHERE record_id = ?", arrayOf(recordId)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }

    fun setGroupStatus(recordId: String, status: String) = synchronized(lock) {
        database.insertWithOnConflict("group_execution", null, ContentValues().apply {
            put("record_id", recordId); put("status", status)
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun workIds(): Set<String> = synchronized(lock) {
        database.rawQuery("SELECT work_id FROM history", null).use { cursor -> buildSet {
            while (cursor.moveToNext()) add(cursor.getString(0))
        } }
    }

    fun count(): Int = synchronized(lock) {
        database.rawQuery("SELECT COUNT(*) FROM history", null).use { it.moveToFirst(); it.getInt(0) }
    }

    fun countsBetween(start: Long, end: Long): Map<String, Int> = synchronized(lock) {
        database.rawQuery(
            "SELECT status, COUNT(*) FROM history WHERE is_test = 0 AND received_at >= ? AND received_at < ? GROUP BY status",
            arrayOf(start.toString(), end.toString())
        ).use { cursor -> buildMap {
            while (cursor.moveToNext()) put(cursor.getString(0), cursor.getInt(1))
        } }
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
        val db = database
        val existing = readRecord(db, recordId)
        val record = ForwardingHistoryRecord(
            recordId = recordId,
            workId = workId,
            channel = channel,
            sender = sender.take(MAX_SENDER_LENGTH),
            body = body.take(MAX_BODY_LENGTH),
            receivedAt = receivedAt,
            subscriptionId = subscriptionId,
            // The history is a record of the enqueue lifecycle. WorkManager owns
            // network constraints; do not expose a transient network snapshot as
            // a long-lived "waiting" state before the worker starts.
            status = STATUS_QUEUED,
            attempts = existing?.attempts ?: 0,
            detail = "已加入发送队列",
            updatedAt = System.currentTimeMillis(),
            isTest = isTest,
        )
        db.beginTransaction()
        try {
            writeRecord(db, record)
            // Retention applies to completed logs only; never evict an in-flight task.
            db.execSQL("DELETE FROM history WHERE status IN ('success','failed','skipped','unknown') AND record_id NOT IN (SELECT record_id FROM history ORDER BY updated_at DESC, record_id DESC LIMIT $MAX_RECORDS)")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        notifyChanged()
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

    fun markUnknown(recordId: String, detail: String) = update(recordId) {
        it.copy(status = STATUS_UNKNOWN, detail = detail, updatedAt = System.currentTimeMillis())
    }

    fun markFailed(recordId: String, detail: String) = update(recordId) {
        it.copy(status = STATUS_FAILED, detail = detail, updatedAt = System.currentTimeMillis())
    }

    fun markSkipped(recordId: String, detail: String) = update(recordId) {
        it.copy(status = STATUS_SKIPPED, detail = detail, updatedAt = System.currentTimeMillis())
    }

    fun clear() = synchronized(lock) {
        database.delete("history", null, null)
        // Keep the migration marker so clearing logs cannot restore the old JSON snapshot.
        notifyChanged()
    }

    private fun update(recordId: String, transform: (ForwardingHistoryRecord) -> ForwardingHistoryRecord) =
        synchronized(lock) {
            val db = database
            db.beginTransaction()
            try {
                val current = readRecord(db, recordId) ?: return@synchronized
                writeRecord(db, transform(current))
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
            notifyChanged()
        }

    private fun readRecord(db: SQLiteDatabase, id: String): ForwardingHistoryRecord? =
        db.rawQuery("SELECT payload FROM history WHERE record_id = ?", arrayOf(id)).use {
            if (it.moveToFirst()) decode("[${it.getString(0)}]").firstOrNull() else null
        }

    private fun writeRecord(db: SQLiteDatabase, record: ForwardingHistoryRecord) {
        val values = ContentValues().apply {
            put("record_id", record.recordId)
            put("work_id", record.workId)
            put("payload", encode(record).toString())
            put("status", record.status)
            put("received_at", record.receivedAt)
            put("updated_at", record.updatedAt)
            put("is_test", if (record.isTest) 1 else 0)
        }
        if (db.update("history", values, "record_id = ?", arrayOf(record.recordId)) == 0) {
            check(db.insertWithOnConflict("history", null, values, SQLiteDatabase.CONFLICT_ABORT) >= 0) {
                "无法保存转发历史"
            }
        }
    }

    fun saveRetryPayload(recordId: String, payload: JSONObject) = synchronized(lock) {
        database.update("history", ContentValues().apply { put("retry_payload", payload.toString()) },
            "record_id = ?", arrayOf(recordId))
    }

    fun retryPayload(recordId: String): JSONObject? = synchronized(lock) {
        database.rawQuery("SELECT retry_payload FROM history WHERE record_id = ?", arrayOf(recordId)).use {
            if (!it.moveToFirst() || it.isNull(0)) null else runCatching { JSONObject(it.getString(0)) }.getOrNull()
        }
    }

    private fun migrateLegacy(db: SQLiteDatabase) = synchronized(lock) {
        val helper = ForwardingHistoryDatabase.get(appContext)
        if (helper.legacyChecked) return@synchronized
        val migrated = db.rawQuery("SELECT value FROM history_metadata WHERE key = 'legacy_imported'", null)
            .use { it.moveToFirst() }
        if (migrated) { helper.legacyChecked = true; return@synchronized }
        // Preserve the original preferences for recovery; import plus marker commit atomically.
        val raw = prefs.getString(KEY_RECORDS, "[]").orEmpty().ifBlank { "[]" }
        val imported = decode(raw)
        db.beginTransaction()
        try {
            imported.forEach { record ->
                if (readRecord(db, record.recordId) == null) writeRecord(db, record)
            }
            db.execSQL("INSERT INTO history_metadata(key, value) VALUES('legacy_imported', '1')")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        helper.legacyChecked = true
    }

    private fun notifyChanged() { EventBus.getDefault().post(Events.RefreshDashboard()) }

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

    companion object {
        const val STATUS_QUEUED = "queued"
        const val STATUS_WAITING_NETWORK = "waiting_network"
        const val STATUS_RUNNING = "running"
        const val STATUS_SUCCESS = "success"
        const val STATUS_RETRY = "retry"
        const val STATUS_FAILED = "failed"
        const val STATUS_UNKNOWN = "unknown"
        const val STATUS_SKIPPED = "skipped"

        private const val PREFS_NAME = "forwarding_history"
        private const val KEY_RECORDS = "records"
        private const val MAX_RECORDS = 2_000
        private const val MAX_SENDER_LENGTH = 160
        private const val MAX_BODY_LENGTH = 2_000
        private val lock = Any()

        fun recordId(workId: String, channel: String) = "$workId::$channel"
    }
}
