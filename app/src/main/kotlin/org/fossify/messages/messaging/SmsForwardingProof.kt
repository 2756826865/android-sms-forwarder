package org.fossify.messages.messaging

import android.content.Context

/**
 * Permanent, content-free evidence that an SMS queue/skip decision was written.
 * The original WorkManager and forwarding-history records still own delivery.
 * A provider identity by itself is not a dispatch receipt.
 */
internal class SmsForwardingProof(context: Context) {
    private val db get() = SmsIngressDatabase.get(context).writableDatabase

    fun record(workId: String) {
        require(workId.startsWith("sms-"))
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
}
