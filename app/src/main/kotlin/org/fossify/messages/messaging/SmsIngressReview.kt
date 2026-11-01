package org.fossify.messages.messaging

import android.content.Context
import android.provider.Telephony
import org.fossify.messages.security.crypto.KeyStoreHelper

/** Uncertain writes are reconciled read-only. A zero/ambiguous match never authorizes insertion. */
internal class SmsIngressReview(private val context: Context) {
    private val db
        get() = SmsIngressDatabase.get(context).writableDatabase

    data class Item(val key: String, val receivedAt: Long, val reason: String)

    fun items(): List<Item> =
        db.rawQuery(
                """
                SELECT event_key,received_at,reason FROM ingress_journal WHERE done=0 AND reason IN
                           ('provider_result_uncertain','payload_unreadable','configuration_changed')
                UNION ALL SELECT event_key,created_at,'notification_pending' FROM ingress_alert
                WHERE delivered=0 AND error!='' ORDER BY 2 LIMIT 20
                """
                    .trimIndent(),
                null,
            )
            .use { c ->
                buildList {
                    while (c.moveToNext()) add(Item(c.getString(0), c.getLong(1), c.getString(2)))
                }
            }

    fun reconcile() {
        val uncertain =
            db.rawQuery(
                    """
                    SELECT j.event_key,j.received_at,j.reason FROM ingress_journal j
                                LEFT JOIN ingress_timing t ON t.event_key=j.event_key AND t.phase='provider_review'
                                WHERE j.done=0 AND j.reason='provider_result_uncertain'
                                ORDER BY COALESCE(t.at,0),j.received_at LIMIT 20
                    """
                        .trimIndent(),
                    null,
                )
                .use { c ->
                    buildList {
                        while (c.moveToNext()) add(
                            Item(c.getString(0), c.getLong(1), c.getString(2))
                        )
                    }
                }
        uncertain.forEach { item ->
            runCatching {
                val encrypted =
                    db.rawQuery(
                            "SELECT payload FROM ingress_journal WHERE event_key=?",
                            arrayOf(item.key),
                        )
                        .use { if (it.moveToFirst()) it.getString(0) else null }
                        ?: return@runCatching
                val clear = KeyStoreHelper.decrypt(encrypted) ?: return@runCatching
                val intent = SmsIngressRecovery.decodeIntent(clear, item.key)
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (parts.isEmpty()) return@runCatching
                val receipt = SmsIngressJournal(context).receipt(item.key) ?: return@runCatching
                val id =
                    SmsProviderPersistence.findProviderReceipt(
                        context,
                        parts.last().originatingAddress.orEmpty(),
                        parts.joinToString("") { it.messageBody.orEmpty() },
                        parts.minOf { it.timestampMillis }.takeIf { it > 0 } ?: receipt.receivedAt,
                        receipt.receivedAt,
                        receipt.sub,
                    )
                if (id > 0) SmsIngressJournal(context).providerResult(item.key, id)
            }
                .also {
                    db.execSQL(
                        "INSERT OR REPLACE INTO ingress_timing(event_key,phase,at) VALUES(?,'provider_review',?)",
                        arrayOf<Any?>(item.key, System.currentTimeMillis()),
                    )
                }
        }
    }

    /**
     * Explicit UI dismissal closes only this recovery event; never deletes Provider SMS or queue
     * owners.
     */
    fun dismiss(item: Item) =
        synchronized(SmsIngressJournal.processLock) {
            if (item.reason == "notification_pending") {
                db.execSQL(
                    "UPDATE ingress_alert SET delivered=1,payload=NULL,error='' WHERE event_key=?",
                    arrayOf(item.key),
                )
                return@synchronized
            }
            db.execSQL(
                """
                UPDATE ingress_journal SET done=1,payload=NULL,reason='review_dismissed'
                            WHERE event_key=? AND done=0 AND reason=?
                """
                    .trimIndent(),
                arrayOf(item.key, item.reason),
            )
        }
}
