package org.fossify.messages.messaging

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Additive migration: old event owners and submission aliases are never dropped. */
internal class SmsIngressDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context, "sms_ingress_identity.db", null, DATABASE_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(SmsIngressIdentity.CREATE)
        db.execSQL("CREATE INDEX ingress_content ON ingress(content_hash,sub_id,received_at)")
        createJournal(db)
        createExecutionSnapshots(db)
        createTiming(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < JOURNAL_VERSION) createJournal(db)
        if (oldVersion == JOURNAL_VERSION)
            db.execSQL("ALTER TABLE ingress_journal ADD COLUMN sim_snapshot TEXT")
        if (oldVersion < SNAPSHOTS_VERSION) createExecutionSnapshots(db)
        if (oldVersion < TIMING_VERSION) createTiming(db)
        if (oldVersion in JOURNAL_VERSION until ROUTING_VERSION)
            db.execSQL(
                "ALTER TABLE ingress_journal ADD COLUMN routing_digest TEXT NOT NULL DEFAULT ''"
            )
    }

    private fun createJournal(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE ingress_journal(event_key TEXT PRIMARY KEY NOT NULL,
                        pdu_hash TEXT NOT NULL, first_action TEXT, raw_sub INTEGER NOT NULL, confirmed_sub INTEGER NOT NULL,
                        slot INTEGER NOT NULL, evidence TEXT NOT NULL, observed_at INTEGER NOT NULL,
                        received_at INTEGER NOT NULL, payload TEXT, provider_attempted INTEGER NOT NULL DEFAULT 0,
                        provider_id INTEGER NOT NULL DEFAULT -1, done INTEGER NOT NULL DEFAULT 0,
                        reason TEXT NOT NULL DEFAULT '', routing_digest TEXT NOT NULL DEFAULT '', sim_snapshot TEXT, notification_claimed INTEGER NOT NULL DEFAULT 0)
            """
                .trimIndent()
        )
        db.execSQL("CREATE INDEX journal_pending ON ingress_journal(done,received_at)")
        db.execSQL("CREATE INDEX journal_pdu ON ingress_journal(pdu_hash,received_at)")
        db.execSQL(
            "CREATE TABLE ingress_alias(alias TEXT PRIMARY KEY NOT NULL,event_key TEXT NOT NULL)"
        )
    }

    private fun createExecutionSnapshots(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE ingress_execution_sim(request_id TEXT PRIMARY KEY NOT NULL,snapshot TEXT NOT NULL)"
        )
    }

    private fun createTiming(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE ingress_timing(event_key TEXT NOT NULL,phase TEXT NOT NULL,at INTEGER NOT
            NULL,PRIMARY KEY(event_key,phase))
            """
                .trimIndent()
        )
    }

    companion object {
        private const val JOURNAL_VERSION = 2
        private const val SNAPSHOTS_VERSION = 3
        private const val ROUTING_VERSION = 4
        private const val TIMING_VERSION = 5
        private const val DATABASE_VERSION = TIMING_VERSION
        private val instances = mutableMapOf<String, SmsIngressDatabase>()

        @Synchronized
        fun get(context: Context): SmsIngressDatabase {
            val app = context.applicationContext
            val path = app.getDatabasePath("sms_ingress_identity.db").absolutePath
            return instances.getOrPut(path) { SmsIngressDatabase(app) }
        }
    }
}
