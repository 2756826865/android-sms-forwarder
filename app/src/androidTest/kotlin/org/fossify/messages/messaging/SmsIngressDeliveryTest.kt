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
    @Test fun encryptedAdmissionRecordsMeasuredStorageLatency() {
        val context = object : ContextWrapper(ForwardingTestContext.context) {
            private val folder = File(baseContext.cacheDir, "spool-benchmark-${UUID.randomUUID()}").apply { mkdirs() }
            override fun getNoBackupFilesDir(): File = folder
        }
        val samples = mutableListOf<Long>()
        repeat(20) {
            val intent = android.content.Intent("test-only")
                .putExtra(SmsIngressSpool.EXTRA_ID, UUID.randomUUID().toString())
                .putExtra("pdus", arrayOf(byteArrayOf(1,2,3)))
            val spool = SmsIngressSpool(context)
            val start = android.os.SystemClock.elapsedRealtime()
            spool.save(intent)
            samples.add(android.os.SystemClock.elapsedRealtime() - start)
            assertTrue(spool.hasPending())
            spool.remove(intent)
            assertFalse(spool.hasPending())
        }
        val sorted = samples.sorted()
        println("Encrypted admission API=${android.os.Build.VERSION.SDK_INT}: cold=${samples.first()}ms p50=${sorted[9]}ms p95=${sorted[18]}ms max=${sorted.last()}ms")
    }

    @Test
    fun routingDigestDoesNotMigrateLegacyTokenOrDeleteUnreadableCredentials() {
        val prefix = "routing-pure-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(ForwardingTestContext.context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                baseContext.getSharedPreferences(prefix + name, mode)
        }
        val legacy = context.getSharedPreferences("pushplus_forwarding", Context.MODE_PRIVATE)
        val multi = context.getSharedPreferences("multi_channel_forwarding", Context.MODE_PRIVATE)
        try {
            org.fossify.messages.forwarding.PushPlusConfig(context).saveToken("same-routing-token")
            check(legacy.getString("token", "").orEmpty().isNotBlank())
            multi.edit().clear().commit() // Real legacy-only migration state.
            val before = legacy.all.toMap()
            val routing = SmsIngressRouting(context)
            val first = routing.digest()
            assertEquals(first, routing.digest())
            assertEquals(before, legacy.all)
            assertTrue(multi.all.isEmpty())

            legacy.edit().putString("token", "unreadable-ciphertext").commit()
            val invalid = legacy.all.toMap()
            try {
                routing.digest()
                fail("Unavailable legacy credentials must not produce a new routing identity")
            } catch (_: IllegalStateException) { }
            assertEquals(invalid, legacy.all)
            assertTrue(multi.all.isEmpty())
        } finally {
            legacy.edit().clear().commit()
            multi.edit().clear().commit()
        }
    }

    @Test
    fun unreadableLegacyTokenStillStagesSmsAndNeverAuthorizesForwarding() {
        val prefix = "routing-intake-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(ForwardingTestContext.context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                baseContext.getSharedPreferences(prefix + name, mode)
        }
        val legacy = context.getSharedPreferences("pushplus_forwarding", Context.MODE_PRIVATE)
        val db = SmsIngressDatabase.get(context).writableDatabase
        var eventKey: String? = null
        try {
            legacy.edit().putString("token", "unreadable-ciphertext").commit()
            // 3GPP SMS-DELIVER PDU: +1234567890, text "hello".
            val bytes = "00040A91214365870900006201101111110005E8329BFD06"
                .chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            assertNotNull(android.telephony.SmsMessage.createFromPdu(bytes, "3gpp"))
            val source = android.content.Intent(android.provider.Telephony.Sms.Intents.SMS_DELIVER_ACTION)
                .putExtra("pdus", arrayOf(bytes))
                .putExtra("format", "3gpp")
            val staged = SmsIngressJournal(context).stage(source)
            eventKey = staged.getStringExtra(SmsIngressJournal.EXTRA_EVENT)
            assertNotNull(eventKey)
            val key = eventKey!!
            val receipt = SmsIngressJournal(context).receipt(key)
            assertNotNull(receipt)
            db.rawQuery(
                "SELECT routing_digest,payload FROM ingress_journal WHERE event_key=?", arrayOf(key),
            ).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals(SmsIngressRouting.UNAVAILABLE_SNAPSHOT, c.getString(0))
                assertTrue(c.getString(1).isNotBlank())
            }
            assertFalse(SmsIngressRouting(context).allowed(key))
            db.rawQuery("SELECT reason FROM ingress_journal WHERE event_key=?", arrayOf(key)).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("configuration_unavailable", c.getString(0))
            }
            // Restoring a secret must never re-route the old receipt to a new destination.
            legacy.edit().remove("token").commit()
            assertFalse(SmsIngressRouting(context).allowed(key))
        } finally {
            eventKey?.let { key ->
                db.execSQL("DELETE FROM ingress_alias WHERE event_key=?", arrayOf(key))
                db.execSQL("DELETE FROM ingress_timing WHERE event_key=?", arrayOf(key))
                db.execSQL("DELETE FROM ingress_journal WHERE event_key=?", arrayOf(key))
            }
            legacy.edit().clear().commit()
        }
    }

    @Test
    fun routingFailureDoesNotOverwriteAnUncertainProviderWrite() {
        val prefix = "routing-provider-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(ForwardingTestContext.context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                baseContext.getSharedPreferences(prefix + name, mode)
        }
        val legacy = context.getSharedPreferences("pushplus_forwarding", Context.MODE_PRIVATE)
        val key = UUID.randomUUID().toString()
        val db = SmsIngressDatabase.get(context).writableDatabase
        try {
            legacy.edit().putString("token", "unreadable-ciphertext").commit()
            db.execSQL(
                """INSERT INTO ingress_journal(event_key,pdu_hash,raw_sub,confirmed_sub,slot,
                     evidence,observed_at,received_at,payload,routing_digest,reason)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
                arrayOf<Any?>(key, "pdu", -1, -1, -1, "test", 10, 10,
                    "cipher", SmsIngressRouting.UNAVAILABLE_SNAPSHOT, "provider_result_uncertain"),
            )
            assertFalse(SmsIngressRouting(context).allowed(key))
            db.rawQuery("SELECT reason FROM ingress_journal WHERE event_key=?", arrayOf(key)).use { c ->
                assertTrue(c.moveToFirst())
                assertEquals("provider_result_uncertain", c.getString(0))
            }
        } finally {
            db.execSQL("DELETE FROM ingress_journal WHERE event_key=?", arrayOf(key))
            legacy.edit().clear().commit()
        }
    }

    @Test
    fun reencryptingTheSameTokenKeepsTheRoutingDigest() {
        val prefix = "routing-semantic-${UUID.randomUUID()}-"
        val context = object : ContextWrapper(ForwardingTestContext.context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) =
                baseContext.getSharedPreferences(prefix + name, mode)
        }
        val config = org.fossify.messages.forwarding.PushPlusConfig(context)
        try {
            config.saveToken("same-routing-token")
            val first = SmsIngressRouting(context).digest()
            config.saveToken("same-routing-token")
            assertEquals(first, SmsIngressRouting(context).digest())
        } finally {
            context.getSharedPreferences("pushplus_forwarding", Context.MODE_PRIVATE).edit().clear().commit()
            context.getSharedPreferences("multi_channel_forwarding", Context.MODE_PRIVATE).edit().clear().commit()
        }
    }

}
