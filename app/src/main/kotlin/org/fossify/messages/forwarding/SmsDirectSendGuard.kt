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

    private fun claim(context: Context, key: String): Boolean {
        val db = database(context).writableDatabase
        db.beginTransaction()
        return try {
            val values = ContentValues().apply {
                put("claim_key", key)
                put("claimed_at", System.currentTimeMillis())
            }
            val inserted = db.insertWithOnConflict("sms_direct_claims", null, values, SQLiteDatabase.CONFLICT_IGNORE) != -1L
            db.setTransactionSuccessful()
            inserted
        } finally {
            db.endTransaction()
        }
    }

    @Volatile private var instance: GuardDatabase? = null
    private fun database(context: Context): GuardDatabase = instance ?: synchronized(this) {
        instance ?: GuardDatabase(context.applicationContext).also { instance = it }
    }

    private class GuardDatabase(context: Context) : SQLiteOpenHelper(context, "sms_direct_guard.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) = db.execSQL(CREATE_CLAIMS)
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    const val CREATE_CLAIMS = "CREATE TABLE sms_direct_claims (claim_key TEXT PRIMARY KEY NOT NULL, claimed_at INTEGER NOT NULL)"
}
