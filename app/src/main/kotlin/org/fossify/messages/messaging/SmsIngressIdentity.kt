package org.fossify.messages.messaging

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.security.MessageDigest

/** Durable correlation aliases, not delivery receipts. Never stores sender or SMS body.
 * A missing sent time only permits a unique, same-subscription match within two seconds.
 * Ambiguous matches remain separate: prefer a visible duplicate over losing a real SMS.
 */
internal class SmsIngressIdentity(context: Context) {
    data class Identity(val workId: String, val receivedAt: Long)
    private val helper = Database(context.applicationContext)
    fun broadcast(address: String, body: String, sentAt: Long, receivedAt: Long, sub: Int,
                  fingerprint: String, providerId: Long = -1): Identity =
        resolve(address, body, sentAt, receivedAt, sub, fingerprint, providerId)
    fun provider(address: String, body: String, sentAt: Long, receivedAt: Long, sub: Int,
                 providerId: Long): Identity {
        require(providerId > 0)
        return resolve(address, body, sentAt, receivedAt, sub, null, providerId)
    }
    private fun resolve(address: String, body: String, sentAt: Long, receivedAt: Long, sub: Int,
                        fingerprint: String?, providerId: Long): Identity = synchronized(lock) {
        resolveLocked(address, body, sentAt, receivedAt, sub, fingerprint, providerId)
    }
    private fun resolveLocked(address: String, body: String, sentAt: Long, receivedAt: Long, sub: Int,
                              fingerprint: String?, providerId: Long): Identity {
        val hash = hash("$address\u0000$body")
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            fun find(sql: String, args: Array<String>): List<Identity> = db.rawQuery(sql, args).use { c ->
                buildList { while (c.moveToNext()) add(Identity(c.getString(0), c.getLong(1))) }
            }
            val byFingerprint = fingerprint?.let {
                find("SELECT work_id, received_at FROM ingress WHERE fingerprint=?", arrayOf(it)).singleOrNull()
            }
            val byProvider = if (providerId > 0) find(
                "SELECT work_id, received_at FROM ingress WHERE provider_id=? AND provider_date=? AND content_hash=? AND sub_id=?",
                arrayOf(providerId.toString(), receivedAt.toString(), hash, sub.toString())).singleOrNull() else null
            // Preserve the old owner when a provider reuses a deleted row ID.
            if (providerId > 0 && byProvider == null) db.execSQL(
                "UPDATE ingress SET provider_id=NULL, provider_date=NULL WHERE provider_id=?", arrayOf(providerId))
            var identity = byFingerprint ?: byProvider
            if (identity == null && sub >= 0) {
                val opposite = if (fingerprint == null) "provider_id IS NULL AND fingerprint IS NOT NULL"
                    else "fingerprint IS NULL AND provider_id IS NOT NULL"
                identity = find("""SELECT work_id, received_at FROM ingress WHERE content_hash=? AND sub_id=?
                    AND $opposite AND received_at BETWEEN ? AND ?
                    AND (sent_at<=0 OR CAST(? AS INTEGER)<=0 OR sent_at=?) LIMIT 2""".trimIndent(),
                    arrayOf(hash, sub.toString(), (receivedAt - 2000).toString(), (receivedAt + 2000).toString(),
                        sentAt.toString(), sentAt.toString())).singleOrNull()
            }
            if (identity == null) {
                val base = if (providerId > 0) "sms-$providerId" else "sms-minimal-$fingerprint"
                val owner = if (find("SELECT work_id, received_at FROM ingress WHERE work_id=?", arrayOf(base)).isEmpty()) base
                    else "$base-${hash("$hash:$receivedAt:$sub").take(16)}"
                db.execSQL("INSERT INTO ingress VALUES(?,?,?,?,?,?,?,?)",
                    arrayOf(owner, fingerprint, providerId.takeIf { it > 0 }, hash, sentAt, receivedAt, sub,
                        receivedAt.takeIf { providerId > 0 }))
                identity = Identity(owner, receivedAt)
            } else {
                if (fingerprint != null && byFingerprint == null) db.execSQL(
                    "UPDATE ingress SET fingerprint=? WHERE work_id=? AND fingerprint IS NULL", arrayOf(fingerprint, identity.workId))
                if (providerId > 0 && byProvider == null) db.execSQL(
                    "UPDATE ingress SET provider_id=?,provider_date=? WHERE work_id=? AND provider_id IS NULL",
                    arrayOf(providerId, receivedAt, identity.workId))
            }
            db.setTransactionSuccessful()
            return identity
        } finally { db.endTransaction(); helper.close() }
    }
    private class Database(context: Context) : SQLiteOpenHelper(context, "sms_ingress_identity.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(CREATE)
            db.execSQL("CREATE INDEX ingress_content ON ingress(content_hash,sub_id,received_at)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    companion object {
        private val lock = Any()
        const val CREATE = "CREATE TABLE ingress(work_id TEXT PRIMARY KEY NOT NULL, fingerprint TEXT UNIQUE, provider_id INTEGER UNIQUE, content_hash TEXT NOT NULL, sent_at INTEGER NOT NULL, received_at INTEGER NOT NULL, sub_id INTEGER NOT NULL, provider_date INTEGER)"
        private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
