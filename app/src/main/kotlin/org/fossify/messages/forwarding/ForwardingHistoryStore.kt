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

    fun pendingQueueRecords(limit: Int): List<ForwardingHistoryRecord> = synchronized(lock) {
        database.rawQuery("SELECT payload FROM history WHERE is_test = 0 AND status IN ('queued','waiting_network','retry','running','unknown') AND NOT EXISTS (SELECT 1 FROM queue_manual_retry_guard WHERE queue_manual_retry_guard.record_id = history.record_id) ORDER BY queue_checked_at ASC, updated_at ASC LIMIT ?",
            arrayOf(limit.coerceIn(1, 100).toString())).use { cursor -> buildList {
            while (cursor.moveToNext()) addAll(decode("[${cursor.getString(0)}]"))
        } }
    }

    /** Compare against the snapshot: a concurrent worker result always wins. */
    fun observeQueue(expected: ForwardingHistoryRecord, status: String, detail: String) = synchronized(lock) {
        val db = database
        db.beginTransaction()
        var changed = false
        try {
            val current = readRecord(db, expected.recordId)
            if (current != null && current == expected) {
                if (current.status != status || queueObservation(expected.recordId) != detail) {
                    // Scheduling observations are not delivery errors. Keep the original error intact.
                    writeRecord(db, current.copy(status = status, updatedAt = System.currentTimeMillis()))
                    changed = true
                }
                db.update("history", ContentValues().apply {
                    put("queue_checked_at", System.currentTimeMillis()); put("queue_detail", detail)
                },
                    "record_id = ?", arrayOf(expected.recordId))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (changed) notifyChanged()
    }

    fun queueObservation(recordId: String): String = synchronized(lock) {
        database.rawQuery("SELECT queue_detail FROM history WHERE record_id = ?", arrayOf(recordId))
            .use { if (it.moveToFirst()) it.getString(0).orEmpty() else "" }
    }

    /** Compare-and-reserve also arbitrates against an automatic repair of this record. */
    fun claimManualRetry(expected: ForwardingHistoryRecord): Boolean = synchronized(lock) {
        val db = database
        db.beginTransaction()
        var claimed = false
        try {
            val current = readRecord(db, expected.recordId)
            if (current != null && current == expected && current.status in setOf(STATUS_FAILED, STATUS_UNKNOWN)) {
                claimed = db.insertWithOnConflict("queue_manual_retry_guard", null,
                    ContentValues().apply { put("record_id", current.recordId) }, SQLiteDatabase.CONFLICT_IGNORE) >= 0
                if (claimed) writeRecord(db, current.copy(
                    detail = current.detail + "；已安排此通道的手动重试，请查看重试记录",
                    updatedAt = System.currentTimeMillis()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (claimed) notifyChanged()
        claimed
    }

    fun queueRepairClaimed(recordId: String): Boolean = synchronized(lock) {
        database.rawQuery("SELECT record_id FROM queue_repair_guard WHERE record_id = ?", arrayOf(recordId))
            .use { it.moveToFirst() }
    }

    /** Durable, at most one automatic repair. Clearing display history does not clear this guard. */
    fun claimQueueRepair(expected: ForwardingHistoryRecord): Boolean = synchronized(lock) {
        val db = database
        db.beginTransaction()
        var claimed = false
        try {
            val current = readRecord(db, expected.recordId)
            if (current != null && current == expected && current.status in ForwardingReconcilePolicy.pending && current.attempts == 0) {
                claimed = db.insertWithOnConflict("queue_repair_guard", null,
                    ContentValues().apply { put("record_id", current.recordId) }, SQLiteDatabase.CONFLICT_IGNORE) >= 0
                if (claimed) writeRecord(db, current.copy(status = STATUS_RETRY,
                    detail = "原任务未启动，已安排一次安全补偿", updatedAt = System.currentTimeMillis()))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        if (claimed) notifyChanged()
        claimed
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
        // KEEP may retain an older WorkRequest. A duplicate event must not reset
        // its attempts, result, error or retry payload before enqueue completes.
        if (existing != null) return@synchronized recordId
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
            detail = "正在提交发送任务",
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

    fun markEnqueued(recordId: String) = update(recordId) {
        if (ForwardingQueuePolicy.canUpdateSubmission(it.status, it.attempts))
            it.copy(detail = "已提交系统调度；点击查看排队原因", updatedAt = System.currentTimeMillis())
        else it
    }

    fun markEnqueueFailed(recordId: String) = update(recordId) {
        if (ForwardingQueuePolicy.canUpdateSubmission(it.status, it.attempts))
            it.copy(status = STATUS_UNKNOWN, detail = "任务提交异常，可能已保存；请先查看排队原因，避免重复发送",
                updatedAt = System.currentTimeMillis())
        else it
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

    /** Called only after WorkManager confirms this exact request UUID was persisted. */
    fun bindQueueSnapshot(recordId: String, payload: JSONObject, expectedRequestId: String? = null): Int = synchronized(lock) {
        val db = database
        val values = ContentValues().apply { put("retry_payload", payload.toString()) }
        if (expectedRequestId == null) return@synchronized db.update("history", values,
            "record_id = ? AND retry_payload IS NULL", arrayOf(recordId))
        // Only a verified one-shot repair may replace its original snapshot.
        // Compare the raw stored value so concurrent callbacks cannot replace a newer owner.
        val previous = db.rawQuery("SELECT retry_payload FROM history WHERE record_id = ?", arrayOf(recordId))
            .use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }
            ?: return@synchronized 0
        if (JSONObject(previous).optString("queueRequestId") != expectedRequestId) return@synchronized 0
        db.update("history", values, "record_id = ? AND retry_payload = ?", arrayOf(recordId, previous))
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
