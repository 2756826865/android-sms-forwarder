package org.fossify.messages.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.getSmsThreadId
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.insertNewSMS
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.syncThreadToLocal
import org.fossify.messages.extensions.subscriptionManagerCompat
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.ForwardingHistoryStore
import org.fossify.messages.forwarding.ForwardingRuleEngine
import org.fossify.messages.forwarding.ForwardingRulesConfig
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.PushPlusConfig
import org.fossify.messages.forwarding.PushPlusWorker
import org.fossify.messages.autoreply.AutoReplyProcessor
import org.fossify.messages.remote.RemoteSmsCommandProcessor
import org.fossify.messages.helpers.ReceiverUtils.isMessageFilteredOut
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.messaging.SmsRecoveryWorker
import org.fossify.messages.models.Message
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.fossify.messages.helpers.ShadowRepository
import org.fossify.messages.helpers.ShadowHmacHelper
import org.fossify.messages.models.MessageOperation

/**
 * Serial foreground owner for incoming SMS processing. The service remains
 * runnable while the screen is off, verifies provider persistence before
 * notifying or forwarding, and asks Android to redeliver an interrupted intent.
 */
open class IncomingSmsService : Service() {
    private val executor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        executor.execute {
            val wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply { acquire(WAKE_LOCK_TIMEOUT_MS) }
            try {
                processIncoming(intent)
            } catch (error: Throwable) {
                Log.e(TAG, "incoming SMS processing failed", error)
                PushPlusConfig(applicationContext).lastReceiverStatus =
                    "广播已到达，处理失败：${error.message ?: error.javaClass.simpleName}"
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                stopSelfResult(startId)
            }
        }
        return START_REDELIVER_INTENT
    }

    /** Visible for tests / future Context-based callers. */
    internal fun processIncomingForReceiver(intent: Intent) = processIncoming(intent)

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun processIncoming(intent: Intent) {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isEmpty()) {
            Log.w(TAG, "${intent.action} contained no SMS parts")
            return
        }

        val address = parts.last().originatingAddress.orEmpty()
        if (address.isBlank()) {
            Log.w(TAG, "incoming SMS has no originating address")
            return
        }

        val body = buildString { parts.forEach { append(it.messageBody.orEmpty()) } }
        val sentAt = parts.minOfOrNull { it.timestampMillis }
            ?.takeIf { it > 0L }
            ?: System.currentTimeMillis()
        val receivedAt = System.currentTimeMillis()
        val subscriptionId = listOf(
            "subscription",
            "subscription_id",
            "android.telephony.extra.SUBSCRIPTION_INDEX",
            "subscriptionIndex",
            "android.telephony.extra.SUBSCRIPTION_ID",
        ).map { intent.getIntExtra(it, SubscriptionManager.INVALID_SUBSCRIPTION_ID) }
            .firstOrNull { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
        Log.d(TAG, "subscriptionId resolved: $subscriptionId from ${intent.action}")

        // 1A: Initialize Shadow Operation (Fail-open, non-blocking)
        val operationId = java.util.UUID.randomUUID().toString()
        ShadowRepository.recordOperation(
            this,
            MessageOperation(
                operationId = operationId,
                direction = "INCOMING",
                source = "BROADCAST",
                addressHmac = ShadowHmacHelper.calculateHmac(address, normalize = true),
                bodyHmac = ShadowHmacHelper.calculateHmac(body, normalize = true),
                bodyLength = body.length,
                subscriptionId = subscriptionId,
                pduCount = parts.size,
                messageTimestamp = sentAt,
                receivedAt = receivedAt
            )
        )
        ShadowRepository.recordStep(this, operationId, "OPERATION_CREATED", "SUCCESS")
        ShadowRepository.recordStep(this, operationId, "RECEIVER_ARRIVED", "SUCCESS")
        ShadowRepository.recordStep(this, operationId, "PDU_PARSED", "SUCCESS")

        val fingerprint = fingerprint(address, body, sentAt, subscriptionId)
        if (wasPersisted(fingerprint)) {
            Log.i(TAG, "duplicate ${intent.action} ignored after successful persistence")
            ShadowRepository.recordStep(this, operationId, "DUPLICATE_CHECK", "SKIPPED", "Already persisted")
            return
        }

        val receiverStatus = PushPlusConfig(applicationContext)
        receiverStatus.lastReceiverStatus =
            "已收到${intent.action?.substringAfterLast('.').orEmpty()}，正在写入短信库"

        if (isFiltered(address, body)) {
            Log.i(TAG, "incoming SMS from $address was filtered by user rules")
            ShadowRepository.recordStep(this, operationId, "FILTER_MATCHED", "OBSERVED")
            return
        }

        val requestedThreadId = getThreadId(address)
        ShadowRepository.recordStep(this, operationId, "PROVIDER_INSERT", "STARTED")
        val insertedMessageId = try {
            val id = persistWithRetry(
                address = address,
                subject = parts.last().pseudoSubject.orEmpty(),
                body = body,
                receivedAt = receivedAt,
                sentAt = sentAt,
                threadId = requestedThreadId,
                subscriptionId = subscriptionId,
            )
            ShadowRepository.recordStep(this, operationId, "PROVIDER_INSERT", "SUCCEEDED", "msgId=$id")
            id
        } catch (e: Exception) {
            ShadowRepository.recordStep(this, operationId, "PROVIDER_INSERT", "FAILED", e.message)
            throw e
        }

        val resolvedThreadId = getSmsThreadId(insertedMessageId)
            .takeIf { it > 0L }
            ?: requestedThreadId.takeIf { it > 0L }
            ?: error("短信已写入，但无法取得 thread_id")

        ShadowRepository.updateOperation(this, operationId) {
            it.copy(
                providerMessageId = insertedMessageId,
                threadId = resolvedThreadId,
                providerInsertedAt = System.currentTimeMillis()
            )
        }

        // Only a verified provider insert can suppress a second delivery action.
        markPersisted(fingerprint)
        SmsRecoveryWorker.markObserved(applicationContext, receivedAt)
        Log.i(
            TAG,
            "SMS persisted: id=$insertedMessageId thread=$resolvedThreadId action=${intent.action}",
        )

        runCatching {
            updateLocalDatabaseAndNotify(
                address = address,
                body = body,
                date = receivedAt,
                messageId = insertedMessageId,
                threadId = resolvedThreadId,
                subscriptionId = subscriptionId,
                status = parts.last().status,
                operationId = operationId
            )
        }.onFailure { error ->
            Log.e(TAG, "system SMS persisted, local refresh failed", error)
            ShadowRepository.recordStep(this, operationId, "LOCAL_SYNC_OBSERVED", "FAILED", error.message)
            SmsRecoveryWorker.enqueueNow(applicationContext)
        }

        val uniqueId = "sms-$insertedMessageId"
        org.fossify.messages.autofill.SmsAutofillAccessibilityService.onNewVerificationSms(applicationContext, body)

        val rulesConfig = ForwardingRulesConfig(applicationContext)
        val simSlotIndex = runCatching {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                subscriptionManagerCompat().getActiveSubscriptionInfo(subscriptionId)?.simSlotIndex
            } else {
                null
            }
        }.getOrNull()

        val enabledForwardChannels = buildSet {
            if (receiverStatus.enabled) add(ForwardingChannels.PUSHPLUS)
            addAll(MultiForwardConfig(applicationContext).enabledChannelIds())
        }
        val ruleDecision = if (rulesConfig.enabled) {
            ForwardingRuleEngine(rulesConfig.rules).evaluate(
                sender = address,
                body = body,
                subscriptionId = subscriptionId,
                channelCandidates = rulesConfig.channelCandidatesForScope(enabledForwardChannels),
                simSlotIndex = simSlotIndex,
            )
        } else {
            null
        }
        if (ruleDecision?.blockedChannels?.isNotEmpty() == true) {
            val decisionTime = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val blockedNames = ruleDecision.blockedChannels
                .map(ForwardingChannels::displayName)
                .joinToString("、")
            rulesConfig.lastDecision = "$decisionTime · $address · $blockedNames · ${ruleDecision.reason}"
        }
        val allowedForwardChannels = ruleDecision?.allowedChannels

        val remoteCommandAllowed = !rulesConfig.affectsRemoteCommands() ||
            !rulesConfig.enabled ||
            rulesConfig.rules.none { it.enabled } ||
            ruleDecision?.matchedRules?.isNotEmpty() == true
        
        ShadowRepository.recordStep(this, operationId, "REMOTE_COMMAND_OBSERVED", "STARTED")
        val remoteCommandConsumed = RemoteSmsCommandProcessor.tryConsume(
            context = this,
            sender = address,
            body = body,
            subscriptionId = subscriptionId,
            messageTimestamp = sentAt,
            messageId = insertedMessageId,
            allowExecution = remoteCommandAllowed,
        )
        if (remoteCommandConsumed) {
            ShadowRepository.recordStep(this, operationId, "REMOTE_COMMAND_OBSERVED", "SUCCESS", "Consumed")
        }

        if (!remoteCommandConsumed && ruleDecision != null) {
            val history = ForwardingHistoryStore(applicationContext)
            ruleDecision.blockedChannels
                .intersect(enabledForwardChannels)
                .forEach { channel ->
                    history.registerSkipped(
                        workId = uniqueId,
                        channel = channel,
                        sender = address,
                        body = body,
                        receivedAt = receivedAt,
                        subscriptionId = subscriptionId,
                        detail = "转发规则未允许：${ruleDecision.reason}",
                    )
                }
        }

        ShadowRepository.recordStep(this, operationId, "FORWARDING_OBSERVED", "STARTED")
        if (!remoteCommandConsumed && MultiForwardConfig(this).anyEnabled() || receiverStatus.enabled) {
            val multiConfig = MultiForwardConfig(this)
            val channels = buildMultiChannelAllowedChannels(
                rulesConfig = rulesConfig,
                allowedForwardChannels = allowedForwardChannels,
                multiConfig = multiConfig,
                pushPlusEnabled = receiverStatus.enabled
            )
            val activeChannels = channels ?: (multiConfig.enabledChannelIds() + if (receiverStatus.enabled) setOf(ForwardingChannels.PUSHPLUS) else emptySet())
            activeChannels.forEach { channel ->
                ShadowRepository.recordDelivery(this, operationId, channel, "QUEUED")
            }
            
            MultiChannelForwardWorker.enqueue(
                context = this,
                sender = address,
                body = body,
                receivedAt = receivedAt,
                subscriptionId = subscriptionId,
                uniqueId = uniqueId,
                targetChannel = "",
                allowedChannels = channels,
                operationId = operationId
            )
        }

        // Automatic SMS reply engine evaluation
        if (!remoteCommandConsumed) {
            runCatching {
                val autoReplyResult = AutoReplyProcessor.processIncoming(
                    context = this,
                    senderNumber = address,
                    messageBody = body,
                    incomingSubId = subscriptionId
                )
                if (autoReplyResult is AutoReplyProcessor.Result.Executed) {
                    ShadowRepository.recordStep(this, operationId, "AUTO_REPLY", "SUCCESS", "Rule: ${autoReplyResult.ruleName}")
                }
            }.onFailure { e ->
                Log.e(TAG, "AutoReply evaluation error", e)
            }
        }

        receiverStatus.lastReceiverStatus =
            "已接收并写入短信库，短信ID：$insertedMessageId，发送方：$address"
            
        ShadowRepository.recordStep(this, operationId, "LEGACY_PIPELINE_RETURNED", "SUCCESS")
    }

    private fun buildMultiChannelAllowedChannels(
        rulesConfig: ForwardingRulesConfig,
        allowedForwardChannels: Set<String>?,
        multiConfig: MultiForwardConfig,
        pushPlusEnabled: Boolean
    ): Set<String>? {
        if (!rulesConfig.enabled) return null
        var channels = allowedForwardChannels ?: emptySet()
        if (rulesConfig.scope == ForwardingRulesConfig.SCOPE_FORWARDING_ONLY && multiConfig.smsDirectEnabled) {
            channels = channels + ForwardingChannels.SMS_DIRECT
        }
        return channels
    }

    private fun persistWithRetry(
        address: String,
        subject: String,
        body: String,
        receivedAt: Long,
        sentAt: Long,
        threadId: Long,
        subscriptionId: Int,
    ): Long {
        var lastError: Throwable? = null
        repeat(PROVIDER_ATTEMPTS) { attempt ->
            try {
                return insertNewSMS(
                    address = address,
                    subject = subject,
                    body = body,
                    date = receivedAt,
                    dateSent = sentAt,
                    read = 0,
                    threadId = threadId,
                    type = Telephony.Sms.MESSAGE_TYPE_INBOX,
                    subscriptionId = subscriptionId,
                )
            } catch (error: Throwable) {
                lastError = error
                Log.e(TAG, "SMS provider insert attempt ${attempt + 1} failed", error)
                if (attempt + 1 < PROVIDER_ATTEMPTS) Thread.sleep(PROVIDER_RETRY_DELAY_MS)
            }
        }
        throw lastError ?: IllegalStateException("短信 Provider 写入失败")
    }

    private fun isFiltered(address: String, body: String): Boolean {
        val isWhitelisted = config.isNumberWhitelisted(address)
        if (!isWhitelisted && config.isNumberBlacklisted(address)) return true
        if (!isWhitelisted && isMessageFilteredOut(this, body)) return true
        if (!isWhitelisted && isNumberBlocked(address)) return true
        if (!isWhitelisted && baseConfig.blockUnknownNumbers) {
            val result = runCatching {
                getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use { privateCursor ->
                    SimpleContactsHelper(this).existsSync(address, privateCursor)
                }
            }.getOrNull()
            if (result == ContactLookupResult.NotFound) return true
        }
        return false
    }

    private fun updateLocalDatabaseAndNotify(
        address: String,
        body: String,
        date: Long,
        messageId: Long,
        threadId: Long,
        subscriptionId: Int,
        status: Int,
        operationId: String? = null
    ) {
        val contact = getNameAndPhotoFromPhoneNumber(address)
        val photoUri = contact.photoUri.orEmpty()
        val senderName = runCatching {
            getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
                getNameFromAddress(address, it)
            }
        }.getOrDefault(contact.name.ifBlank { address })
        val participant = SimpleContact(
            rawId = 0,
            contactId = 0,
            name = senderName,
            photoUri = photoUri,
            phoneNumbers = arrayListOf(
                PhoneNumber(value = address, type = 0, label = "", normalizedNumber = address),
            ),
            birthdays = ArrayList(),
            anniversaries = ArrayList(),
        )
        val message = Message(
            id = messageId,
            body = body,
            type = Telephony.Sms.MESSAGE_TYPE_INBOX,
            status = status,
            participants = arrayListOf(participant),
            date = (date / 1000).toInt(),
            read = false,
            threadId = threadId,
            isMMS = false,
            attachment = null,
            senderPhoneNumber = address,
            senderName = senderName,
            senderPhotoUri = photoUri,
            subscriptionId = subscriptionId,
        )

        messagesDB.insertOrUpdate(message)
        syncThreadToLocal(threadId)
        refreshMessages()
        refreshConversations()
        
        operationId?.let { 
            ShadowRepository.recordStep(this@IncomingSmsService, it, "LOCAL_SYNC_OBSERVED", "SUCCESS")
        }
        
        showReceivedMessageNotification(
            messageId = messageId,
            address = address,
            senderName = senderName,
            body = body,
            threadId = threadId,
            bitmap = getNotificationBitmap(photoUri),
        )
        
        operationId?.let { 
            ShadowRepository.recordStep(this@IncomingSmsService, it, "NOTIFICATION_OBSERVED", "SUCCESS")
        }
    }

    private fun wasPersisted(fingerprint: String): Boolean = synchronized(duplicateLock) {
        val prefs = getSharedPreferences(DUPLICATE_PREFS, Context.MODE_PRIVATE)
        val rawEntries = decodePersistedFingerprints(
            prefs.getString(KEY_RECENT_FINGERPRINTS, "[]").orEmpty(),
        )
        val now = System.currentTimeMillis()
        val entries = rawEntries.filter { now - it.second in 0L..DUPLICATE_WINDOW_MS }
        if (entries.size != rawEntries.size) persistFingerprints(prefs, entries)
        prefs.getString(KEY_LAST_FINGERPRINT, null) == fingerprint || entries.any { it.first == fingerprint }
    }

    private fun markPersisted(fingerprint: String) = synchronized(duplicateLock) {
        val prefs = getSharedPreferences(DUPLICATE_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val entries = decodePersistedFingerprints(
            prefs.getString(KEY_RECENT_FINGERPRINTS, "[]").orEmpty(),
        ).filter { now - it.second in 0L..DUPLICATE_WINDOW_MS }
            .filterNot { it.first == fingerprint }
            .plus(fingerprint to now)
            .takeLast(MAX_RECENT_FINGERPRINTS)
        persistFingerprints(prefs, entries)
        prefs.edit().remove(KEY_LAST_FINGERPRINT).commit()
    }

    private fun decodePersistedFingerprints(value: String): List<Pair<String, Long>> = runCatching {
        val array = JSONArray(value)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val entry = item.optString("value")
                val timestamp = item.optLong("timestamp")
                if (entry.isNotBlank() && timestamp > 0L) add(entry to timestamp)
            }
        }
    }.getOrDefault(emptyList())

    private fun persistFingerprints(
        prefs: android.content.SharedPreferences,
        entries: List<Pair<String, Long>>,
    ) {
        val encoded = JSONArray().apply {
            entries.forEach { (value, timestamp) ->
                put(JSONObject().put("value", value).put("timestamp", timestamp))
            }
        }.toString()
        prefs.edit().putString(KEY_RECENT_FINGERPRINTS, encoded).commit()
    }

    private fun fingerprint(
        address: String,
        body: String,
        sentAt: Long,
        subscriptionId: Int,
    ): String {
        val raw = "$address\u0000$body\u0000$sentAt\u0000$subscriptionId"
        return MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.keep_alive_channel_name),
                    NotificationManager.IMPORTANCE_MIN,
                ).apply {
                    setShowBadge(false)
                    enableVibration(false)
                    setSound(null, null)
                },
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_messenger)
            .setContentTitle(getString(R.string.keep_alive_notification_title))
            .setContentText(getString(R.string.incoming_sms_processing))
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    NOTIFICATION_ID,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val TAG = "IncomingSmsService"
        private const val CHANNEL_ID = "incoming_sms_processing"
        private const val NOTIFICATION_ID = 19082
        private const val WAKE_LOCK_TAG = "smsforwarder:incoming-service"
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L
        private const val PROVIDER_ATTEMPTS = 3
        private const val PROVIDER_RETRY_DELAY_MS = 500L
        private const val DUPLICATE_PREFS = "sms_receiver_state"
        private const val KEY_LAST_FINGERPRINT = "last_fingerprint"
        private const val KEY_RECENT_FINGERPRINTS = "recent_fingerprints"
        private const val MAX_RECENT_FINGERPRINTS = 100
        private const val DUPLICATE_WINDOW_MS = 24 * 60 * 60 * 1000L
        private val duplicateLock = Any()
        private val receiveExecutor = Executors.newSingleThreadExecutor()
        private const val RECEIVER_HANDOFF_HOLD_MS = 20_000L

        fun enqueue(context: Context, source: Intent) {
            val serviceIntent = Intent(context, IncomingSmsService::class.java).apply {
                action = source.action
                replaceExtras(source)
            }
            ContextCompat.startForegroundService(context, serviceIntent)
        }

        /**
         * Process under the receiver goAsync budget. Prefer a real foreground service
         * (works while SmsKeepAliveService keeps the process warm). Avoid manually
         * constructing Service instances — that path silently fails on HyperOS.
         */
        fun processFromReceiver(context: Context, source: Intent, onComplete: () -> Unit) {
            val appContext = context.applicationContext
            val workIntent = Intent(appContext, IncomingSmsService::class.java).apply {
                action = source.action
                replaceExtras(source)
            }
            receiveExecutor.execute {
                try {
                    ContextCompat.startForegroundService(appContext, workIntent)
                    Log.i(TAG, "SMS_DELIVER handed to IncomingSmsService FGS")
                    // Keep the receiver wake/goAsync until the service has had time to
                    // startForeground + persist. HyperOS otherwise freezes us mid-handoff.
                    Thread.sleep(RECEIVER_HANDOFF_HOLD_MS)
                } catch (error: Throwable) {
                    Log.e(TAG, "FGS handoff failed for SMS_DELIVER", error)
                    PushPlusConfig(appContext).lastReceiverStatus =
                        "广播已到达，前台服务启动失败：${error.message ?: error.javaClass.simpleName}"
                } finally {
                    onComplete()
                }
            }
        }
    }
}
