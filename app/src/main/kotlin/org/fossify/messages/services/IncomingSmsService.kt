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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.security.MessageDigest
import java.util.concurrent.Executors
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.isNumberBlocked
import org.fossify.commons.helpers.ContactLookupResult
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity
import org.fossify.messages.autoreply.AutoReplyProcessor
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber
import org.fossify.messages.extensions.getNameFromAddress
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.getOptionalPrivateContactsCursor as getMyContactsCursor
import org.fossify.messages.extensions.getSmsThreadId
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.syncThreadToLocal
import org.fossify.messages.forwarding.ForwardingChannelInstance
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.ForwardingHistoryStore
import org.fossify.messages.forwarding.ForwardingMessageFormatter
import org.fossify.messages.forwarding.ForwardingRuleDecision
import org.fossify.messages.forwarding.ForwardingRuleEngine
import org.fossify.messages.forwarding.ForwardingRulesConfig
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.PushPlusConfig
import org.fossify.messages.helpers.ReceiverUtils.isMessageFilteredOut
import org.fossify.messages.helpers.ShadowHmacHelper
import org.fossify.messages.helpers.ShadowRepository
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.messaging.IncomingSmsUiSyncWorker
import org.fossify.messages.messaging.ReceivingSubscriptionReader
import org.fossify.messages.messaging.SmsIngressIdentity
import org.fossify.messages.messaging.SmsIngressJournal
import org.fossify.messages.messaging.SmsRecoveryWorker
import org.fossify.messages.messaging.SmsRoleDiagnostics
import org.fossify.messages.models.Message
import org.fossify.messages.models.MessageOperation
import org.fossify.messages.remote.RemoteSmsCommandProcessor
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serial foreground owner for incoming SMS processing. The service remains runnable while the
 * screen is off, verifies provider persistence before notifying or forwarding, and asks Android to
 * redeliver an interrupted intent.
 */
open class IncomingSmsService : Service() {
    private val executor = Executors.newSingleThreadExecutor()

    /**
     * 本次服务实例是否真的拿到了前台身份。
     *
     * 系统的"接受 FGS 启动"与"允许展示前台通知"是两步：`startForegroundService()` 成功只代表 第一步通过，`startForeground()`
     * 仍可能被拒（见 [onCreate]）。false 时进程只是普通后台进程， 随时可能被回收，因此处理完必须再补一次补偿扫描。
     */
    @Volatile
    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        // FGS「半程失败」防护：startForegroundService() 已被系统接受（enqueue() 因此返回 true，
        // 接收侧的降级路径不会执行），但 startForeground() 本身仍可能抛异常——
        //   · Android 14+：specialUse 类型未获批准 → SecurityException /
        //     InvalidForegroundServiceTypeException
        //   · 部分 OEM：在 startForeground() 处再做一次后台启动 / 通知权限校验
        // 异常若穿出 onCreate，服务直接崩溃、intent 永远不会被 processIncoming 消费，
        // 而接收侧已经认为"交给前台服务了" → 短信彻底丢失且无任何兜底。
        // 因此这里吞掉异常、记日志、继续走 processIncoming（尽力而为），并立刻入队一次补偿扫描。
        foregroundStarted = try {
            startInForeground()
            true
        } catch (error: Throwable) {
            Log.e(TAG, "startForeground failed; continuing without foreground status", error)
            runCatching {
                PushPlusConfig(applicationContext).lastReceiverStatus =
                    "广播已到达，前台通知启动失败（降级处理）：${error.message ?: error.javaClass.simpleName}"
                SmsRecoveryWorker.enqueueNow(applicationContext)
            }.onFailure { enqueueError ->
                Log.w(TAG, "minimal: recovery enqueue after startForeground failure failed", enqueueError)
            }
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        try {
            executor.execute {
                var wakeLock: PowerManager.WakeLock? = null
                try {
                    wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                    wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS)
                    processReceipt(intent)
            } catch (error: Throwable) {
                Log.e(TAG, "incoming SMS processing failed", error)
                PushPlusConfig(applicationContext).lastReceiverStatus =
                    "广播已到达，处理失败：${error.message ?: error.javaClass.simpleName}"
                // A foreground owner can fail after Android accepted the broadcast handoff.
                // Preserve the SMS through the same bounded fallback used by the receiver,
                // then reconcile partially completed work even when foregroundStarted is true.
                runCatching { processMinimal(applicationContext, intent) }
                    .onFailure { Log.e(TAG, "minimal fallback after processing failure failed", it) }
                runCatching { SmsRecoveryWorker.enqueueNow(applicationContext) }
                    .onFailure { error -> Log.w(TAG, "recovery enqueue failed", error) }
                }
                stopSelfResult(startId)
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            runCatching { SmsRecoveryWorker.enqueueNow(applicationContext) }
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        executor.shutdown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun processReceipt(source: Intent) =
        synchronized(SmsIngressJournal.processLock) {
            val journal = SmsIngressJournal(this)
            val intent = journal.stage(source)
            val receipt = journal.receipt(intent) ?: error("missing_ingress_receipt")
            if (receipt.done) return@synchronized
            processIncoming(intent)
        }

    private fun processIncoming(intent: Intent) {
        if (!SmsRoleDiagnostics.observe(this, "receive:${intent.action}")) {
            processMinimal(this, intent)
            return
        }
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
            ?: (SmsIngressJournal(this).receipt(intent)?.receivedAt
                    ?: System.currentTimeMillis())
        val receivedAt =
            SmsIngressJournal(this).receipt(intent)?.receivedAt ?: System.currentTimeMillis()
        val subscriptionId = resolveSubscriptionId(this, intent)
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

        val fingerprint =
            SmsIngressJournal(this).receipt(intent)?.key
                ?: fingerprint(address, body, sentAt, subscriptionId)
        if (wasPersisted(this, fingerprint) && SmsIngressJournal(this).receipt(intent) == null) {
            Log.i(TAG, "duplicate ${intent.action} ignored after successful persistence")
            ShadowRepository.recordStep(this, operationId, "DUPLICATE_CHECK", "SKIPPED", "Already persisted")
            return
        }

        val receiverStatus = PushPlusConfig(applicationContext)
        receiverStatus.lastReceiverStatus =
            "已收到${intent.action?.substringAfterLast('.').orEmpty()}，正在写入短信库"

        // P2-D：三路径统一语义——「过滤」只决定是否转发/通知，不决定是否入库。
        // 这里不再提前 return：被过滤的短信仍会写系统短信库 + 本地 Room（用户看得到），
        // 只是跳过转发、通知、远程指令与自动回复。
        // 之所以不再"完全不落库"：那会让同一条被过滤短信在正常模式下从系统短信库消失、
        // 在降级模式下却留存（历史行为分歧）；更糟的是"短信凭空消失"正是本项目 P0 的症状，
        // 用户无法区分"被我自己过滤了"和"短信丢了"。
        val requestedThreadId = getThreadId(address)
        val insertedMessageId = persistWithRetry(this, address, parts.last().pseudoSubject.orEmpty(),
            body, receivedAt, sentAt, requestedThreadId, subscriptionId,
                intent.getStringExtra(SmsIngressJournal.EXTRA_EVENT),
            )
        check(insertedMessageId > 0L) { "默认短信身份已变化，改用只读接收" }

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
        markPersisted(this, fingerprint)
        SmsRecoveryWorker.markObserved(applicationContext, receivedAt)
        Log.i(
            TAG,
            "SMS persisted: id=$insertedMessageId thread=$resolvedThreadId action=${intent.action}",
        )

        runCatching {
            writeLocalMessageMinimal(
                this,
                address,
                body,
                receivedAt,
                insertedMessageId,
                resolvedThreadId,
                subscriptionId,
                parts.last().status,
                intent.getStringExtra(SmsIngressJournal.EXTRA_EVENT),
            )
        }
            .onFailure {
                IncomingSmsUiSyncWorker.enqueue(
                    applicationContext,
                    address,
                    body,
                    receivedAt,
                    subscriptionId,
                    fingerprint,
                    insertedMessageId,
                )
            }
        val filteredOut = isFiltered(address, body)
        if (filteredOut) {
            Log.i(
                TAG,
                "incoming SMS from $address was filtered by user rules; persisting without forwarding",
            )
            ShadowRepository.recordStep(this, operationId, "FILTER_MATCHED", "OBSERVED")
        }

        if (!filteredOut) {
            SmsIngressJournal(this).receipt(intent)?.let { receipt ->
                org.fossify.messages.messaging.SmsIngressAlerts(this).enqueue(
                    receipt.key, insertedMessageId, address, body, resolvedThreadId)
            }
        }

        runCatching {
            updateLocalDatabaseAndNotify(
                address = address,
                body = body,
                date = receivedAt,
                messageId = insertedMessageId,
                threadId = resolvedThreadId,
                subscriptionId = subscriptionId,
                status = parts.last().status,
                operationId = operationId,
                // One durable alert claim covers both broadcast entrances and process recovery.
                notify = !filteredOut,
                eventKey = intent.getStringExtra(SmsIngressJournal.EXTRA_EVENT),
            )
        }.onFailure { error ->
            Log.w(TAG, "local refresh failed: ${error.javaClass.simpleName}")
            IncomingSmsUiSyncWorker.enqueue(applicationContext, address, body, receivedAt,
                subscriptionId, fingerprint, insertedMessageId)
        }

        val ingress = SmsIngressIdentity(this).broadcast(address, body, sentAt, receivedAt,
            subscriptionId, fingerprint, insertedMessageId)
        val uniqueId = ingress.workId
        if (filteredOut) {
            // 关键：命中过滤时在转发历史中登记为 skipped。
            // 这样 SmsRecoveryWorker 扫描到此消息时（已经在 Room 里），
            // 能直接在 forwardedMessageIds 中看到记录，将其识别为"已由 FULL 路径判定过滤"，
            // 从而彻底防止 blockUnknownNumbers 等过滤被恢复扫描击穿并错误补发！
            runCatching {
                ForwardingHistoryStore(applicationContext).registerSkipped(
                    workId = uniqueId,
                    channel = "system",
                    sender = address,
                    body = body,
                    receivedAt = receivedAt,
                    subscriptionId = subscriptionId,
                    detail = "用户规则拦截（含陌生人/黑名单/关键词），不予转发"
                )
            }
        }

        val eventKey = intent.getStringExtra(SmsIngressJournal.EXTRA_EVENT)
        if (eventKey != null && !SmsIngressJournal(this).routingAllowed(eventKey)) return
        val rulesConfig = ForwardingRulesConfig(applicationContext)
        val simSlotIndex = SmsIngressJournal(this).receipt(intent)?.slot?.takeIf { it >= 0 }

        val multiConfig = MultiForwardConfig(applicationContext)
        val enabledForwardChannels = buildSet {
            if (receiverStatus.enabled) add(ForwardingChannels.PUSHPLUS)
            addAll(multiConfig.enabledChannelIds())
        }
        // 命中过滤的短信不跑规则求值：它根本不会走到转发阶段，求值结果无人消费（下游
        // `lastDecision` 写入、`registerSkipped`、转发入队全都被 `!filteredOut` 守卫）。
        // 同时也不该写 `lastDecision`——那是"为什么没转发"的**转发规则**视图，而被过滤的短信
        // 没转发的原因是用户过滤规则（黑名单/关键词），写一条"转发规则未允许：X渠道"既误导用户，
        // 又会覆盖掉上一条非过滤短信的真实决策。
        val ruleDecision = if (!filteredOut && rulesConfig.enabled) {
            ForwardingRuleEngine(rulesConfig.rules) { receivedAt }
                    .evaluate(
                sender = address,
                body = body,
                subscriptionId = subscriptionId,
                channelCandidates = rulesConfig.channelCandidatesForScope(enabledForwardChannels),
                simSlotIndex = simSlotIndex,
                resolveContent = { rule, action, text ->
                    val template = action.customTemplate.takeIf { action.templateMode == org.fossify.messages.forwarding.RuleTemplateMode.CUSTOM }
                        ?: rule.customTemplate.takeIf(String::isNotBlank)
                    if (template != null) {
                        ForwardingMessageFormatter.renderRuleTemplate(this, template, address, text, sentAt, subscriptionId)
                    } else {
                        ForwardingMessageFormatter.format(this, address, text, sentAt, subscriptionId).content
                    }
                }
            )
        } else {
            null
        }
        // ruleDecision 在 filteredOut 时恒为 null，这里无需再判 filteredOut
        if (ruleDecision?.blockedChannels?.isNotEmpty() == true) {
            val decisionTime = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            val blockedNames = ruleDecision.blockedChannels
                .map(ForwardingChannels::displayName)
                .joinToString("、")
            rulesConfig.lastDecision = "$decisionTime · $address · $blockedNames · ${ruleDecision.reason}"
        }

        val remoteCommandAllowed = !rulesConfig.affectsRemoteCommands() ||
            !rulesConfig.enabled ||
            rulesConfig.rules.none { it.enabled } ||
            ruleDecision?.matchedRules?.isNotEmpty() == true
        
        // 命中过滤的短信不消费远程指令：过滤语义 = "用户不想让这条短信产生任何副作用"，
        // 这与改动前的行为一致（此前 filtered 直接 return，根本走不到这里）。
        ShadowRepository.recordStep(this, operationId, "REMOTE_COMMAND_OBSERVED", "STARTED")
        val remoteCommandConsumed = if (filteredOut) {
            false
        } else {
            RemoteSmsCommandProcessor.tryConsume(
                context = this,
                sender = address,
                body = body,
                subscriptionId = subscriptionId,
                messageTimestamp = sentAt,
                messageId = insertedMessageId,
                allowExecution = remoteCommandAllowed,
            )
        }
        if (remoteCommandConsumed) {
            ShadowRepository.recordStep(this, operationId, "REMOTE_COMMAND_OBSERVED", "SUCCESS", "Consumed")
        }

        ShadowRepository.recordStep(this, operationId, "FORWARDING_OBSERVED", "STARTED")
        if (!filteredOut && !remoteCommandConsumed) {
            val history = ForwardingHistoryStore(applicationContext)
            if (ruleDecision != null) {
                // 1. 记录规则跳过渠道
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

                // 2. 实例级靶向投递
                if (ruleDecision.targets.isNotEmpty()) {
                    ruleDecision.targets.forEach { target ->
                        val targetKey = target.instanceId.ifBlank { target.channelType }
                        ShadowRepository.recordDelivery(this, operationId, targetKey, "QUEUED")
                        MultiChannelForwardWorker.enqueueSingle(
                            context = this,
                            sender = address,
                            body = target.renderedContent,
                            receivedAt = receivedAt,
                            subscriptionId = subscriptionId,
                            uniqueId = "$uniqueId-${target.ruleId.take(8)}-${target.actionId.take(8)}",
                            targetChannel = target.channelType,
                            allowedChannels = if (target.instanceId.isNotBlank()) setOf(target.instanceId) else setOf(target.channelType),
                            isTest = false,
                            operationId = operationId,
                            targetInstanceId = target.instanceId,
                            ruleId = target.ruleId,
                            actionId = target.actionId,
                            bodyAlreadyRendered = true,
                            allowSimLabelPrefix = target.allowSimLabelPrefix,
                            threadId = resolvedThreadId
                        )
                    }
                } else {
                    Log.i(TAG, "rules enabled but matched 0 targets: ${ruleDecision.reason}")
                }
            } else if (multiConfig.anyEnabled() || receiverStatus.enabled) {
                // 规则未启用时仍按实例逐一投递，不能把同类型多个实例压缩成一个类型。
                enqueueForwardingFallback(
                    context = this,
                    address = address,
                    body = body,
                    receivedAt = receivedAt,
                    subscriptionId = subscriptionId,
                    uniqueId = uniqueId,
                    operationId = operationId,
                    threadId = resolvedThreadId
                )
            }
        }

        eventKey?.let { SmsIngressJournal(this).phase(it, "forwarding_decision_registered") }

        // Automatic SMS reply engine evaluation
        if (!filteredOut && !remoteCommandConsumed) {
            runCatching {
                val autoReplyResult = AutoReplyProcessor.processIncoming(
                    context = this,
                    senderNumber = address,
                    messageBody = body,
                    incomingSubId = subscriptionId,
                    sourceId = fingerprint
                )
                if (autoReplyResult is AutoReplyProcessor.Result.Executed) {
                    ShadowRepository.recordStep(this, operationId, "AUTO_REPLY", "QUEUED", "Rule: ${autoReplyResult.ruleName}")
                }
            }.onFailure { e ->
                Log.e(TAG, "AutoReply evaluation error", e)
            }
        }

        receiverStatus.lastReceiverStatus = if (filteredOut) {
            "已接收并写入短信库（命中过滤规则，未转发/未通知），短信ID：$insertedMessageId，发送方：$address"
        } else {
            "已接收并写入短信库，短信ID：$insertedMessageId，发送方：$address"
        }

        ShadowRepository.recordStep(this, operationId, "LEGACY_PIPELINE_RETURNED", "SUCCESS")
        SmsIngressJournal(this).receipt(intent)?.let {
            SmsIngressJournal(this).phase(it.key, "processing_completed")
            SmsIngressJournal(this).complete(it.key)
        }
    }

    private fun isFiltered(address: String, body: String): Boolean =
        isFilteredForReceive(this, address, body)

    private fun updateLocalDatabaseAndNotify(
        address: String,
        body: String,
        date: Long,
        messageId: Long,
        threadId: Long,
        subscriptionId: Int,
        status: Int,
        operationId: String? = null,
        /** false 时只入库、不弹通知（命中过滤规则的短信走这条路）。 */
        notify: Boolean = true,
        eventKey: String? = null,
    ) {
        // Contact enrichment must never prevent the original SMS from being stored.
        val contact = runCatching { getNameAndPhotoFromPhoneNumber(address) }.getOrNull()
        val photoUri = contact?.photoUri.orEmpty()
        val senderName = runCatching {
            getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true).use {
                getNameFromAddress(address, it)
            }
        }.getOrDefault(contact?.name.orEmpty().ifBlank { address })
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
            read = messagesDB.getMessageWithId(messageId)?.read ?: false,
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

        if (notify) {
            if (eventKey != null) {
                org.fossify.messages.messaging.SmsIngressAlerts(this).enqueue(
                    eventKey, messageId, address, body, threadId, senderName)
            } else {
                showReceivedMessageNotification(messageId, address, senderName, body, threadId,
                    getNotificationBitmap(photoUri))
            }
        }
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
        private const val DUPLICATE_PREFS = "sms_receiver_state"
        private const val KEY_LAST_FINGERPRINT = "last_fingerprint"
        private const val KEY_RECENT_FINGERPRINTS = "recent_fingerprints"
        private const val MAX_RECENT_FINGERPRINTS = 100
        private const val DUPLICATE_WINDOW_MS = 24 * 60 * 60 * 1000L
        /** 普通接收阶段预算；持久化恢复重放不受此门限限制。 */
        private const val MINIMAL_BUDGET_MS = 8_000L
        private val duplicateLock = Any()

        /**
         * Hands the protected SMS broadcast to the foreground service.
         *
         * @return true when Android accepted the foreground-service start; false when the platform
         *   or OEM refused it (Android 12+ background-start restriction, OEM freeze…). Only
         *   [IllegalStateException] (the parent of ForegroundServiceStartNotAllowedException, so it
         *   covers API 31+ as well as bare OEM throws without any API-level class loading) and
         *   [SecurityException] are swallowed; anything else keeps propagating so real bugs stay
         *   visible.
         */
        fun enqueue(context: Context, source: Intent): Boolean {
            val serviceIntent = Intent(context, IncomingSmsService::class.java).apply {
                action = source.action
                replaceExtras(source)
            }
            return try {
                ContextCompat.startForegroundService(context, serviceIntent)
                true
            } catch (e: IllegalStateException) {
                Log.e(TAG, "foreground service start not allowed; caller must fall back", e)
                false
            } catch (e: SecurityException) {
                Log.e(TAG, "foreground service start denied; caller must fall back", e)
                false
            }
        }

        /**
         * Serial degraded processing over a durable ingress receipt. Provider uncertainty is
         * reconciled before any insert; basic Room data precedes contact enrichment. Filtering
         * matches FULL, and alerts use a separate durable publication outbox. Ordinary intake
         * limits expensive phases to a short budget; Worker replay finishes those phases without
         * reusing that budget. Original queue/submission/billing owners remain authoritative.
         */
        fun processMinimal(context: Context, source: Intent) =
            synchronized(SmsIngressJournal.processLock) {
                val journal = SmsIngressJournal(context)
                val staged = journal.stage(source)
                if (journal.receipt(staged)?.done == true) return@synchronized
                processMinimalOwned(context, staged)
            }

        // Retain the legacy pipeline's structural debt while adding a durable serial owner; do not
        // replace its queue/guards.
        @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
        private fun processMinimalOwned(context: Context, source: Intent) {
            val appContext = context.applicationContext
            val startedAt = System.currentTimeMillis()
            val recovery = source.getBooleanExtra(SmsIngressJournal.EXTRA_REPLAY, false)
            try {
                val parts = Telephony.Sms.Intents.getMessagesFromIntent(source)
                if (parts.isEmpty()) {
                    Log.w(TAG, "minimal: ${source.action} contained no SMS parts")
                    return
                }
                val address = parts.last().originatingAddress.orEmpty()
                if (address.isBlank()) {
                    Log.w(TAG, "minimal: incoming SMS has no originating address")
                    return
                }
                val body = buildString { parts.forEach { append(it.messageBody.orEmpty()) } }
                val sentAt = parts.minOfOrNull { it.timestampMillis }
                    ?.takeIf { it > 0L }
                    ?: (SmsIngressJournal(appContext).receipt(source)?.receivedAt
                            ?: System.currentTimeMillis())
                val receipt = SmsIngressJournal(appContext).receipt(source)
                val routingAllowed =
                    receipt?.let { SmsIngressJournal(appContext).routingAllowed(it.key) } != false
                val receivedAt = receipt?.receivedAt ?: System.currentTimeMillis()
                val subscriptionId = resolveSubscriptionId(appContext, source)
                val fingerprint = receipt?.key ?: fingerprint(address, body, sentAt, subscriptionId)

                // 2a. 幂等：SMS_DELIVER 与 SMS_RECEIVED 可能双发，已入库则直接停止。
                if (wasPersisted(appContext, fingerprint) && receipt == null) {
                    Log.i(TAG, "minimal: duplicate ${source.action} ignored after successful persistence")
                    return
                }

                // 廉价过滤（微秒级，不查联系人）。命中时仍然写库保证不丢，只是不转发。
                var filteredOut = isFilteredCheap(appContext, address, body)
                if (filteredOut) {
                    Log.i(TAG, "minimal: $address matched user filter; will persist but skip forwarding")
                }

                // 1. 写系统短信库
                val ownsSms = SmsRoleDiagnostics.observe(appContext, "minimal:${source.action}")
                val requestedThreadId = if (ownsSms) appContext.getThreadId(address) else 0L
                val insertedMessageId = try {
                    persistWithRetry(
                        context = appContext,
                        address = address,
                        subject = parts.last().pseudoSubject.orEmpty(),
                        body = body,
                        receivedAt = receivedAt,
                        sentAt = sentAt,
                        threadId = requestedThreadId,
                        subscriptionId = subscriptionId,
                            eventKey = receipt?.key,
                        )
                } catch (e: Exception) {
                    Log.e(TAG, "minimal: SMS provider insert failed", e)
                    PushPlusConfig(appContext).lastReceiverStatus =
                        "广播已到达，前台服务不可用且短信库写入失败：${e.message ?: e.javaClass.simpleName}"
                    SmsRecoveryWorker.enqueueNow(appContext)
                    -1L
                }

                if (insertedMessageId < 0L) {
                    // UI repair must not depend on the forwarding identity database succeeding.
                    IncomingSmsUiSyncWorker.enqueue(appContext, address, body, receivedAt, subscriptionId, fingerprint,
                        insertedMessageId,
                    )
                }
                val ingress = SmsIngressIdentity(appContext).broadcast(address, body,
                    parts.minOf { it.timestampMillis }.coerceAtLeast(0L), receivedAt, subscriptionId,
                    fingerprint, insertedMessageId)
                if (!ownsSms) {
                    PushPlusConfig(appContext).lastReceiverStatus = "非默认短信模式：跳过系统写库，按广播转发"
                    SmsRecoveryWorker.enqueueNow(appContext)
                }
                if (insertedMessageId < 0L) {
                    filteredOut = isFilteredForReceive(appContext, address, body)
                    if (!filteredOut && receipt != null) {
                        org.fossify.messages.messaging.SmsIngressAlerts(appContext)
                            .enqueue(receipt.key, insertedMessageId, address, body, 0L)
                    }
                    // Provider 写入失败时转发是最后一份拷贝：SmsRecoveryWorker 扫的是 Provider，
                    // 没写进去的消息它不会发现，因此这里仍然必须入队转发（除非被用户规则拦截）。
                    if (!filteredOut && routingAllowed) {
                        enqueueForwardingFallback(
                            context = appContext,
                            address = address,
                            body = body,
                            receivedAt = ingress.receivedAt,
                            subscriptionId = subscriptionId,
                            uniqueId = ingress.workId,
                            operationId = null,
                            ruleDecision = evaluateCheapRules(appContext, address, body, subscriptionId, ingress.receivedAt),
                        )
                    }
                    if (!ownsSms && routingAllowed)
                        receipt?.let { SmsIngressJournal(appContext).complete(it.key)
                    }
                    return
                }

                // 2b. 只有确认写入成功后才登记指纹，抑制第二次投递。
                markPersisted(appContext, fingerprint)
                SmsRecoveryWorker.markObserved(appContext, receivedAt)
                Log.i(TAG, "minimal: SMS persisted id=$insertedMessageId action=${source.action}")

                val resolvedMinimalThreadId = appContext.getSmsThreadId(insertedMessageId)
                    .takeIf { it > 0L }
                    ?: requestedThreadId

                // 3. 本地 Room + 会话同步（不做联系人查询，姓名直接用号码兜底）
                if (org.fossify.messages.messaging.RecoveryExecutionPolicy.mayContinue(
                        recovery, System.currentTimeMillis() - startedAt, MINIMAL_BUDGET_MS)) {
                    runCatching {
                        writeLocalMessageMinimal(
                            context = appContext,
                            address = address,
                            body = body,
                            date = receivedAt,
                            messageId = insertedMessageId,
                            threadId = resolvedMinimalThreadId,
                            subscriptionId = subscriptionId,
                            status = parts.last().status,
                            eventKey = receipt?.key,
                        )
                    }.onFailure { error ->
                        Log.e(TAG, "minimal: local refresh failed (non-fatal)", error)
                        IncomingSmsUiSyncWorker.enqueue(appContext, address, body, receivedAt, subscriptionId, fingerprint,
                                insertedMessageId,
                            )
                    }
                } else {
                    Log.w(TAG, "minimal: skipped local sync, budget exhausted after ${System.currentTimeMillis() - startedAt}ms")
                    IncomingSmsUiSyncWorker.enqueue(appContext, address, body, receivedAt, subscriptionId, fingerprint,
                        insertedMessageId,
                    )
                }

                filteredOut = isFilteredForReceive(appContext, address, body)
                if (!filteredOut && receipt != null) {
                    org.fossify.messages.messaging.SmsIngressAlerts(appContext).enqueue(
                        receipt.key, insertedMessageId, address, body, resolvedMinimalThreadId)
                }

                // Ordinary intake may defer an expensive phase; durable Worker replay must finish
                // it instead of repeatedly deferring behind its own Provider ownership marker.
                var budgetBlockedForwarding = false
                if (filteredOut) {
                    Log.i(TAG, "minimal: skipped forwarding for filtered sender $address (id=$insertedMessageId)")
                } else if (!routingAllowed) {
                    budgetBlockedForwarding = true
                } else if (!org.fossify.messages.messaging.RecoveryExecutionPolicy.mayContinue(
                        recovery, System.currentTimeMillis() - startedAt, MINIMAL_BUDGET_MS)) {
                    Log.w(
                        TAG,
                        "minimal: skipped forwarding (id=$insertedMessageId), budget exhausted " +
                            "after ${System.currentTimeMillis() - startedAt}ms",
                    )
                    budgetBlockedForwarding = true
                    runCatching { SmsRecoveryWorker.enqueueNow(appContext) }
                        .onFailure { error -> Log.w(TAG, "minimal: recovery enqueue failed", error) }
                } else {
                    enqueueForwardingFallback(
                        context = appContext,
                        address = address,
                        body = body,
                        receivedAt = ingress.receivedAt,
                        subscriptionId = subscriptionId,
                        uniqueId = ingress.workId,
                        operationId = null,
                        // P1-B：降级期间也必须应用用户的渠道白名单，否则"屏蔽某渠道"会失效。
                        ruleDecision = evaluateCheapRules(appContext, address, body, subscriptionId, ingress.receivedAt),
                        threadId = resolvedMinimalThreadId,
                    )
                }

                if (
                    !budgetBlockedForwarding &&
                        appContext.messagesDB.getMessageWithId(insertedMessageId) != null
                )
                    receipt?.let { SmsIngressJournal(appContext).complete(it.key)
                }

                PushPlusConfig(appContext).lastReceiverStatus = when {
                    filteredOut ->
                        "已接收并写入短信库（降级路径，命中过滤未转发），短信ID：$insertedMessageId，发送方：$address"
                        !routingAllowed -> "已接收并写入短信库，配置已变化，旧事件转发已暂停待核对"
                        budgetBlockedForwarding ->
                        "已接收并写入短信库（降级路径，预算耗尽未即时转发，已排入补偿扫描），" +
                            "短信ID：$insertedMessageId，发送方：$address"
                    else ->
                        "已接收并写入短信库（降级路径），短信ID：$insertedMessageId，发送方：$address"
                }
            } catch (error: Throwable) {
                // 兜底：绝不把异常抛回 onReceive，否则广播未被消费 = 短信永久丢失。
                Log.e(TAG, "minimal: degraded handling failed", error)
                runCatching {
                    PushPlusConfig(appContext).lastReceiverStatus =
                        "广播已到达，降级处理失败：${error.message ?: error.javaClass.simpleName}"
                    SmsRecoveryWorker.enqueueNow(appContext)
                }
            }
        }

        /** Shared receive filter. All paths persist basic local data before contact lookups. */
        fun isFilteredForReceive(context: Context, address: String, body: String): Boolean {
            if (isFilteredCheap(context, address, body)) return true
            val isWhitelisted = context.config.isNumberWhitelisted(address)
            if (!isWhitelisted && context.baseConfig.blockUnknownNumbers) {
                val result = runCatching {
                    context
                        .getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
                        .use { privateCursor ->
                            SimpleContactsHelper(context).existsSync(address, privateCursor)
                        }
                }
                    .getOrNull()
                if (result == ContactLookupResult.NotFound) return true
            }
            return false
        }

        fun isFilteredCheap(context: Context, address: String, body: String): Boolean {
            if (context.config.isNumberWhitelisted(address)) return false
            if (context.config.isNumberBlacklisted(address)) return true
            if (isMessageFilteredOut(context, body)) return true
            return context.isNumberBlocked(address)
        }

        /**
         * 降级路径的"廉价规则子集"：只求值**渠道白名单 + 实例白名单**，不渲染模板、不查联系人。
         *
         * 规则求值本身是纯内存字符串匹配（不传 `resolveContent` 时引擎不会渲染模板）， 真正昂贵的是模板渲染与联系人查询，因此这个子集可以安全地放进 goAsync
         * 预算内。
         *
         * 与 FULL 路径的差别：FULL 会用 `resolveContent` 逐目标渲染自定义模板并按 `ruleDecision.targets`
         * 精准投递；降级路径不做模板渲染，只按 [ForwardingRuleDecision.allowedChannels] /
         * [ForwardingRuleDecision.allowedInstanceIds] 对启用渠道做白名单过滤，投递正文仍走默认格式化。
         *
         * @return null = 规则未启用 / 求值失败（不限制渠道，与改动前行为一致）； 非 null = 只允许该决策允许的渠道类型与实例。
         */
        fun evaluateCheapRules(
            context: Context,
            address: String,
            body: String,
            subscriptionId: Int,
            receivedAt: Long = System.currentTimeMillis(),
        ): ForwardingRuleDecision? {
            val rulesConfig = runCatching { ForwardingRulesConfig(context) }.getOrNull() ?: return null
            if (!rulesConfig.enabled) return null
            val enabledChannels = runCatching {
                buildSet {
                    if (PushPlusConfig(context).enabled) add(ForwardingChannels.PUSHPLUS)
                    addAll(MultiForwardConfig(context).enabledChannelIds())
                }
            }.getOrDefault(emptySet())
            val simSlotIndex =
                ReceivingSubscriptionReader.slotForSubscription(context, subscriptionId)
            return runCatching {
                ForwardingRuleEngine(rulesConfig.rules) { receivedAt }
                    .evaluate(
                    sender = address,
                    body = body,
                    subscriptionId = subscriptionId,
                    channelCandidates = rulesConfig.channelCandidatesForScope(enabledChannels),
                    simSlotIndex = simSlotIndex,
                    resolveContent = { rule, action, text ->
                        val template = action.customTemplate
                            .takeIf { action.templateMode == org.fossify.messages.forwarding.RuleTemplateMode.CUSTOM }
                            ?: rule.customTemplate.takeIf(String::isNotBlank)
                        if (template != null) ForwardingMessageFormatter.renderRuleTemplate(
                            context, template, address, text, receivedAt, subscriptionId)
                        else ForwardingMessageFormatter.format(context, address, text, receivedAt, subscriptionId).content
                    },
                )
            }.onFailure { error ->
                Log.e(TAG, "minimal: cheap rule subset evaluation failed", error)
            }.getOrNull()
        }

        /** 按实例逐一入队转发，并兼容尚未转换成实例的旧版渠道配置。 FULL 与 MINIMAL 两条路径共用，避免实现漂移。 */
        fun enqueueForwardingFallback(
            context: Context,
            address: String,
            body: String,
            receivedAt: Long,
            subscriptionId: Int,
            uniqueId: String,
            operationId: String?,
            /**
             * 降级路径的廉价规则子集（见 [evaluateCheapRules]）。 null = 规则未启用 / 未求值 → 不限制渠道（与改动前行为一致）； 非 null =
             * 只允许 [ForwardingRuleDecision.allowedChannels] 内的渠道类型， 以及
             * [ForwardingRuleDecision.allowedInstanceIds] 内的实例。
             */
            ruleDecision: ForwardingRuleDecision? = null,
            threadId: Long = 0L,
            originalSim: org.fossify.messages.forwarding.ForwardingSimSnapshot? = null,
        ) {
            if (ruleDecision != null) {
                ruleDecision.targets.forEach { target ->
                    MultiChannelForwardWorker.enqueueSingle(
                        originalSim = originalSim,
                        context = context, sender = address,
                        body = target.renderedContent, receivedAt = receivedAt, subscriptionId = subscriptionId,
                        uniqueId = "$uniqueId-${target.ruleId.take(8)}-${target.actionId.take(8)}",
                        targetChannel = target.channelType,
                        allowedChannels = setOf(target.instanceId.ifBlank { target.channelType }),
                        isTest = false, operationId = operationId, targetInstanceId = target.instanceId,
                        ruleId = target.ruleId, actionId = target.actionId, bodyAlreadyRendered = true,
                        allowSimLabelPrefix = target.allowSimLabelPrefix, threadId = threadId)
                }
                return
            }
            val multiConfig = MultiForwardConfig(context)
            val pushPlusEnabled = PushPlusConfig(context).enabled
            val enabledInstances: List<ForwardingChannelInstance> = runCatching {
                org.fossify.messages.forwarding.repository.ChannelRepository
                    .getInstance(context)
                    .getEnabledInstances()
            }.onFailure { error ->
                Log.e(TAG, "forwarding: channel instances unavailable", error)
            }.getOrDefault(emptyList())

            // P1-B：降级期间也必须尊重用户配置的渠道白名单，否则规则"屏蔽渠道 Z"会失效，
            // 消息会被投递到用户明确不想让内容进入的渠道。
            val allowedInstances = if (ruleDecision == null) {
                enabledInstances
            } else {
                enabledInstances.filter { instance ->
                    // 若规则明确指定了实例列表，则以 allowedInstanceIds 判定；
                    // 否则回退到按渠道类型 allowedChannels 判定。
                    if (ruleDecision.allowedInstanceIds.isNotEmpty()) {
                        ruleDecision.isInstanceAllowed(instance.id)
                    } else {
                        ruleDecision.isAllowed(instance.channelType)
                    }
                }
            }
            if (ruleDecision != null && allowedInstances.size != enabledInstances.size) {
                Log.i(
                    TAG,
                    "forwarding: rule subset blocked ${enabledInstances.size - allowedInstances.size} " +
                    "of ${enabledInstances.size} instance(s)",
                )
            }

            allowedInstances.forEach { instance ->
                operationId?.let { ShadowRepository.recordDelivery(context, it, instance.id, "QUEUED") }
                MultiChannelForwardWorker.enqueueSingle(
                    originalSim = originalSim,
                    context = context,
                    sender = address,
                    body = body,
                    receivedAt = receivedAt,
                    subscriptionId = subscriptionId,
                    uniqueId = "$uniqueId-${instance.id}",
                    targetChannel = instance.channelType,
                    allowedChannels = setOf(instance.id),
                    isTest = false,
                    operationId = operationId,
                    targetInstanceId = instance.id,
                    threadId = threadId
                )
            }

            // 兼容尚未转换成实例的旧版配置；同类型已有实例时避免重复发送。
            // instanceTypes 用"全部启用实例"（而非 allowedInstances）计算，这样被规则屏蔽的实例
            // 其同类型 legacy 通道也会一并被排除，不会绕过实例级屏蔽。
            val instanceTypes = enabledInstances.map { it.channelType }.toSet()
            val legacyChannels = (multiConfig.enabledChannelIds() +
                if (pushPlusEnabled) setOf(ForwardingChannels.PUSHPLUS) else emptySet())
                .filter { channel -> ruleDecision == null || ruleDecision.isAllowed(channel) }
                .toSet() - instanceTypes
            legacyChannels.forEach { channel ->
                operationId?.let { ShadowRepository.recordDelivery(context, it, channel, "QUEUED") }
                MultiChannelForwardWorker.enqueueSingle(
                    originalSim = originalSim,
                    context = context,
                    sender = address,
                    body = body,
                    receivedAt = receivedAt,
                    subscriptionId = subscriptionId,
                    uniqueId = "$uniqueId-legacy-$channel",
                    targetChannel = channel,
                    allowedChannels = setOf(channel),
                    isTest = false,
                    operationId = operationId,
                    threadId = threadId
                )
            }
        }

        private fun writeLocalMessageMinimal(
            context: Context,
            address: String,
            body: String,
            date: Long,
            messageId: Long,
            threadId: Long,
            subscriptionId: Int,
            status: Int,
            eventKey: String? = null,
        ) {
            check(threadId > 0L) { "provider_thread_not_ready" }
            val participant = SimpleContact(
                rawId = 0,
                contactId = 0,
                name = address,
                photoUri = "",
                phoneNumbers = arrayListOf(
                    PhoneNumber(value = address, type = 0, label = "", normalizedNumber = address),
                ),
                birthdays = ArrayList(),
                anniversaries = ArrayList(),
            )
            val providerRead = runCatching {
                context.contentResolver
                    .query(
                        android.content.ContentUris.withAppendedId(
                            Telephony.Sms.CONTENT_URI,
                            messageId,
                        ),
                        arrayOf(Telephony.Sms.READ),
                        null,
                        null,
                        null,
                    )
                    ?.use { if (it.moveToFirst()) it.getInt(0) == 1 else null }
            }
                .getOrNull()
            val message = Message(
                id = messageId,
                body = body,
                type = Telephony.Sms.MESSAGE_TYPE_INBOX,
                status = status,
                participants = arrayListOf(participant),
                date = (date / 1000).toInt(),
                read =
                        providerRead
                            ?: context.messagesDB.getMessageWithId(messageId)?.read
                            ?: false,
                threadId = threadId,
                isMMS = false,
                attachment = null,
                senderPhoneNumber = address,
                senderName = address,
                senderPhotoUri = "",
                subscriptionId = subscriptionId,
            )
            context.getMessagesDB().runInTransaction {
                val previous = context.messagesDB.getMessageWithId(messageId)
                context.messagesDB.insertOrUpdate(message.copy(read = providerRead ?: previous?.read ?: message.read)
                )
                val old = context.conversationsDB.getConversationWithThreadId(threadId)
                val rows = context.messagesDB.getNonRecycledThreadMessages(threadId)
                val latest =
                    rows.maxWithOrNull(compareBy<Message> { it.date }.thenBy { it.id }) ?: message
                val unread = rows.count { !it.read && it.type == Telephony.Sms.MESSAGE_TYPE_INBOX }
                context.conversationsDB.insertOrUpdate(
                    old?.copy(
                        snippet = latest.body,
                        date = latest.date,
                        read = unread == 0,
                        unreadCount = unread,
                        isScheduled = false,
                    )
                        ?: org.fossify.messages.models.Conversation(
                            threadId = threadId,
                            snippet = latest.body,
                            date = latest.date,
                            read = unread == 0,
                            title = address,
                            photoUri = "",
                            isGroupConversation = false,
                            phoneNumber = address,
                            unreadCount = unread,
                        )
                )
            }
            eventKey?.let { SmsIngressJournal(context).phase(it, "local_committed") }
            refreshMessages()
            refreshConversations()
            eventKey?.let { SmsIngressJournal(context).phase(it, "refresh_published") }
        }

        fun resolveSubscriptionId(context: Context, intent: Intent): Int =
            SmsIngressJournal(context).receipt(intent)?.sub
                ?: (ReceivingSubscriptionReader.read(context, intent).confirmedSubscriptionId ?: -1)

        fun persistWithRetry(
            context: Context,
            address: String,
            subject: String,
            body: String,
            receivedAt: Long,
            sentAt: Long,
            threadId: Long,
            subscriptionId: Int,
            eventKey: String? = null,
        ): Long =
            org.fossify.messages.messaging.SmsProviderPersistence.persistWithRetry(
                context,
                address,
                subject,
                body,
                receivedAt,
                sentAt,
                threadId,
                subscriptionId,
                eventKey,
            )

        fun wasPersisted(context: Context, fingerprint: String): Boolean = synchronized(duplicateLock) {
            val prefs = context.getSharedPreferences(DUPLICATE_PREFS, Context.MODE_PRIVATE)
            val rawEntries = decodePersistedFingerprints(
                prefs.getString(KEY_RECENT_FINGERPRINTS, "[]").orEmpty(),
            )
            val now = System.currentTimeMillis()
            val entries = rawEntries.filter { now - it.second in 0L..DUPLICATE_WINDOW_MS }
            if (entries.size != rawEntries.size) persistFingerprints(prefs, entries)
            prefs.getString(KEY_LAST_FINGERPRINT, null) == fingerprint || entries.any { it.first == fingerprint }
        }

        fun markPersisted(context: Context, fingerprint: String) = synchronized(duplicateLock) {
            val prefs = context.getSharedPreferences(DUPLICATE_PREFS, Context.MODE_PRIVATE)
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

        fun fingerprint(
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
    }
}
