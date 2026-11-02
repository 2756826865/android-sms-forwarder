package org.fossify.messages.messaging

import android.content.Context
import org.fossify.messages.forwarding.ForwardingHistoryRecord
import org.fossify.messages.forwarding.ForwardingHistoryStore

/**
 * Content-free durable evidence of an accepted WorkManager request, an actually started
 * worker, or an explicitly skipped SMS. Never write a proof from registerQueued().
 * An ingress identity alone does not prove a forwarding decision.
 */
internal class SmsForwardingProof(context: Context) {
    private val app = context.applicationContext
    private val db get() = SmsIngressDatabase.get(app).writableDatabase

    fun record(workId: String) {
        require(workId.startsWith("sms-") && workId.length > 4)
        db.execSQL(
            "INSERT OR IGNORE INTO ingress_forwarding_proof(work_id,created_at) VALUES(?,?)",
            arrayOf<Any?>(workId, System.currentTimeMillis()),
        )
    }

    fun hasAny(owner: String): Boolean =
        db.rawQuery(
            """SELECT 1 FROM ingress_forwarding_proof
               WHERE work_id=? OR substr(work_id,1,?)=? LIMIT 1""".trimIndent(),
            arrayOf(owner, (owner.length + 1).toString(), "$owner-"),
        ).use { it.moveToFirst() }

    /**
     * One-time migration of only observable, already-recorded forwarding outcomes.
     * A queued/unknown record without a verified request is deliberately NOT proof.
     * History lost before migration cannot be reconstructed safely.
     */
    fun backfillKnownHistory(history: ForwardingHistoryStore) {
        val prefs = app.getSharedPreferences("sms_forwarding_proof_migration", Context.MODE_PRIVATE)
        if (prefs.getBoolean("known_history_imported_v1", false)) return
        val candidates = history.records().filter(::hasProvenOutcome)
        val writable = db
        writable.beginTransaction()
        try {
            candidates.forEach { record ->
                writable.execSQL(
                    "INSERT OR IGNORE INTO ingress_forwarding_proof(work_id,created_at) VALUES(?,?)",
                    arrayOf<Any?>(record.workId, System.currentTimeMillis()),
                )
            }
            writable.setTransactionSuccessful()
        } finally {
            writable.endTransaction()
        }
        check(prefs.edit().putBoolean("known_history_imported_v1", true).commit()) {
            "forwarding_proof_migration_marker_unavailable"
        }
    }

    companion object {
        internal fun hasProvenOutcome(record: ForwardingHistoryRecord): Boolean {
            if (record.isTest || !record.workId.startsWith("sms-")) return false
            return record.status == ForwardingHistoryStore.STATUS_SKIPPED ||
                record.status == ForwardingHistoryStore.STATUS_SUCCESS ||
                (record.attempts > 0 && record.status in setOf(
                    ForwardingHistoryStore.STATUS_RUNNING,
                    ForwardingHistoryStore.STATUS_RETRY,
                    ForwardingHistoryStore.STATUS_FAILED,
                    ForwardingHistoryStore.STATUS_UNKNOWN,
                ))
        }
    }
}
