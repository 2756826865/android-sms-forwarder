package org.fossify.messages.messaging

import android.content.Context
import org.fossify.messages.forwarding.ForwardingSimSnapshot
import org.fossify.messages.security.crypto.KeyStoreHelper
import org.json.JSONObject

internal class SmsIngressSnapshots(context: Context) {
    private val app = context.applicationContext
    private val db
        get() = SmsIngressDatabase.get(app).writableDatabase

    fun snapshotForWork(workId: String): ForwardingSimSnapshot? =
        db.rawQuery(
                """
                SELECT j.sim_snapshot FROM ingress i JOIN ingress_journal j ON i.fingerprint=j.event_key
                            WHERE (?=i.work_id OR ? LIKE i.work_id||'-%') AND j.sim_snapshot IS NOT NULL
                            ORDER BY LENGTH(i.work_id) DESC LIMIT 1
                """
                    .trimIndent(),
                arrayOf(workId, workId),
            )
            .use {
                if (it.moveToFirst()) decodeSnapshot(it.getString(0)) else null
            }

    fun freezeExecutionSnapshot(
        requestId: String,
        snapshot: ForwardingSimSnapshot,
    ): ForwardingSimSnapshot =
        synchronized(SmsIngressStaging.lock) {
            db.execSQL(
                "INSERT OR IGNORE INTO ingress_execution_sim(request_id,snapshot) VALUES(?,?)",
                arrayOf(requestId, encodeSnapshot(snapshot)),
            )
            db.rawQuery(
                    "SELECT snapshot FROM ingress_execution_sim WHERE request_id=?",
                    arrayOf(requestId),
                )
                .use {
                    check(it.moveToFirst())
                    decodeSnapshot(it.getString(0)) ?: error("execution_snapshot_unreadable")
                }
        }

    private fun encodeSnapshot(s: ForwardingSimSnapshot): String =
        KeyStoreHelper.encrypt(
            JSONObject()
                .put("slot", s.slotIndex)
                .put("description", s.description)
                .put("number", s.receiverNumber)
                .put("label", s.customLabel)
                .put("sub", s.subscriptionId)
                .put("status", s.status)
                .put("source", s.source)
                .put("at", s.observedAt)
                .toString()
        )

    private fun decodeSnapshot(value: String): ForwardingSimSnapshot? =
        KeyStoreHelper.decrypt(value)?.let {
            val p = JSONObject(it)
            ForwardingSimSnapshot(
                p.getInt("slot"),
                p.getString("description"),
                p.getString("number"),
                p.getString("label"),
                p.optInt("sub", -1),
                p.optString("status", "UNKNOWN"),
                p.optString("source", "legacy"),
                p.optLong("at", 0),
            )
        }

    fun saveReceipt(key: String, snapshot: ForwardingSimSnapshot) {
        val existing =
            db.rawQuery(
                    "SELECT sim_snapshot,confirmed_sub FROM ingress_journal WHERE event_key=?",
                    arrayOf(key),
                )
                .use {
                    if (!it.moveToFirst()) return
                    if (!it.isNull(0))
                        decodeSnapshot(it.getString(0)) ?: error("receipt_snapshot_unreadable")
                    else null
                }
        if (existing != null && (existing.slotIndex >= 0 || snapshot.slotIndex < 0)) return
        val confirmed = SmsIngressJournal(app).receipt(key)?.sub ?: -1
        if (confirmed >= 0 && snapshot.subscriptionId >= 0 && confirmed != snapshot.subscriptionId)
            return
        db.execSQL(
            "UPDATE ingress_journal SET sim_snapshot=? WHERE event_key=?",
            arrayOf(encodeSnapshot(snapshot), key),
        )
    }
}
