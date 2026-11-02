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
    fun unifiedBackgroundSnapshotCountsActualReadinessAndHidesSensitiveFields() {
        val context = ForwardingTestContext.context
        val config = org.fossify.messages.forwarding.MultiForwardConfig(context)
        val old = config.keepAliveServiceEnabled
        try {
            config.keepAliveServiceEnabled = false
            val source =
                org.fossify.messages.remote.repository.RemoteSourceInstance(
                    id = "test-source",
                    name = "private-source-name",
                    type = org.fossify.messages.remote.repository.RemoteSourceType.TELEGRAM,
                    connectionState =
                        org.fossify.messages.remote.repository.RemoteSourceConnectionState.READY,
                    configJson = """{"botToken":"secret-token"}""",
                )
            val storedReady =
                org.fossify.messages.services.SmsKeepAliveService.snapshot(
                    context,
                    listOf(source),
                    emptySet(),
                )
            assertTrue(storedReady.required)
            assertTrue(storedReady.text.contains("0/1"))
            assertTrue(storedReady.text.contains("待恢复"))
            assertFalse(storedReady.text.contains("secret-token"))
            assertFalse(storedReady.text.contains("private-source-name"))
            val active =
                org.fossify.messages.services.SmsKeepAliveService.snapshot(
                    context,
                    listOf(source),
                    setOf(source.id),
                )
            assertTrue(active.text.contains("1/1"))
            assertFalse(
                org.fossify.messages.services.SmsKeepAliveService.snapshot(
                        context,
                        listOf(source.copy(enabled = false)),
                    )
                    .required
            )
            assertFalse(
                org.fossify.messages.services.SmsKeepAliveService.snapshot(
                        context,
                        listOf(source.copy(configJson = "{}")),
                    )
                    .required
            )
            config.keepAliveServiceEnabled = true
            assertTrue(
                org.fossify.messages.services.SmsKeepAliveService.snapshot(context, emptyList())
                    .required
            )
        } finally {
            config.keepAliveServiceEnabled = old
        }
    }

    @Test
    fun unifiedForegroundRetiresAllLegacyOwnersWithoutTouchingSmsProcessingNotification() {
        val instrumentation =
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val repo =
            org.fossify.messages.remote.repository.RemoteSourceRepository.getInstance(context)
        org.junit.Assume.assumeTrue(
            repo.getEnabledSources().none {
                it.type != org.fossify.messages.remote.repository.RemoteSourceType.SMS &&
                    it.hasValidCredentials()
            }
        )
        val config = org.fossify.messages.forwarding.MultiForwardConfig(context)
        val old = config.keepAliveServiceEnabled
        val automation = instrumentation.uiAutomation
        val permission = android.Manifest.permission.POST_NOTIFICATIONS
        val hadPermission =
            android.os.Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(permission) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
        automation.adoptShellPermissionIdentity()
        if (android.os.Build.VERSION.SDK_INT >= 33 && !hadPermission)
            automation.grantRuntimePermission(context.packageName, permission)
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        val activityManager = context.getSystemService(android.app.ActivityManager::class.java)
        fun awaitState(check: () -> Boolean) {
            val end = android.os.SystemClock.elapsedRealtime() + 20_000L
            while (!check() && android.os.SystemClock.elapsedRealtime() < end) android.os
                .SystemClock
                .sleep(50)
            assertTrue("unified foreground state timed out: " +
                org.fossify.messages.services.SmsKeepAliveService.observedStatus(context) +
                "; active notifications=" + manager.activeNotifications.map { it.id } +
                "; services=" + activityManager.getRunningServices(100).map { it.service.className }, check())
        }
        val legacy =
            listOf(
                org.fossify.messages.services.DingTalkRemoteControlService::class.java,
                org.fossify.messages.services.FeishuRemoteControlService::class.java,
                org.fossify.messages.services.EmailRemoteControlService::class.java,
                org.fossify.messages.services.TelegramRemoteControlService::class.java,
                org.fossify.messages.services.WebSocketRemoteControlService::class.java,
                org.fossify.messages.services.WeComRemoteControlService::class.java,
            )
        val localSource =
            org.fossify.messages.remote.repository.RemoteSourceInstance(
                id = "unified-service-regression-${java.util.UUID.randomUUID()}",
                name = "local regression",
                type = org.fossify.messages.remote.repository.RemoteSourceType.WEBSOCKET,
                configJson = """{"url":"ws://127.0.0.1:9"}""",
            )
        try {
            config.keepAliveServiceEnabled = true
            org.fossify.messages.services.SmsKeepAliveService.ensureStarted(context)
            awaitState { manager.activeNotifications.any { it.id == 19081 } }
            // The short-lived SMS service owns 19082. Migration must never cancel that ID.
            manager.createNotificationChannel(
                android.app.NotificationChannel("migration-test", "test", 2)
            )
            manager.notify(
                19082,
                androidx.core.app.NotificationCompat.Builder(context, "migration-test")
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("SMS processing sentinel")
                    .build(),
            )
            legacy.forEach { component ->
                androidx.core.content.ContextCompat.startForegroundService(
                    context,
                    android.content.Intent(context, component),
                )
                android.os.SystemClock.sleep(200)
                awaitState {
                    activityManager.getRunningServices(100).none {
                        it.service.className in legacy.map { cls -> cls.name }
                    } &&
                        manager.activeNotifications.none {
                            it.id in
                                org.fossify.messages.services.SmsKeepAliveService
                                    .LEGACY_NOTIFICATION_IDS
                        }
                }
                assertTrue(manager.activeNotifications.any { it.id == 19081 })
                assertTrue(manager.activeNotifications.any { it.id == 19082 })
            }
            // A source's legacy stop API must not stop the enabled SMS guard.
            org.fossify.messages.services.FeishuRemoteControlService.stop(context)
            android.os.SystemClock.sleep(200)
            assertTrue(manager.activeNotifications.any { it.id == 19081 })
            repo.saveSource(localSource)
            config.keepAliveServiceEnabled = false
            org.fossify.messages.services.SmsKeepAliveService.stop(context)
            android.os.SystemClock.sleep(200)
            assertTrue(
                "remote listener must retain the foreground owner",
                manager.activeNotifications.any { it.id == 19081 },
            )
            org.fossify.messages.services.FeishuRemoteControlService.stop(context)
            android.os.SystemClock.sleep(200)
            assertTrue(manager.activeNotifications.any { it.id == 19081 })
            repo.deleteSource(localSource.id)
            org.fossify.messages.services.SmsKeepAliveService.stop(context)
            awaitState { manager.activeNotifications.none { it.id == 19081 } }
        } finally {
            repo.deleteSource(localSource.id)
            manager.cancel(19082)
            config.keepAliveServiceEnabled = old
            org.fossify.messages.services.SmsKeepAliveService.ensureStarted(context)
            // As with a grant-permission rule, keep the test grant: revoking it kills this runner.
            automation.dropShellPermissionIdentity()
        }
    }

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
            assertEquals(7, db.version)
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
    fun reviewOnlyRoutingFailureIsNotScheduledForEndlessRecovery() {
        val context = ForwardingTestContext.context
        val key = UUID.randomUUID().toString()
        val db = SmsIngressDatabase.get(context).writableDatabase
        try {
            db.execSQL(
                """INSERT INTO ingress_journal(event_key,pdu_hash,raw_sub,confirmed_sub,slot,
                       evidence,observed_at,received_at,payload,routing_digest,reason)
                   VALUES(?,?,?,?,?,?,?,?,?,?,?)""".trimIndent(),
                arrayOf<Any?>(
                    key, "pdu", -1, -1, -1, "test", 10, 10,
                    "cipher", SmsIngressRouting.UNAVAILABLE_SNAPSHOT,
                    "configuration_unavailable",
                ),
            )
            val recovery = SmsIngressRecovery(context)
            assertFalse(recovery.pending().any {
                it.getStringExtra(SmsIngressJournal.EXTRA_EVENT) == key
            })
            assertTrue(SmsIngressReview(context).items().any {
                it.key == key && it.reason == "configuration_unavailable"
            })
        } finally {
            db.execSQL("DELETE FROM ingress_journal WHERE event_key=?", arrayOf(key))
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

    @Test
    fun encryptedHandoffFallbackMustCommitBeforeBroadcastFinish() {
        val actions = mutableListOf<String>()
        val primary = DurableIngressHandoff.persistWithFallback(
            primary = { actions.add("spool_failed"); error("storage unavailable") },
            fallback = { actions.add("journal_committed") },
        )
        assertFalse(primary)
        var finished = false
        val accepted = DurableIngressHandoff.dispatch(
            executor = java.util.concurrent.Executor { it.run() },
            persist = { actions.add("persist_confirmed") },
            finish = { actions.add("finished"); finished = true },
            process = { assertTrue(finished); actions.add("process") },
        )
        assertTrue(accepted)
        assertEquals(
            listOf("spool_failed", "journal_committed", "persist_confirmed", "finished", "process"),
            actions,
        )
    }

    @Test
    fun rejectedExecutorStillKeepsCommittedEncryptedReceipt() {
        val actions = mutableListOf<String>()
        val accepted = DurableIngressHandoff.dispatch(
            executor = java.util.concurrent.Executor {
                throw java.util.concurrent.RejectedExecutionException("saturated")
            },
            persist = { actions.add("committed") },
            finish = { actions.add("finish") },
            process = { actions.add("process") },
        )
        assertFalse(accepted)
        assertEquals(listOf("committed", "finish"), actions)
    }

    @Test
    fun aFailedSpoolAndJournalCannotBeAcknowledgedAsDurable() {
        var fallbackAttempted = false
        try {
            DurableIngressHandoff.persistWithFallback(
                primary = { error("disk") },
                fallback = { fallbackAttempted = true; error("keystore") },
            )
            fail("admission must fail if neither durable store committed")
        } catch (_: IllegalStateException) {
            assertTrue(fallbackAttempted)
        }
    }

    @Test
    fun forwardingProofSurvivesHistoryCompactionAndRejectsPrefixCollisions() {
        val store = SmsForwardingProof(ForwardingTestContext.context)
        val root = "sms-" + (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE)
        val other = root + "1"
        store.record(root + "-target")
        assertTrue(store.hasAny(root))
        assertFalse(store.hasAny(other))
        assertFalse(store.hasAny(root.dropLast(1)))
        val db = SmsIngressDatabase.get(ForwardingTestContext.context).writableDatabase
        db.execSQL("DELETE FROM ingress_forwarding_proof WHERE work_id=?", arrayOf(root + "-target"))
    }

    @Test
    fun queuedHistoryIsNotAcceptedAsDurableForwardingProof() {
        val history = org.fossify.messages.forwarding.ForwardingHistoryStore(
            ForwardingTestContext.context,
        )
        val owner = "sms-" + (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE)
        val proof = SmsForwardingProof(ForwardingTestContext.context)
        val recordId = history.registerQueued(
            workId = owner,
            channel = "system",
            sender = "10086",
            body = "test",
            receivedAt = System.currentTimeMillis(),
            subscriptionId = -1,
            isTest = false,
        )
        try {
            assertFalse(proof.hasAny(owner))
            history.markEnqueueFailed(recordId)
            assertFalse(proof.hasAny(owner))
            // A directly observed, explicit skip is safe even without a network request.
            history.markSkipped(recordId, "filtered")
            assertTrue(proof.hasAny(owner))
        } finally {
            val db = SmsIngressDatabase.get(ForwardingTestContext.context).writableDatabase
            db.execSQL("DELETE FROM ingress_forwarding_proof WHERE work_id=?", arrayOf(owner))
        }
    }

    @Test
    fun historicalProofBackfillRejectsUnconfirmedQueuedAndUnknown() {
        fun record(status: String, attempts: Int) =
            org.fossify.messages.forwarding.ForwardingHistoryRecord(
                "id", "sms-991", "system", "10086", "test", 10, -1,
                status, attempts, "", 10, false,
            )
        val store = org.fossify.messages.forwarding.ForwardingHistoryStore
        assertFalse(SmsForwardingProof.hasProvenOutcome(record(store.STATUS_QUEUED, 0)))
        assertFalse(SmsForwardingProof.hasProvenOutcome(record(store.STATUS_UNKNOWN, 0)))
        assertFalse(SmsForwardingProof.hasProvenOutcome(record(store.STATUS_FAILED, 0)))
        assertTrue(SmsForwardingProof.hasProvenOutcome(record(store.STATUS_SKIPPED, 0)))
        assertTrue(SmsForwardingProof.hasProvenOutcome(record(store.STATUS_SUCCESS, 0)))
        assertTrue(SmsForwardingProof.hasProvenOutcome(record(store.STATUS_RUNNING, 1)))
    }

    @Test
    fun contactEnrichmentHasBoundedRetries() {
        assertTrue(SmsContactRetryPolicy.shouldRetry(0))
        assertTrue(SmsContactRetryPolicy.shouldRetry(2))
        assertFalse(SmsContactRetryPolicy.shouldRetry(3))
        assertFalse(SmsContactRetryPolicy.shouldRetry(100))
    }

    @Test
    fun roomDisplayUpdatesInvalidateObserversWithoutReplacingReceiptState() {
        val db = androidx.room.Room.inMemoryDatabaseBuilder(
            ForwardingTestContext.context,
            org.fossify.messages.databases.MessagesDatabase::class.java,
        ).allowMainThreadQueries().build()
        val changed = java.util.concurrent.CountDownLatch(1)
        val observer = object : androidx.room.InvalidationTracker.Observer("conversations") {
            override fun onInvalidated(tables: Set<String>) { changed.countDown() }
        }
        try {
            // Open before registration so invalidation triggers are installed for the transaction.
            db.openHelper.writableDatabase
            db.invalidationTracker.addObserver(observer)
            db.runInTransaction {
                db.openHelper.writableDatabase.execSQL("INSERT INTO conversations(thread_id,snippet,date,read,title,photo_uri,is_group_conversation,phone_number,is_scheduled,uses_custom_title,archived,unread_count) VALUES(42,'latest',123,0,'123','',0,'123',0,0,0,2)")
            }
            assertTrue("Room did not publish the committed row", changed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            db.runInTransaction {
                db.openHelper.writableDatabase.execSQL("UPDATE conversations SET title='contact',photo_uri='photo' WHERE thread_id=42")
            }
            db.openHelper.writableDatabase.query("SELECT snippet,read,unread_count FROM conversations WHERE thread_id=42").use {
                assertTrue(it.moveToFirst())
                assertEquals("latest", it.getString(0))
                assertEquals(0, it.getInt(1))
                assertEquals(2, it.getInt(2))
            }
        } finally {
            db.invalidationTracker.removeObserver(observer)
            db.close()
        }
    }

    @Test
    fun liveIslandProvidersDoNotExpireTheWholeSmsNotification() {
        val context = ForwardingTestContext.context
        val providers = listOf(
            org.fossify.messages.helpers.liveisland.XiaomiHyperIslandProvider(),
            org.fossify.messages.helpers.liveisland.OppoFluidCloudProvider(),
            org.fossify.messages.helpers.liveisland.AndroidLiveUpdateProvider(),
        )
        for (provider in providers) {
            val builder = androidx.core.app.NotificationCompat.Builder(context, "island-timeout-test")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
            provider.applyToBuilder(context, builder,
                org.fossify.messages.helpers.liveisland.LiveIslandMessage(77,"sender","message","ticker",null))
            val notification = builder.build()
            assertEquals(provider.kind.toString(), 0L, notification.timeoutAfter)
            if (provider is org.fossify.messages.helpers.liveisland.XiaomiHyperIslandProvider) {
                val extra = notification.extras.getString("miui.focus.param")
                assertNotNull(extra)
                val island = org.json.JSONObject(extra!!).getJSONObject("param_v2").getJSONObject("param_island")
                assertEquals(8, island.getInt("islandTimeout"))
            }
        }
    }

    @Test
    fun bothSettingsConsumersSeeTheSameVerificationPopupPreference() {
        val context = ForwardingTestContext.context
        val first = org.fossify.messages.helpers.Config.newInstance(context)
        val second = org.fossify.messages.helpers.Config.newInstance(context)
        val original = first.showVerificationCodePopup
        try {
            first.showVerificationCodePopup = false
            assertFalse(second.showVerificationCodePopup)
            second.showVerificationCodePopup = true
            assertTrue(first.showVerificationCodePopup)
        } finally {
            first.showVerificationCodePopup = original
        }
    }

}
