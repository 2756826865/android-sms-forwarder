package org.fossify.messages.messaging

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.util.UUID
import org.fossify.messages.testing.ForwardingTestContext
import org.junit.Assert.*
import org.junit.Test

class SmsIngressDeliveryTest {
    @Test
    fun completionDoesNotDiscardAnUnpublishedAlert() {
        val context = ForwardingTestContext.context
        val key = UUID.randomUUID().toString()
        val db = SmsIngressDatabase.get(context).writableDatabase
        try {
            db.execSQL(
                """
                INSERT INTO ingress_journal(event_key,pdu_hash,raw_sub,confirmed_sub,slot,evidence,
                                observed_at,received_at,payload) VALUES(?,?,?,?,?,?,?,?,?)
                """
                    .trimIndent(),
                arrayOf<Any?>(key, "pdu", 7, 7, 0, "test", 10, 10, "pdu-cipher"),
            )
            db.execSQL(
                "INSERT INTO ingress_alert(event_key,payload,created_at,thread_id) VALUES(?,?,?,?)",
                arrayOf<Any?>(key, "alert-cipher", 10, 4),
            )
            SmsIngressJournal(context).complete(key)
            assertTrue(SmsIngressAlerts(context).hasPending())
            db.rawQuery(
                    "SELECT delivered,payload FROM ingress_alert WHERE event_key=?",
                    arrayOf(key),
                )
                .use {
                    assertTrue(it.moveToFirst())
                    assertEquals(0, it.getInt(0))
                    assertEquals("alert-cipher", it.getString(1))
                }
            assertTrue(SmsIngressJournal(context).receipt(key)!!.done)
        } finally {
            db.execSQL("DELETE FROM ingress_alert WHERE event_key=?", arrayOf(key))
            db.execSQL("DELETE FROM ingress_journal WHERE event_key=?", arrayOf(key))
        }
    }

    @Test
    fun versionFiveUpgradePreservesOwnersAndFrozenSnapshots() {
        val prefix = "delivery-upgrade-${UUID.randomUUID()}-"
        val context =
            object : ContextWrapper(ForwardingTestContext.context) {
                override fun getApplicationContext(): Context = this

                override fun getDatabasePath(name: String): File =
                    baseContext.getDatabasePath(prefix + name)

                override fun openOrCreateDatabase(
                    name: String,
                    mode: Int,
                    factory: SQLiteDatabase.CursorFactory?,
                ): SQLiteDatabase =
                    SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name), factory)

                override fun openOrCreateDatabase(
                    name: String,
                    mode: Int,
                    factory: SQLiteDatabase.CursorFactory?,
                    errorHandler: DatabaseErrorHandler?,
                ): SQLiteDatabase =
                    SQLiteDatabase.openOrCreateDatabase(
                        getDatabasePath(name).path,
                        factory,
                        errorHandler,
                    )
            }
        val path = context.getDatabasePath("sms_ingress_identity.db")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL(SmsIngressIdentity.CREATE)
            db.execSQL("INSERT INTO ingress VALUES('original-owner','alias',1,'hash',10,10,7,10)")
            db.execSQL(
                """
                CREATE TABLE ingress_journal(event_key TEXT PRIMARY KEY NOT NULL,pdu_hash TEXT NOT NULL,
                                first_action TEXT,raw_sub INTEGER NOT NULL,confirmed_sub INTEGER NOT NULL,slot INTEGER NOT NULL,
                                evidence TEXT NOT NULL,observed_at INTEGER NOT NULL,received_at INTEGER NOT NULL,payload TEXT,
                                provider_attempted INTEGER NOT NULL DEFAULT 0,provider_id INTEGER NOT NULL DEFAULT -1,
                                done INTEGER NOT NULL DEFAULT 0,reason TEXT NOT NULL DEFAULT '',routing_digest TEXT NOT NULL DEFAULT '',
                                sim_snapshot TEXT,notification_claimed INTEGER NOT NULL DEFAULT 0)
                """
                    .trimIndent()
            )
            db.execSQL(
                "CREATE TABLE ingress_alias(alias TEXT PRIMARY KEY NOT NULL,event_key TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE ingress_execution_sim(request_id TEXT PRIMARY KEY NOT NULL,snapshot TEXT NOT NULL)"
            )
            db.execSQL("INSERT INTO ingress_execution_sim VALUES('request','frozen')")
            db.execSQL(
                "CREATE TABLE ingress_timing(event_key TEXT NOT NULL,phase TEXT NOT NULL,at INTEGER NOT NULL,PRIMARY KEY(event_key,phase))"
            )
            db.version = 5
        }
        val helper = SmsIngressDatabase.get(context)
        try {
            val db = helper.writableDatabase
            assertEquals(6, db.version)
            db.rawQuery(
                    "SELECT snapshot,created_at FROM ingress_execution_sim WHERE request_id='request'",
                    null,
                )
                .use {
                    assertTrue(it.moveToFirst())
                    assertEquals("frozen", it.getString(0))
                    assertEquals(0L, it.getLong(1))
                }
            db.rawQuery("SELECT work_id FROM ingress", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("original-owner", it.getString(0))
            }
            db.execSQL(
                "INSERT INTO ingress_alert(event_key,payload,created_at,thread_id) VALUES('test','cipher',10,4)"
            )
        } finally {
            helper.close()
            path.delete()
        }
    }
}
