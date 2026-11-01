package org.fossify.messages.messaging

import android.content.Context
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.helpers.IncomingCodeAlerts
import org.fossify.messages.security.crypto.KeyStoreHelper
import org.json.JSONObject

/**
 * Durable notification outbox. A publish crash retries the same notification ID, never forwarding.
 */
internal class SmsIngressAlerts(private val context: Context) {
    private val db
        get() = SmsIngressDatabase.get(context).writableDatabase

    @Suppress("LongParameterList") // Immutable notification envelope, shared by both receive paths.
    fun enqueue(
        key: String,
        messageId: Long,
        address: String,
        body: String,
        threadId: Long,
        sender: String = address,
    ) {
        if (published(key)) return
        val payload =
            JSONObject()
                .put("id", messageId)
                .put("address", address)
                .put("body", body)
                .put("thread", threadId)
                .put("sender", sender)
        val createdAt =
            SmsIngressJournal(context).receipt(key)?.receivedAt ?: System.currentTimeMillis()
        db.execSQL(
            "INSERT OR IGNORE INTO ingress_alert(event_key,payload,created_at,thread_id) VALUES(?,?,?,?)",
            arrayOf<Any?>(key, KeyStoreHelper.encrypt(payload.toString()), createdAt, threadId),
        )
        publish(key)
        SmsAlertRecoveryWorker.enqueue(context)
    }

    private fun published(key: String): Boolean =
        db.rawQuery(
                "SELECT notification_claimed FROM ingress_journal WHERE event_key=?",
                arrayOf(key),
            )
            .use { it.moveToFirst() && it.getInt(0) != 0 }

    fun drain() {
        val keys =
            db.rawQuery(
                    """SELECT event_key FROM ingress_alert WHERE delivered=0 AND next_attempt_at<=?
                        ORDER BY created_at LIMIT 16""".trimIndent(),
                    arrayOf(System.currentTimeMillis().toString()),
                )
                .use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }
        keys.forEach(::publish)
    }

    fun hasPending(): Boolean =
        db.rawQuery(
                "SELECT 1 FROM ingress_alert WHERE delivered=0 LIMIT 1",
                null,
            )
            .use { it.moveToFirst() }

    @Suppress(
        "TooGenericExceptionCaught"
    ) // Notification/Keystore platform boundary: retain for retry.
    private fun publish(key: String) =
        synchronized(publishing) {
            val leaseUntil = android.os.SystemClock.elapsedRealtime() + PUBLISH_LEASE_MS
            if ((publishing[key] ?: 0L) > android.os.SystemClock.elapsedRealtime())
                return@synchronized
            publishing[key] = leaseUntil
            fun release() =
                synchronized(publishing) {
                    if (publishing[key] == leaseUntil) publishing.remove(key)
                }
            var asynchronous = false
            try {
                val encrypted =
                    db.rawQuery(
                            "SELECT payload FROM ingress_alert WHERE event_key=? AND delivered=0",
                            arrayOf(key),
                        )
                        .use { if (it.moveToFirst()) it.getString(0) else null }
                        ?: return@synchronized
                val clear = KeyStoreHelper.decrypt(encrypted) ?: error("alert_key_unavailable")
                val p = JSONObject(clear)
                asynchronous = publishEnvelope(key, p, ::release)
            } catch (error: Exception) {
                asynchronous = false
                defer(key, error.javaClass.simpleName)
                android.util.Log.w(
                    "SmsIngressAlerts",
                    "notification pending: ${error.javaClass.simpleName}",
                )
            } finally {
                if (!asynchronous) release()
            }
        }

    private fun publishEnvelope(key: String, p: JSONObject, release: () -> Unit): Boolean {
        val id = p.getLong("id")
        val address = p.getString("address")
        val body = p.getString("body")
        val thread = p.getLong("thread")
        if (
            published(key) ||
                context.messagesDB.getMessageWithId(id)?.read == true ||
                superseded(key, thread)
        ) {
            delivered(key)
            return false
        }
        if (id <= 0) {
            IncomingCodeAlerts.showUnstored(context, address, body, key)
            delivered(key)
            return false
        }
        context.showReceivedMessageNotification(
            id,
            address,
            p.getString("sender"),
            body,
            thread,
            null,
            onPublished = {
                runCatching {
                    delivered(key)
                    org.fossify.messages.autofill.SmsAutofillAccessibilityService
                        .onNewVerificationSms(context, body)
                    IncomingCodeAlerts.popup(context, address, body, key)
                }
                    .also { release() }
            },
            onSuppressed = {
                runCatching { delivered(key) }.also { release() }
            },
            onFailed = {
                defer(key, "notification_publish_failed")
                release()
            },
        )
        return true
    }

    private fun defer(key: String, reason: String) {
        runCatching {
            db.execSQL(
                "UPDATE ingress_alert SET error=?,next_attempt_at=? WHERE event_key=? AND delivered=0",
                arrayOf<Any?>(reason, System.currentTimeMillis() + RETRY_MS, key),
            )
        }
    }

    private fun superseded(key: String, thread: Long): Boolean =
        thread > 0 &&
            db.rawQuery(
                    """
                    SELECT 1 FROM ingress_alert newer JOIN ingress_alert old ON old.event_key=?
                               WHERE newer.thread_id=? AND newer.delivered=1 AND newer.created_at>old.created_at LIMIT 1
                    """
                        .trimIndent(),
                    arrayOf(key, thread.toString()),
                )
                .use { it.moveToFirst() }

    private fun delivered(key: String) {
        db.beginTransaction()
        try {
            db.execSQL(
                "UPDATE ingress_alert SET delivered=1,payload=NULL,error='' WHERE event_key=?",
                arrayOf(key),
            )
            db.execSQL(
                "UPDATE ingress_journal SET notification_claimed=1 WHERE event_key=?",
                arrayOf(key),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    companion object {
        private val publishing = mutableMapOf<String, Long>()
        private const val PUBLISH_LEASE_MS = 30_000L
        private const val RETRY_MS = 60_000L
    }
}
