package org.fossify.messages.messaging

import android.content.Context
import android.content.Intent
import android.util.Base64
import org.fossify.messages.security.crypto.KeyStoreHelper
import org.json.JSONObject

internal class SmsIngressRecovery(context: Context) {
    private val app = context.applicationContext
    private val db
        get() = SmsIngressDatabase.get(app).writableDatabase

    fun pending(limit: Int = RECOVERY_BATCH_SIZE): List<Intent> =
        synchronized(SmsIngressStaging.lock) {
            db.rawQuery(
                    """
                    SELECT event_key,payload,confirmed_sub FROM ingress_journal WHERE done=0 AND reason NOT IN
                    ('provider_result_uncertain','payload_unreadable','configuration_changed') AND payload IS
                    NOT NULL AND received_at<=? ORDER BY received_at LIMIT ?
                    """
                        .trimIndent(),
                    arrayOf(
                        (System.currentTimeMillis() - HANDOFF_GRACE_MS).toString(),
                        limit.toString(),
                    ),
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext()) {
                            val key = c.getString(0)
                            val clear = KeyStoreHelper.decrypt(c.getString(1))
                            if (clear == null) {
                                SmsIngressJournal(app).reason(key, "payload_unreadable")
                                continue
                            }
                            runCatching { decodeIntent(clear, key) }
                                .onSuccess { add(it) }
                                .onFailure { SmsIngressJournal(app).reason(key, "payload_unreadable") }

                        }
                    }
                }
        }

    fun completedWork(workId: String): Boolean =
        db.rawQuery(
                """
                SELECT 1 FROM ingress i JOIN ingress_journal j ON i.fingerprint=j.event_key WHERE
                i.work_id=? AND j.done=1 LIMIT 1
                """
                    .trimIndent(),
                arrayOf(workId),
            )
            .use { it.moveToFirst() }

    fun ownsProvider(id: Long, date: Long): Boolean =
        db.rawQuery(
                "SELECT 1 FROM ingress_journal WHERE provider_id=? AND received_at=? LIMIT 1",
                arrayOf(id.toString(), date.toString()),
            )
            .use { it.moveToFirst() }

    fun statusSummary(): String =
        db.rawQuery("SELECT done,reason,COUNT(*) FROM ingress_journal GROUP BY done,reason", null)
            .use { c ->
                buildList {
                        while (c.moveToNext()) add(
                            "${if(c.getInt(0)==1) "完成" else "待恢复"}：${c.getString(1).ifBlank { "处理中" }} ${c.getInt(2)}条"
                        )
                    }
                    .joinToString("；")
            }

    fun hasReadyPending(): Boolean =
        db.rawQuery(
                """
                SELECT 1 FROM ingress_journal WHERE done=0 AND
                            reason NOT IN ('provider_result_uncertain','payload_unreadable','configuration_changed')
                            AND payload IS NOT NULL AND received_at<=? LIMIT 1
                """
                    .trimIndent(),
                arrayOf((System.currentTimeMillis() - HANDOFF_GRACE_MS).toString()),
            )
            .use { it.moveToFirst() }

    fun hasPending(): Boolean =
        db.rawQuery(
                """
                SELECT 1 FROM ingress_journal WHERE done=0 AND reason NOT IN
                ('provider_result_uncertain','payload_unreadable','configuration_changed') AND payload IS
                NOT NULL LIMIT 1
                """
                    .trimIndent(),
                null,
            )
            .use { it.moveToFirst() }

    fun cleanCompleted() {
        // Pending receipts are never aged out: an offline device must retain its recovery data.
        db.execSQL("UPDATE ingress_journal SET payload=NULL WHERE done=1 AND payload IS NOT NULL")
    }

    fun phaseSummary(): String =
        db.rawQuery(
                """
                SELECT phase,at FROM ingress_timing WHERE event_key=
                        (SELECT event_key FROM ingress_journal ORDER BY received_at DESC LIMIT 1) ORDER BY at
                """
                    .trimIndent(),
                null,
            )
            .use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0) + "：" + c.getLong(1)) }
                    .joinToString("\n")
            }

    companion object {
        fun decodeIntent(clear: String, key: String): Intent {
            val p = JSONObject(clear)
            val a = p.getJSONArray("pdus")
            return Intent(p.getString("action")).putExtra("format", p.optString("format"))
                .putExtra("pdus", Array(a.length()) { Base64.decode(a.getString(it), Base64.NO_WRAP) })
                .putExtra(SmsIngressJournal.EXTRA_EVENT, key).putExtra(SmsIngressJournal.EXTRA_REPLAY, true)
        }

        private const val RECOVERY_BATCH_SIZE = 16
        private const val HANDOFF_GRACE_MS = 10_000L
    }
}
