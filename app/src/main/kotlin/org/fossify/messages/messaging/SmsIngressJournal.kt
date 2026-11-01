package org.fossify.messages.messaging

import android.content.Context
import android.content.Intent

/** Short-lived encrypted PDU journal in the existing ingress DB, not another forwarding queue. */
internal class SmsIngressJournal(context: Context) {
    private val app = context.applicationContext
    private val db
        get() = SmsIngressDatabase.get(app).writableDatabase

    data class Receipt(
        val key: String,
        val receivedAt: Long,
        val sub: Int,
        val slot: Int,
        val providerId: Long,
        val attempted: Boolean,
        val done: Boolean,
    )

    fun receipt(intent: Intent): Receipt? = intent.getStringExtra(EXTRA_EVENT)?.let(::receipt)

    fun receipt(key: String): Receipt? =
        db.rawQuery(
                """
                SELECT event_key,received_at,confirmed_sub,slot,provider_id,provider_attempted,done FROM
                ingress_journal WHERE event_key=?
                """
                    .trimIndent(),
                arrayOf(key),
            )
            .use { c ->
                if (!c.moveToFirst()) null
                else
                    Receipt(
                        c.getString(0),
                        c.getLong(1),
                        c.getInt(2),
                        c.getInt(SLOT_COLUMN),
                        c.getLong(PROVIDER_COLUMN),
                        c.getInt(ATTEMPTED_COLUMN) != 0,
                        c.getInt(DONE_COLUMN) != 0,
                    )
            }

    fun stage(source: Intent): Intent = SmsIngressStaging(app, this).stage(source)

    fun routingAllowed(key: String): Boolean = SmsIngressRouting(app).allowed(key)

    fun providerAttempt(key: String) =
        db.execSQL(
            "UPDATE ingress_journal SET provider_attempted=1 WHERE event_key=?",
            arrayOf(key),
        )

    fun providerResult(key: String, id: Long) =
        db.execSQL(
            "UPDATE ingress_journal SET provider_id=?,reason='' WHERE event_key=?",
            arrayOf<Any?>(id, key),
        )

    fun reason(key: String, reason: String) =
        db.execSQL("UPDATE ingress_journal SET reason=? WHERE event_key=?", arrayOf(reason, key))

    fun complete(key: String) =
        db.execSQL(
            "UPDATE ingress_journal SET done=1,payload=NULL,reason='' WHERE event_key=?",
            arrayOf(key),
        )

    fun phase(key: String, name: String) {
        runCatching {
            db.execSQL(
                "INSERT OR IGNORE INTO ingress_timing(event_key,phase,at) VALUES(?,?,?)",
                arrayOf<Any?>(key, name, System.currentTimeMillis()),
            )
        }
    }

    companion object {
        private const val SLOT_COLUMN = 3
        private const val PROVIDER_COLUMN = 4
        private const val ATTEMPTED_COLUMN = 5
        private const val DONE_COLUMN = 6
        const val EXTRA_EVENT = "org.fossify.messages.INGRESS_EVENT"
        const val EXTRA_REPLAY = "org.fossify.messages.INGRESS_REPLAY"
        val processLock = Any()
    }
}
