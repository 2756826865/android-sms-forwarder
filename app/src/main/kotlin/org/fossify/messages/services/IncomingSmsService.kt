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

    /**
     * 本次服务实例是否真的拿到了前台身份。
     *
     * 系统的"接受 FGS 启动"与"允许展示前台通知"是两步：`startForegroundService()` 成功只代表
     * 第一步通过，`startForeground()` 仍可能被拒（见 [onCreate]）。false 时进程只是普通后台进程，
     * 随时可能被回收，因此处理完必须再补一次补偿扫描。
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
                if (!foregroundStarted) {
                    // 没有前台身份时进程随时可能被回收。处理完再补一次补偿扫描：此时短信多半已经
                    // 写入系统短信库，SmsRecoveryWorker 能真正把它捞回来（onCreate 里那次入队只是
                    // "先占位"，那时短信还没落库，扫到也救不回来）。
                    runCatching { SmsRecoveryWorker.enqueueNow(applicationContext) }
                        .onFailure { error -> Log.w(TAG, "recovery enqueue failed", error) }
                }
                stopSelfResult(startId)
            }
        }
        return START_REDELIVER_INTENT
    }

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
        if (wasPersisted(this, fingerprint)) {
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
        val filteredOut = isFiltered(address, body)
        if (filteredOut) {
            Log.i(TAG, "incoming SMS from $address was filtered by user rules; persisting without forwarding")
            ShadowRepository.recordStep(this, operationId, "FILTER_MATCHED", "OBSERVED")
        }

        val requestedThreadId = getThreadId(address)
        ShadowRepository.recordStep(this, operationId, "PROVIDER_INSERT", "STARTED")
        val insertedMessageId = try {
            val id = persistWithRetry(
                context = this,
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
        markPersisted(this, fingerprint)
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
                operationId = operationId,
                // 命中过滤的短信只入库、不弹通知（与 MINIMAL / RECOVERY 对齐）
                notify = !filteredOut,
            )
        }.onFailure { error ->
            Log.e(TAG, "system SMS persisted, local refresh failed", error)
            ShadowRepository.recordStep(this, operationId, "LOCAL_SYNC_OBSERVED", "FAILED", error.message)
            SmsRecoveryWorker.enqueueNow(applicationContext)
        }

        val uniqueId = "sms-$insertedMessageId"
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
        } else {
            org.fossify.messages.autofill.SmsAutofillAccessibilityService.onNewVerificationSms(applicationContext, body)
        }

        val rulesConfig = ForwardingRulesConfig(applicationContext)
        val simSlotIndex = runCatching {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_PHONE_STATE) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                subscriptionManagerCompat().getActiveSubscriptionInfo(subscriptionId)?.simSlotIndex
            } else {
                null
            }
        }.getOrNull()

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
            ForwardingRuleEngine(rulesConfig.rules).evaluate(
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

        // Automatic SMS reply engine evaluation
        if (!filteredOut && !remoteCommandConsumed) {
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

        receiverStatus.lastReceiverStatus = if (filteredOut) {
            "已接收并写入短信库（命中过滤规则，未转发/未通知），短信ID：$insertedMessageId，发送方：$address"
        } else {
            "已接收并写入短信库，短信ID：$insertedMessageId，发送方：$address"
        }

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

    private fun isFiltered(address: String, body: String): Boolean {
        if (isFilteredCheap(this, address, body)) return true
        val isWhitelisted = config.isNumberWhitelisted(address)
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
        operationId: String? = null,
        /** false 时只入库、不弹通知（命中过滤规则的短信走这条路）。 */
        notify: Boolean = true,
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

        if (notify) {
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
        /** 降级路径总预算：goAsync 窗口约 10s，留 2s 余量。 */
        private const val MINIMAL_BUDGET_MS = 8_000L
        private val duplicateLock = Any()

        /**
         * Hands the protected SMS broadcast to the foreground service.
         *
         * @return true when Android accepted the foreground-service start; false when the
         * platform or OEM refused it (Android 12+ background-start restriction, OEM freeze…).
         * Only [IllegalStateException] (the parent of ForegroundServiceStartNotAllowedException,
         * so it covers API 31+ as well as bare OEM throws without any API-level class loading)
         * and [SecurityException] are swallowed; anything else keeps propagating so real bugs
         * stay visible.
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
         * 降级路径：前台服务启动失败时，在广播的 goAsync 窗口内同步完成"不丢短信"的最小工作集。
         *
         * 按序完成（总预算 [MINIMAL_BUDGET_MS] = 8s，goAsync 窗口约 10s，留 2s 余量）：
         * 1. 写系统短信库（[persistWithRetry]，3 次重试），失败则 [SmsRecoveryWorker.enqueueNow]
         * 2. [wasPersisted] / [markPersisted] 幂等去重（SMS_DELIVER 与 SMS_RECEIVED 双发时防重复入库）
         * 3. 写本地 Room + [org.fossify.messages.extensions.syncThreadToLocal]（失败只记日志，不阻塞）
         * 4. 入队转发 [MultiChannelForwardWorker.enqueueSingle]，并应用**廉价规则子集**
         *    （渠道/实例白名单，见 [evaluateCheapRules]；不做模板渲染）
         *
         * 第 3、4 步各自前置 [MINIMAL_BUDGET_MS] 闸门；被闸门拦下时入队 [SmsRecoveryWorker] 补偿，
         * 由恢复路径补转发（恢复路径用"是否登记过转发"而非"是否入过库"判重，见 SmsRecoveryWorker）。
         *
         * 明确不做（会 ANR，留给 SmsRecoveryWorker 补做）：联系人查询（含 blockUnknownNumbers）、
         * 通知、自动回复、远程指令消费、验证码提取、悬浮胶囊、**模板渲染**。
         *
         * 本函数不会向调用方抛异常：任何失败都会记日志并触发补偿，避免异常穿透 onReceive
         * 导致 SMS_DELIVER 广播未被消费、短信彻底丢失。
         */
        fun processMinimal(context: Context, source: Intent) {
            val appContext = context.applicationContext
            val startedAt = System.currentTimeMillis()
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
                    ?: System.currentTimeMillis()
                val receivedAt = System.currentTimeMillis()
                val subscriptionId = resolveSubscriptionId(source)
                val fingerprint = fingerprint(address, body, sentAt, subscriptionId)

                // 2a. 幂等：SMS_DELIVER 与 SMS_RECEIVED 可能双发，已入库则直接停止。
                if (wasPersisted(appContext, fingerprint)) {
                    Log.i(TAG, "minimal: duplicate ${source.action} ignored after successful persistence")
                    return
                }

                // 廉价过滤（微秒级，不查联系人）。命中时仍然写库保证不丢，只是不转发。
                val filteredOut = isFilteredCheap(appContext, address, body)
                if (filteredOut) {
                    Log.i(TAG, "minimal: $address matched user filter; will persist but skip forwarding")
                }

                // 1. 写系统短信库
                val requestedThreadId = appContext.getThreadId(address)
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
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "minimal: SMS provider insert failed", e)
                    PushPlusConfig(appContext).lastReceiverStatus =
                        "广播已到达，前台服务不可用且短信库写入失败：${e.message ?: e.javaClass.simpleName}"
                    SmsRecoveryWorker.enqueueNow(appContext)
                    -1L
                }

                if (insertedMessageId < 0L) {
                    // Provider 写入失败时转发是最后一份拷贝：SmsRecoveryWorker 扫的是 Provider，
                    // 没写进去的消息它不会发现，因此这里仍然必须入队转发（除非被用户规则拦截）。
                    if (!filteredOut) {
                        enqueueForwardingFallback(
                            context = appContext,
                            address = address,
                            body = body,
                            receivedAt = receivedAt,
                            subscriptionId = subscriptionId,
                            uniqueId = "sms-minimal-$receivedAt-${body.hashCode()}",
                            operationId = null,
                            ruleDecision = evaluateCheapRules(appContext, address, body, subscriptionId),
                        )
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
                if (System.currentTimeMillis() - startedAt < MINIMAL_BUDGET_MS) {
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
                        )
                    }.onFailure { error ->
                        Log.e(TAG, "minimal: local refresh failed (non-fatal)", error)
                    }
                } else {
                    Log.w(TAG, "minimal: skipped local sync, budget exhausted after ${System.currentTimeMillis() - startedAt}ms")
                }

                // 4. 入队转发（命中用户过滤规则时除外）。
                //    这里必须和第 3 步一样先过预算闸门：enqueueForwardingFallback 第一行就是
                //    ChannelRepository.getInstance(context)，冷进程下会跑 refresh()
                //    （detectConfiguredWeComStreams → RemoteSourceRepository → 按字段逐个
                //    Keystore decryptSensitiveConfig）+ importLegacyChannels()，实测数百毫秒～数秒，
                //    绝不是"毫秒级"，顶穿 goAsync 的 10s 窗口就是 ANR。
                //    闸门拦截后消息已在系统短信库中，入队补偿扫描由 SmsRecoveryWorker 接手转发。
                var budgetBlockedForwarding = false
                if (filteredOut) {
                    Log.i(TAG, "minimal: skipped forwarding for filtered sender $address (id=$insertedMessageId)")
                } else if (System.currentTimeMillis() - startedAt >= MINIMAL_BUDGET_MS) {
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
                        receivedAt = receivedAt,
                        subscriptionId = subscriptionId,
                        uniqueId = "sms-$insertedMessageId",
                        operationId = null,
                        // P1-B：降级期间也必须应用用户的渠道白名单，否则"屏蔽某渠道"会失效。
                        ruleDecision = evaluateCheapRules(appContext, address, body, subscriptionId),
                        threadId = resolvedMinimalThreadId,
                    )
                }

                PushPlusConfig(appContext).lastReceiverStatus = when {
                    filteredOut ->
                        "已接收并写入短信库（降级路径，命中过滤未转发），短信ID：$insertedMessageId，发送方：$address"
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

        /**
         * "廉价"过滤：只做 SharedPreferences 上的字符串匹配与系统屏蔽号判定，
         * 绝不触碰联系人库（那才会吃掉 goAsync 的 8 秒预算）。
         *
         * 顺序与 [IncomingSmsService.isFiltered] 完全一致：白名单 → 应用黑名单 →
         * 关键词 → 系统屏蔽号。FULL 路径在此之上再追加 blockUnknownNumbers 的联系人查询。
         *
         * **已知差异（P2-C，接受现状）**：`blockUnknownNumbers` 只在 FULL 路径生效。
         * MINIMAL（降级）与 RECOVERY（恢复）两条路径都**不做**联系人查询，因此"拦截未知号码"
         * 在这两条路径上不生效——代价是联系人库查询可能吃掉整个 goAsync 预算 / 拖慢 Worker，
         * 收益不足以抵消。三条路径在**廉价子集**（白名单/黑名单/关键词/系统屏蔽号）上完全一致。
         *
         * 关于 [Context.isNumberBlocked]：它对传入的号码列表做纯内存比对 + 正则，
         * 本身不查库；默认参数 getBlockedNumbers() 仅在"本应用是默认拨号器"时才去查
         * 系统 BlockedNumberContract（一张极小的表，不是联系人库），否则直接返回空列表。
         * 本应用是短信应用、几乎不可能是默认拨号器，因此这里实际是微秒级短路，安全可放入降级路径。
         */
        fun isFilteredCheap(context: Context, address: String, body: String): Boolean {
            if (context.config.isNumberWhitelisted(address)) return false
            if (context.config.isNumberBlacklisted(address)) return true
            if (isMessageFilteredOut(context, body)) return true
            return context.isNumberBlocked(address)
        }

        /**
         * 降级路径的"廉价规则子集"：只求值**渠道白名单 + 实例白名单**，不渲染模板、不查联系人。
         *
         * 规则求值本身是纯内存字符串匹配（不传 `resolveContent` 时引擎不会渲染模板），
         * 真正昂贵的是模板渲染与联系人查询，因此这个子集可以安全地放进 goAsync 预算内。
         *
         * 与 FULL 路径的差别：FULL 会用 `resolveContent` 逐目标渲染自定义模板并按
         * `ruleDecision.targets` 精准投递；降级路径不做模板渲染，只按
         * [ForwardingRuleDecision.allowedChannels] / [ForwardingRuleDecision.allowedInstanceIds]
         * 对启用渠道做白名单过滤，投递正文仍走默认格式化。
         *
         * @return null = 规则未启用 / 求值失败（不限制渠道，与改动前行为一致）；
         * 非 null = 只允许该决策允许的渠道类型与实例。
         */
        fun evaluateCheapRules(
            context: Context,
            address: String,
            body: String,
            subscriptionId: Int,
        ): ForwardingRuleDecision? {
            val rulesConfig = runCatching { ForwardingRulesConfig(context) }.getOrNull() ?: return null
            if (!rulesConfig.enabled) return null
            val enabledChannels = runCatching {
                buildSet {
                    if (PushPlusConfig(context).enabled) add(ForwardingChannels.PUSHPLUS)
                    addAll(MultiForwardConfig(context).enabledChannelIds())
                }
            }.getOrDefault(emptySet())
            val simSlotIndex = runCatching {
                if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_PHONE_STATE) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    context.subscriptionManagerCompat().getActiveSubscriptionInfo(subscriptionId)?.simSlotIndex
                } else {
                    null
                }
            }.getOrNull()
            return runCatching {
                ForwardingRuleEngine(rulesConfig.rules).evaluate(
                    sender = address,
                    body = body,
                    subscriptionId = subscriptionId,
                    channelCandidates = rulesConfig.channelCandidatesForScope(enabledChannels),
                    simSlotIndex = simSlotIndex,
                )
            }.onFailure { error ->
                Log.e(TAG, "minimal: cheap rule subset evaluation failed", error)
            }.getOrNull()
        }

        /**
         * 按实例逐一入队转发，并兼容尚未转换成实例的旧版渠道配置。
         * FULL 与 MINIMAL 两条路径共用，避免实现漂移。
         */
        fun enqueueForwardingFallback(
            context: Context,
            address: String,
            body: String,
            receivedAt: Long,
            subscriptionId: Int,
            uniqueId: String,
            operationId: String?,
            /**
             * 降级路径的廉价规则子集（见 [evaluateCheapRules]）。
             * null = 规则未启用 / 未求值 → 不限制渠道（与改动前行为一致）；
             * 非 null = 只允许 [ForwardingRuleDecision.allowedChannels] 内的渠道类型，
             * 以及 [ForwardingRuleDecision.allowedInstanceIds] 内的实例。
             */
            ruleDecision: ForwardingRuleDecision? = null,
            threadId: Long = 0L,
        ) {
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
                        "of ${enabledInstances.size} instance(s) for $address",
                )
            }

            allowedInstances.forEach { instance ->
                operationId?.let { ShadowRepository.recordDelivery(context, it, instance.id, "QUEUED") }
                MultiChannelForwardWorker.enqueueSingle(
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
        ) {
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
                senderName = address,
                senderPhotoUri = "",
                subscriptionId = subscriptionId,
            )
            context.messagesDB.insertOrUpdate(message)
            context.syncThreadToLocal(threadId)
            refreshMessages()
            refreshConversations()
        }

        private fun resolveSubscriptionId(intent: Intent): Int = listOf(
            "subscription",
            "subscription_id",
            "android.telephony.extra.SUBSCRIPTION_INDEX",
            "subscriptionIndex",
            "android.telephony.extra.SUBSCRIPTION_ID",
        ).map { intent.getIntExtra(it, SubscriptionManager.INVALID_SUBSCRIPTION_ID) }
            .firstOrNull { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID }
            ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID

        fun persistWithRetry(
            context: Context,
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
                    return context.insertNewSMS(
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
