package org.fossify.messages.forwarding

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Separate history database: updating delivery logs never migrates the SMS database. */
internal class ForwardingHistoryDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "forwarding_history.db", null, 3) {
    @Volatile var legacyChecked = false
    init { setWriteAheadLoggingEnabled(true) }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(CREATE_HISTORY)
        db.execSQL("CREATE TABLE group_execution (record_id TEXT PRIMARY KEY NOT NULL, status TEXT NOT NULL)")
        db.execSQL("CREATE INDEX history_updated ON history(updated_at DESC, record_id DESC)")
        db.execSQL("CREATE INDEX history_received ON history(is_test, received_at, status)")
        db.execSQL("CREATE TABLE history_metadata (key TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)")
        db.execSQL(CREATE_REPAIR_GUARD)
        db.execSQL(CREATE_MANUAL_RETRY_GUARD)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE history ADD COLUMN queue_checked_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL(CREATE_REPAIR_GUARD)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE history ADD COLUMN queue_detail TEXT NOT NULL DEFAULT ''")
            db.execSQL(CREATE_MANUAL_RETRY_GUARD)
        }
    }

    companion object {
        const val CREATE_HISTORY = "CREATE TABLE history (record_id TEXT PRIMARY KEY NOT NULL, work_id TEXT NOT NULL, payload TEXT NOT NULL, status TEXT NOT NULL, received_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, is_test INTEGER NOT NULL, retry_payload TEXT, queue_checked_at INTEGER NOT NULL DEFAULT 0, queue_detail TEXT NOT NULL DEFAULT '')"
        const val CREATE_REPAIR_GUARD = "CREATE TABLE queue_repair_guard (record_id TEXT PRIMARY KEY NOT NULL)"
        const val CREATE_MANUAL_RETRY_GUARD = "CREATE TABLE queue_manual_retry_guard (record_id TEXT PRIMARY KEY NOT NULL)"
        @Volatile private var instance: ForwardingHistoryDatabase? = null
        fun get(context: Context): ForwardingHistoryDatabase = instance ?: synchronized(this) {
            instance ?: ForwardingHistoryDatabase(context).also { instance = it }
        }
    }
}
