package org.fossify.messages.forwarding

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Independent of optional shadow observations and deletable forwarding logs. */
internal object SmsDirectSendGuard {
    fun sendOnce(context: Context, source: String, destination: String, send: () -> Unit) {
        val key = SmsDirectBillingPolicy.key(source, destination)
        SmsDirectBillingPolicy.sendOnce(key, claim = { claim(context, it) }, send = send)
    }

    fun sendPrepared(context: Context, source: String, destination: String, recordId: String = "",
                     receiptRecordId: String = recordId, send: ((String) -> Unit) -> Unit) {
        val history = ForwardingHistoryStore(context)
        val key = SmsDirectBillingPolicy.key(source, destination)
        if (history.smsPhase(recordId) in setOf("submitting", "submitted", "sent", "failed", "unknown"))
            throw SmsDirectOutcomeUnknown("该短信已进入提交阶段，已阻止再次收费发送")
        history.setSmsPhase(recordId, "preparing")
        var claimed = false
        try {
            send { token ->
                check(!claimed) { "单个任务只允许一次收费提交" }
                if (!claim(context, key, token, receiptRecordId))
                    throw SmsDirectOutcomeUnknown("该短信已尝试提交，已阻止重复发送")
                claimed = true
                history.setSmsPhase(recordId, "submitting")
            }
            check(claimed) { "缺少收费提交边界" }
            database(context).writableDatabase.execSQL(
                "UPDATE sms_direct_claims SET phase='submitted' WHERE claim_key=? AND phase='submitting'", arrayOf(key))
            if (history.smsPhase(recordId) == "submitting") history.setSmsPhase(recordId, "submitted")
        } catch (error: Exception) {
            var cause: Throwable? = error
            val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
            while (cause != null && seen.add(cause)) {
                if (cause is SmsDirectOutcomeUnknown) {
                    history.setSmsPhase(recordId, "unknown")
                    throw cause
                }
                cause = cause.cause
            }
            if (claimed) {
                database(context).writableDatabase.execSQL(
                    "UPDATE sms_direct_claims SET phase='unknown' WHERE claim_key=? AND phase='submitting'", arrayOf(key))
                if (history.smsPhase(recordId) == "submitting") history.setSmsPhase(recordId, "unknown")
                if (error is java.util.concurrent.CancellationException) throw error
                throw SmsDirectOutcomeUnknown("短信可能已提交，停止自动重发", error)
            }
            history.setSmsPhase(recordId, "pre_submit_failed")
            if (error is java.util.concurrent.CancellationException) throw error
            throw SmsDirectPreSubmitFailure(error)
        }
    }

    fun recordSentResult(context: Context, token: String, success: Boolean) {
        if (token.isBlank()) return
        val db = database(context).writableDatabase
        db.beginTransaction()
        val records: List<Pair<String, Boolean>>
        try {
            db.execSQL("UPDATE sms_direct_claims SET phase=? WHERE message_uri=? AND phase<>'failed'",
                arrayOf(if (success) "sent" else "failed", token))
            records = db.rawQuery("SELECT record_id,phase FROM sms_direct_claims WHERE message_uri=?", arrayOf(token)).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) to (c.getString(1) == "sent")) }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        records.filter { it.first.isNotBlank() }.forEach {
            ForwardingHistoryStore(context).recordSmsResult(it.first, it.second)
        }
    }

    private fun claim(context: Context, key: String, token: String = "", recordId: String = ""): Boolean {
        val db = database(context).writableDatabase
        db.beginTransaction()
        return try {
            val values = ContentValues().apply {
                put("claim_key", key)
                put("claimed_at", System.currentTimeMillis())
                put("phase", "submitting"); put("message_uri", token); put("record_id", recordId)
            }
            val inserted = db.insertWithOnConflict("sms_direct_claims", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            db.setTransactionSuccessful()
            inserted
        } finally {
            db.endTransaction()
        }
    }

    private val instances = java.util.concurrent.ConcurrentHashMap<String, GuardDatabase>()
    private fun database(context: Context): GuardDatabase = instances.computeIfAbsent(
        context.getDatabasePath("sms_direct_guard.db").absolutePath
    ) { GuardDatabase(context.applicationContext) }

    private class GuardDatabase(context: Context) : SQLiteOpenHelper(context, "sms_direct_guard.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE_CLAIMS)
            db.execSQL("CREATE INDEX sms_direct_receipt ON sms_direct_claims(message_uri)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE sms_direct_claims ADD COLUMN phase TEXT NOT NULL DEFAULT 'unknown'")
                db.execSQL("ALTER TABLE sms_direct_claims ADD COLUMN message_uri TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE sms_direct_claims ADD COLUMN record_id TEXT NOT NULL DEFAULT ''")
                db.execSQL("CREATE INDEX sms_direct_receipt ON sms_direct_claims(message_uri)")
            }
        }
    }

    const val CREATE_CLAIMS = "CREATE TABLE sms_direct_claims (claim_key TEXT PRIMARY KEY NOT NULL, claimed_at INTEGER NOT NULL, phase TEXT NOT NULL DEFAULT 'unknown', message_uri TEXT NOT NULL DEFAULT '', record_id TEXT NOT NULL DEFAULT '')"
}
