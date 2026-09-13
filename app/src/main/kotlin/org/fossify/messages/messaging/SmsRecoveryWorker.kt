package org.fossify.messages.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.syncThreadToLocal
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.ForwardingHistoryStore
import org.fossify.messages.forwarding.ForwardingMessageFormatter
import org.fossify.messages.forwarding.ForwardingRuleEngine
import org.fossify.messages.forwarding.ForwardingRulesConfig
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.PushPlusConfig
import org.fossify.messages.forwarding.PushPlusWorker
import org.fossify.messages.forwarding.RuleTemplateMode
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.remote.RemoteSmsCommandProcessor
import org.fossify.messages.services.IncomingSmsService
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import org.fossify.messages.helpers.ShadowRepository

/** Repairs SMS broadcasts delayed or suppressed by aggressive OEM background policies. */
class SmsRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        // 首次安装（水位不存在）时只登记水位、不回溯：否则新装用户第一次扫描就会把过去 N 天的
        // 历史短信全量转发到所有通道并执行其中的远程指令（P0-5 短信轰炸）。
        val storedLastChecked = prefs.getLong(KEY_LAST_CHECKED, 0L)
        // 首次运行 = 水位尚未登记（新装 / prefs 被清）。此时一律只登记水位，不回放任何历史短信。
        val firstRun = storedLastChecked <= 0L
        val lastChecked = if (firstRun) now else storedLastChecked
        val baseSince = if (firstRun) {
            now
        } else {
            (lastChecked - OVERLAP_MS).coerceAtLeast(now - MAX_LOOKBACK_MS)
        }
        // 一次性深回溯入参（见 enqueueFullResync）。只影响本次扫描，不写回水位。
        // 首次运行时忽略该入参：开机广播 / 回前台都会触发 FullResync，若不忽略，新装用户在
        // 首次扫描时仍会回放 FULL_RESYNC_LOOKBACK_MS 内的历史短信（场景 B 的残留路径）。
        val forcedSince = if (firstRun) 0L else inputData.getLong(KEY_FORCED_SINCE, 0L)
        val since = if (forcedSince > 0L) minOf(baseSince, forcedSince) else baseSince
        // 只取 id 列，避免数万条短信时把整表（含正文）加载进内存导致 OOM
        val localIds = applicationContext.messagesDB.getAllIds().toHashSet()
        // P1-A：恢复扫描**不能**只用 "id in localIds" 判重。
        // 降级路径的步骤 3（写 Room）与步骤 4（入队转发）各有独立的预算闸门：步骤 3 成功、步骤 4
        // 被闸门拦掉时，消息已经在 Room 里却**从未入队转发**。此时只用 id 判重会让恢复扫描永远
        // 跳过它 → 短信入库但永不转发（两个机制各自正确、合起来却是新洞）。
        // MultiChannelForwardWorker.enqueueSingle 在**入队时**就调用 ForwardingHistoryStore.registerQueued
        // (workId = uniqueId)，因此"历史里存在 sms-<id> 前缀的记录"等价于"这条短信已经进过转发队列"。
        // 扫描开始时读一次即可：扫描中自己新产生的记录不需要在本次扫描内立刻可见（每行只处理一次）。
        val forwardedMessageIds = readForwardedMessageIds()
        var newestSeen = lastChecked
        var repairedAny = false

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.SUBSCRIPTION_ID,
            Telephony.Sms.READ,
        )
        val selection = "${Telephony.Sms.TYPE}=? AND ${Telephony.Sms.DATE}>?"
        val args = arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString(), since.toString())

        // 显式 try/catch 而非 runCatching：runCatching 会把 CancellationException 一起吞掉，
        // 使 WorkManager 的取消无法正常传播（协程取消语义被破坏）。这里先原样重抛。
        try {
            applicationContext.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                selection,
                args,
                "${Telephony.Sms.DATE} ASC",
            )?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val subIndex = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
                val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)

                val threadIdsToSync = mutableSetOf<Long>()
                ShadowRepository.incrementCounter(applicationContext, "RECOVERY_SCAN_STARTED")
                
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idIndex)
                    val address = cursor.getString(addressIndex).orEmpty()
                    val body = cursor.getString(bodyIndex).orEmpty()
                    val date = cursor.getLong(dateIndex)
                    val threadId = cursor.getLong(threadIndex)
                    val subscriptionId = if (subIndex >= 0) {
                        cursor.getInt(subIndex)
                    } else {
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    }
                    val isRead = cursor.getInt(readIndex) == 1
                    newestSeen = maxOf(newestSeen, date)
                    
                    if (address.isBlank()) {
                        ShadowRepository.incrementCounter(applicationContext, "RECOVERY_ITEM_SKIPPED")
                        continue
                    }

                    val uniqueId = "sms-$id"
                    val alreadyLocal = id in localIds
                    // 与 FULL / MINIMAL 两条链路保持一致的过滤：白名单优先 → 应用黑名单 →
                    // 关键词 → 系统屏蔽号（微秒级，不查联系人；blockUnknownNumbers 是已知差异，
                    // 见 IncomingSmsService.isFilteredCheap 的注释）。
                    // 必须放在转发之前：否则降级路径命中过滤、且 Room 写入被预算闸门跳过时，
                    // 这条短信会被本轮扫描当成"新短信"，绕过用户规则直接转发到 TG / Webhook / 邮件。
                    val filteredOut = IncomingSmsService.isFilteredCheap(applicationContext, address, body)

                    if (filteredOut) {
                        // 三路径统一语义：过滤只决定"是否转发/通知"，不决定"是否入库"。
                        // 已在 Room 里的不动；不在的补进本地库（与降级路径一致），否则它会持续以
                        // 「Provider 有、Room 没有」的形态被后续每一轮扫描重复检出。
                        ShadowRepository.incrementCounter(applicationContext, "RECOVERY_ITEM_FILTERED")
                        if (!alreadyLocal) {
                            threadIdsToSync.add(threadId)
                            repairedAny = true
                        }
                        continue
                    }

                    // P1-A：已入 Room **且** 已登记过转发 → 真正"处理过"，跳过。
                    // 只在 alreadyLocal 时才查转发记录：不在 Room 的消息必然从未处理过，
                    // 没必要多付一次历史查询。
                    if (alreadyLocal && id in forwardedMessageIds) {
                        ShadowRepository.incrementCounter(applicationContext, "RECOVERY_ITEM_SKIPPED")
                        continue
                    }

                    // alreadyLocal 但没登记过转发 = 降级路径"写了 Room 却没来得及转发"（P1-A 的洞）。
                    // 这种情形只补转发，**不重复通知、不消费远程指令**：降级路径本来就把这两件事
                    // 列为"明确不做"，恢复路径无权替它补做决定——远程指令会真的发出短信，是风险
                    // 最高的操作，宁可不执行（缺一次指令是 fail-safe，误执行不是）。
                    val backfillForwardingOnly = alreadyLocal
                    if (backfillForwardingOnly) {
                        ShadowRepository.incrementCounter(applicationContext, "RECOVERY_FORWARD_BACKFILLED")
                    } else {
                        ShadowRepository.incrementCounter(applicationContext, "RECOVERY_ITEM_FOUND")
                        threadIdsToSync.add(threadId)
                    }
                    repairedAny = true

                    val pushPlus = PushPlusConfig(applicationContext)

                    val multiConfig = MultiForwardConfig(applicationContext)
                    val rulesConfig = ForwardingRulesConfig(applicationContext)
                    val enabledForwardChannels = buildSet {
                        if (pushPlus.enabled) add(ForwardingChannels.PUSHPLUS)
                        addAll(multiConfig.enabledChannelIds())
                    }
                    val simSlotIndex = resolveSimSlotIndex(subscriptionId)
                    // P2-E：与 FULL 路径一样传 resolveContent，否则规则配了自定义模板时，
                    // 恢复补发的正文会退回默认模板（与正常链路不一致）。
                    val ruleDecision = if (rulesConfig.enabled) {
                        ForwardingRuleEngine(rulesConfig.rules).evaluate(
                            sender = address,
                            body = body,
                            subscriptionId = subscriptionId,
                            channelCandidates = rulesConfig.channelCandidatesForScope(enabledForwardChannels),
                            simSlotIndex = simSlotIndex,
                            resolveContent = { rule, action, text ->
                                val template = action.customTemplate
                                    .takeIf { action.templateMode == RuleTemplateMode.CUSTOM }
                                    ?: rule.customTemplate.takeIf(String::isNotBlank)
                                if (template != null) {
                                    ForwardingMessageFormatter.renderRuleTemplate(
                                        context = applicationContext,
                                        template = template,
                                        sender = address,
                                        body = text,
                                        receivedAt = date,
                                        subscriptionId = subscriptionId,
                                    )
                                } else {
                                    ForwardingMessageFormatter.format(
                                        context = applicationContext,
                                        sender = address,
                                        body = text,
                                        receivedAt = date,
                                        subscriptionId = subscriptionId,
                                    ).content
                                }
                            },
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
                        rulesConfig.lastDecision = "补偿恢复 · $address · $blockedNames · ${ruleDecision.reason}"
                    }
                    val remoteCommandAllowed = !rulesConfig.affectsRemoteCommands() ||
                        !rulesConfig.enabled ||
                        rulesConfig.rules.none { it.enabled } ||
                        ruleDecision?.matchedRules?.isNotEmpty() == true
                    // 补转发场景不消费远程指令（理由见 backfillForwardingOnly 处注释）。
                    val remoteCommandConsumed = if (backfillForwardingOnly) {
                        false
                    } else {
                        RemoteSmsCommandProcessor.tryConsume(
                            context = applicationContext,
                            sender = address,
                            body = body,
                            subscriptionId = subscriptionId,
                            messageTimestamp = date,
                            messageId = id,
                            allowExecution = remoteCommandAllowed,
                        )
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
                                    receivedAt = date,
                                    subscriptionId = subscriptionId,
                                    detail = "转发规则未允许：${ruleDecision.reason}",
                                )
                            }
                    }

                    val allowedForwardChannels = ruleDecision?.allowedChannels
                    if (!remoteCommandConsumed && (multiConfig.anyEnabled() || pushPlus.enabled)) {
                        val targets = ruleDecision?.targets.orEmpty()
                        if (targets.isNotEmpty()) {
                            // P2-E：与 FULL 路径一致——按规则生成的目标精准投递，并携带规则渲染后的正文
                            // （`bodyAlreadyRendered = true`，避免 Worker 再用默认模板覆盖）。
                            targets.forEach { target ->
                                MultiChannelForwardWorker.enqueueSingle(
                                    context = applicationContext,
                                    sender = address,
                                    body = target.renderedContent,
                                    receivedAt = date,
                                    subscriptionId = subscriptionId,
                                    uniqueId = "$uniqueId-${target.ruleId.take(8)}-${target.actionId.take(8)}",
                                    targetChannel = target.channelType,
                                    allowedChannels = if (target.instanceId.isNotBlank()) {
                                        setOf(target.instanceId)
                                    } else {
                                        setOf(target.channelType)
                                    },
                                    isTest = false,
                                    operationId = null,
                                    targetInstanceId = target.instanceId,
                                    ruleId = target.ruleId,
                                    actionId = target.actionId,
                                    bodyAlreadyRendered = true,
                                )
                            }
                        } else {
                            MultiChannelForwardWorker.enqueue(
                                context = applicationContext,
                                sender = address,
                                body = body,
                                receivedAt = date,
                                subscriptionId = subscriptionId,
                                uniqueId = uniqueId,
                                allowedChannels = buildMultiChannelAllowedChannels(rulesConfig, allowedForwardChannels, multiConfig),
                            )
                        }
                    }

                    if (backfillForwardingOnly) {
                        // 补转发后必须留下"已登记转发"的痕迹，否则下一轮扫描会重复补发。
                        // （例：所有渠道都被规则拦掉时一次 enqueue 都不会发生，也就没有任何记录。）
                        ensureForwardingMarker(
                            uniqueId = uniqueId,
                            address = address,
                            body = body,
                            receivedAt = date,
                            subscriptionId = subscriptionId,
                        )
                    } else if (!isRead) {
                        val namePhoto = runCatching {
                            applicationContext.getNameAndPhotoFromPhoneNumber(address)
                        }.getOrNull()
                        val senderName = namePhoto?.name?.takeIf { it.isNotBlank() } ?: address
                        val photoUri = namePhoto?.photoUri.orEmpty()
                        applicationContext.showReceivedMessageNotification(
                            messageId = id,
                            address = address,
                            senderName = senderName,
                            body = body,
                            threadId = threadId,
                            bitmap = applicationContext.getNotificationBitmap(photoUri),
                        )
                    }
                }
                
                // 对本轮扫描出的 threadId 批量去重后执行同步，避免查询放大
                threadIdsToSync.forEach { threadId ->
                    ShadowRepository.incrementCounter(applicationContext, "LEGACY_RECOVERY_ACTION_OBSERVED")
                    applicationContext.syncThreadToLocal(threadId)
                }
                
                ShadowRepository.incrementCounter(applicationContext, "RECOVERY_SCAN_COMPLETED")
            }
        } catch (e: CancellationException) {
            // WorkManager 取消（协程取消）必须原样传播，否则会被误当成扫描失败而 retry。
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "recovery scan failed", e)
            return Result.retry()
        }

        prefs.edit().putLong(KEY_LAST_CHECKED, newestSeen).apply()
        if (repairedAny) {
            refreshMessages()
            refreshConversations()
        }
        return Result.success()
    }

    /**
     * 读出"已登记过转发入队"的短信 id 集合（P1-A 判重依据）。
     *
     * workId 约定为 `sms-<messageId>`，或其后缀变体
     * `sms-<messageId>-<instanceId | channel | ruleId8-actionId8>`——见各处 enqueue 调用点。
     *
     * 为什么用 [ForwardingHistoryStore] 而不是另建存储：`MultiChannelForwardWorker.enqueueSingle`
     * 在**入队时**（不是执行时）就会 `registerQueued(workId = uniqueId)`，所以"历史里存在该前缀
     * 记录"天然等价于"这条短信已经进过转发队列"，无需新增持久化结构。
     *
     * 已知边界：[ForwardingHistoryStore] 只保留最近 200 条记录，极老的短信可能查不到记录，
     * 此时会退化为"再转发一次"。这是刻意的保守偏差——宁可重发，不可漏发。
     */
    private fun readForwardedMessageIds(): Set<Long> {
        return runCatching {
            ForwardingHistoryStore(applicationContext).records()
                .mapNotNull { record -> parseSmsWorkId(record.workId) }
                .toSet()
        }.getOrDefault(emptySet())
    }

    /**
     * 从 workId 中解析短信 id：`sms-12` / `sms-12-xxx` → 12。
     *
     * 必须是完整数字段，避免 `sms-12` 误配 `sms-1234`。
     */
    private fun parseSmsWorkId(workId: String): Long? {
        if (!workId.startsWith(SMS_WORK_ID_PREFIX)) return null
        val rest = workId.removePrefix(SMS_WORK_ID_PREFIX)
        val digits = rest.takeWhile { it in '0'..'9' }
        if (digits.isEmpty()) return null
        if (rest.length > digits.length && rest[digits.length] != '-') return null
        return digits.toLongOrNull()
    }

    /**
     * 补转发之后确保留下"已登记转发"的痕迹。
     *
     * 正常情形由 [MultiChannelForwardWorker.enqueueSingle] 自己写；但当所有渠道都被规则拦掉、
     * 或渠道实例不可用时，一次 enqueue 都不会发生，也就没有痕迹 → 下一轮扫描会再次尝试补发
     * （周期任务每 15 分钟一次）。这里补一条 `registerSkipped` 把它标记为"已处理"。
     */
    private fun ensureForwardingMarker(
        uniqueId: String,
        address: String,
        body: String,
        receivedAt: Long,
        subscriptionId: Int,
    ) {
        runCatching {
            val history = ForwardingHistoryStore(applicationContext)
            val alreadyMarked = history.records()
                .any { it.workId == uniqueId || it.workId.startsWith("$uniqueId-") }
            if (!alreadyMarked) {
                history.registerSkipped(
                    workId = uniqueId,
                    channel = "system",
                    sender = address,
                    body = body,
                    receivedAt = receivedAt,
                    subscriptionId = subscriptionId,
                    detail = "恢复补转发：无可用投递目标或全部被转发规则拦截",
                )
            }
        }.onFailure { error ->
            Log.w(TAG, "forwarding backfill marker failed", error)
        }
    }

    private fun resolveSimSlotIndex(subscriptionId: Int): Int? {
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return null
        return runCatching {
            val manager = applicationContext.getSystemService(SubscriptionManager::class.java)
            manager?.getActiveSubscriptionInfo(subscriptionId)?.simSlotIndex
        }.getOrNull()
    }

    private fun buildMultiChannelAllowedChannels(
        rulesConfig: ForwardingRulesConfig,
        allowedForwardChannels: Set<String>?,
        multiConfig: MultiForwardConfig,
    ): Set<String>? {
        if (!rulesConfig.enabled) return null
        var channels = allowedForwardChannels ?: emptySet()
        if (rulesConfig.scope == ForwardingRulesConfig.SCOPE_FORWARDING_ONLY && multiConfig.smsDirectEnabled) {
            channels = channels + ForwardingChannels.SMS_DIRECT
        }
        return channels
    }

    companion object {
        private const val TAG = "SmsRecoveryWorker"
        /** 转发 workId 前缀约定：`sms-<messageId>`（含后缀变体）。 */
        private const val SMS_WORK_ID_PREFIX = "sms-"
        private const val PREFS = "sms_recovery_state"
        private const val KEY_LAST_CHECKED = "last_checked"
        private const val KEY_LAST_FOREGROUND_RESYNC = "last_foreground_resync"
        private const val FOREGROUND_RESYNC_MIN_INTERVAL_MS = 60_000L
        private const val UNIQUE_NOW = "sms-recovery-now"
        private const val UNIQUE_PERIODIC = "sms-recovery-periodic"
        private const val KEY_FORCED_SINCE = "forced_since"
        private const val OVERLAP_MS = 60 * 1000L
        /** 水位落后于当前时间时（OEM 冻结 / doze / 关机）最多回补的时间跨度。 */
        private const val MAX_LOOKBACK_MS = 6 * 60 * 60 * 1000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SmsRecoveryWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                // KEEP (而非 UPDATE)：App.kt 与 MainActivity 每次冷启动都会调用 schedule()，
                // UPDATE 会把 15 分钟计时反复重置，OEM 强杀场景下周期任务几乎永不真正执行。
                // 首次调用时无既有任务，KEEP 与 UPDATE 行为一致，仍会入队。
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun enqueueNow(context: Context, inputData: Data = Data.EMPTY) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SmsRecoveryWorker>().setInputData(inputData).build(),
            )
        }

        /**
         * Force a longer lookback (for screen-off swallow / missed SMS_DELIVER).
         *
         * 深回溯以「一次性入参」下发给本次任务，**不再回拨 [KEY_LAST_CHECKED] 水位**：
         * 回拨水位会把补偿窗口持久化，下一次扫描会对同一批历史短信再次全量转发（P0-5 短信轰炸），
         * 且该路径由开机广播与前台恢复自动触发，无法限流。改为入参后，水位始终按实际扫到的
         * 最新时间单向前进，深回溯范围固定为 [FULL_RESYNC_LOOKBACK_MS]。
         */
        fun enqueueFullResync(context: Context) {
            enqueueNow(
                context,
                workDataOf(KEY_FORCED_SINCE to (System.currentTimeMillis() - FULL_RESYNC_LOOKBACK_MS)),
            )
        }

        /**
         * 回前台 / 解锁补偿同步入口。
         *
         * 与 [enqueueFullResync] 的区别是带最小间隔节流：用户频繁切前台时不会反复触发重量级扫描，
         * 但首次调用 (无节流记录) 必定执行，保证升级后第一次回前台一定能补偿。
         */
        fun enqueueForegroundResync(
            context: Context,
            minIntervalMs: Long = FOREGROUND_RESYNC_MIN_INTERVAL_MS,
        ) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastEnqueuedAt = prefs.getLong(KEY_LAST_FOREGROUND_RESYNC, 0L)
            if (lastEnqueuedAt != 0L && now - lastEnqueuedAt in 0L until minIntervalMs) return
            prefs.edit().putLong(KEY_LAST_FOREGROUND_RESYNC, now).apply()
            enqueueFullResync(context)
        }

        fun markObserved(context: Context, receivedAt: Long) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = prefs.getLong(KEY_LAST_CHECKED, 0L)
            if (receivedAt > previous) prefs.edit().putLong(KEY_LAST_CHECKED, receivedAt).apply()
        }

        private const val FULL_RESYNC_LOOKBACK_MS = 60 * 60 * 1000L
    }
}
