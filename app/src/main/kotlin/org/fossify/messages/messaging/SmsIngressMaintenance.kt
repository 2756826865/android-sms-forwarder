package org.fossify.messages.messaging

import android.content.Context
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import org.fossify.messages.forwarding.ForwardingHistoryStore
import org.fossify.messages.forwarding.MultiChannelForwardWorker

/** Keep compact ownership tombstones. Only unreferenced, completed auxiliary data is expired. */
internal class SmsIngressMaintenance(private val context: Context) {
    fun clean() {
        val prefs = context.getSharedPreferences("sms_ingress_maintenance", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last_cleanup", 0) < CLEAN_INTERVAL_MS) return
        val history = ForwardingHistoryStore(context)
        val workIds = history.workIds()
        val requests = requestReferences(history)
        val db = SmsIngressDatabase.get(context).writableDatabase
        val cutoff = (now - RETENTION_MS).toString()
        var nextCursor = ""
        synchronized(SmsIngressStaging.lock) {
            db.beginTransaction()
            try {
                db.rawQuery(
                        "SELECT request_id FROM ingress_execution_sim WHERE created_at<?",
                        arrayOf(cutoff),
                    )
                    .use { c ->
                        val expired = buildList {
                            while (c.moveToNext()) if (
                                IngressRetentionPolicy.canExpireSnapshot(c.getString(0), requests)
                            )
                                add(c.getString(0))
                        }
                        expired.forEach {
                            db.execSQL(
                                "DELETE FROM ingress_execution_sim WHERE request_id=?",
                                arrayOf(it),
                            )
                        }
                    }
                val completed =
                    db.rawQuery(
                            """
                            SELECT event_key FROM ingress_journal WHERE done=1 AND received_at<?
                                                AND NOT EXISTS(SELECT 1 FROM ingress_alert a WHERE a.event_key=ingress_journal.event_key AND a.delivered=0)
                              AND (sim_snapshot IS NOT NULL OR evidence!='')
                              AND event_key>? ORDER BY event_key LIMIT 256
                            """
                                .trimIndent(),
                            arrayOf(cutoff, prefs.getString("cursor", "").orEmpty()),
                        )
                        .use { c ->
                            buildList { while (c.moveToNext()) add(c.getString(0)) }
                        }
                nextCursor = completed.lastOrNull().orEmpty()
                for (key in completed) {
                    val roots =
                        db.rawQuery("SELECT work_id FROM ingress WHERE fingerprint=?", arrayOf(key))
                            .use { c ->
                                buildList { while (c.moveToNext()) add(c.getString(0)) }
                            }
                    if (IngressRetentionPolicy.referenced(roots, workIds)) continue
                    db.execSQL("DELETE FROM ingress_alias WHERE event_key=?", arrayOf(key))
                    db.execSQL("DELETE FROM ingress_timing WHERE event_key=?", arrayOf(key))
                    db.execSQL(
                        "DELETE FROM ingress_alert WHERE event_key=? AND delivered=1",
                        arrayOf(key),
                    )
                    db.execSQL(
                        """
                        UPDATE ingress_journal SET sim_snapshot=NULL,evidence='',routing_digest='',payload=NULL
                                                   WHERE event_key=?
                        """
                            .trimIndent(),
                        arrayOf(key),
                    )
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }
        prefs.edit().putLong("last_cleanup", now).putString("cursor", nextCursor).apply()
    }

    private fun requestReferences(history: ForwardingHistoryStore): Set<String> {
        val requests = mutableSetOf<String>()
        val works =
            WorkManager.getInstance(context)
                .getWorkInfosByTag(MultiChannelForwardWorker::class.java.name)
                .get(QUERY_SECONDS, TimeUnit.SECONDS)
        requests.addAll(works.map { it.id.toString() })
        var offset = 0
        while (true) {
            val records = history.records(PAGE_SIZE, offset)
            records.forEach { record ->
                history
                    .retryPayload(record.recordId)
                    ?.optString("queueRequestId")
                    ?.takeIf(String::isNotBlank)
                    ?.let(requests::add)
            }
            if (records.size < PAGE_SIZE) break
            offset += records.size
        }
        history.pendingSubmissions().forEach {
            it.optString("queueRequestId").takeIf(String::isNotBlank)?.let(requests::add)
        }
        return requests
    }

    companion object {
        private const val CLEAN_INTERVAL_MS = 60 * 60 * 1000L
        private const val RETENTION_MS = 30 * 24 * CLEAN_INTERVAL_MS
        private const val QUERY_SECONDS = 5L
        private const val PAGE_SIZE = 100
    }
}
