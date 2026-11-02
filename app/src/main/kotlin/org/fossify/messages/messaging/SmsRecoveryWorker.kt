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
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.fossify.messages.extensions.conversationsDB
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
import org.fossify.messages.forwarding.RuleTemplateMode
import org.fossify.messages.helpers.ShadowRepository
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.services.IncomingSmsService

/** Repairs SMS broadcasts delayed or suppressed by aggressive OEM background policies. */
class SmsRecoveryWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = scanMutex.withLock {
        try {
            runScan()
        } finally {
            if (isStopped) Log.i(TAG, "recovery stopped reason=$stopReason; checkpoint retained")
        }
    }

    // Legacy channel dispatch remains in one place so queue/receipt semantics are not rewritten in
    // this repair.
    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth")
    private suspend fun runScan(): Result {
        val journal = SmsIngressRecovery(applicationContext)
        SmsIngressSpool(applicationContext).drain()
        SmsIngressReview(applicationContext).reconcile()
        SmsAlertRecoveryWorker.enqueue(applicationContext)
        runCatching { SmsIngressMaintenance(applicationContext).clean() }
        val drainStarted = android.os.SystemClock.elapsedRealtime()
        for (intent in journal.pending()) {
            coroutineContext.ensureActive()
            if (android.os.SystemClock.elapsedRealtime() - drainStarted >= DRAIN_BUDGET_MS) break
            IncomingSmsService.processMinimal(applicationContext, intent)
        }
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) !=
            PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        // Reconcile original channel tasks before history-based SMS dedupe. Never re-route old SMS.
        org.fossify.messages.forwarding.ForwardingQueueReconciler.run(applicationContext, allowRepair = true)

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val storedLastChecked = prefs.getLong(KEY_LAST_CHECKED, 0L)
        val scanState = SmsRecoveryScanState(applicationContext)
        val forcedSince = inputData.getLong(KEY_FORCED_SINCE, 0L)
        val legacyRequest = runAttemptCount == 0 && !inputData.getBoolean("v128_trigger", false)
        if (forcedSince > 0 && storedLastChecked > 0 && legacyRequest)
            scanState.request(forcedSince)
        val scanIdle = !scanState.hasRequest() && !scanState.hasActivePage()
        val intakePending = journal.hasPending() ||
            SmsIngressSpool(applicationContext).hasPending()
        if (inputData.getBoolean("v128_trigger", false) && scanIdle && !intakePending)
            return Result.success()
        val installAt =
            applicationContext.packageManager
                .getPackageInfo(applicationContext.packageName, 0)
                .firstInstallTime
        val page = scanState.begin(storedLastChecked, now, installAt)
        val since = page.date
        val scanStarted = android.os.SystemClock.elapsedRealtime()
        var scanned = 0
        var lastDate = page.date
        var lastId = page.id
        var exhausted = true
        // 只取 id 列，避免数万条短信时把整表（含正文）加载进内存导致 OOM
        val forwardedWorkIds by lazy {
            ForwardingHistoryStore(applicationContext).workIds().toSet() }
        // P1-A：恢复扫描**不能**只用 "id in localIds" 判重。
        // 降级路径的步骤 3（写 Room）与步骤 4（入队转发）各有独立的预算闸门：步骤 3 成功、步骤 4
        // 被闸门拦掉时，消息已经在 Room 里却**从未入队转发**。此时只用 id 判重会让恢复扫描永远
        // 跳过它 → 短信入库但永不转发（两个机制各自正确、合起来却是新洞）。
        // 既有转发记录由上面的任务核对器按原目标处理；这里继续禁止重新匹配规则、整条短信重放。
        // 排队记录只证明已登记，不能证明已送达；无法证明安全的旧任务会标记未知，等待用户核实。
        // 扫描开始时读一次即可：扫描中自己新产生的记录不需要在本次扫描内立刻可见（每行只处理一次）。
        val forwardedMessageIds by lazy { readForwardedMessageIds() }
        var repairedAny = false
        val threadIdsToSync = mutableSetOf<Long>()
        val requiredSmsIds = mutableMapOf<Long, MutableSet<Long>>()

        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.DATE_SENT,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.SUBSCRIPTION_ID,
            Telephony.Sms.READ,
        )
        val selection =
            "${Telephony.Sms.TYPE}=? AND (${Telephony.Sms.DATE}>? OR " +
                "(${Telephony.Sms.DATE}=? AND ${Telephony.Sms._ID}>?)) AND ${Telephony.Sms.DATE}<=?"
        val args = arrayOf(Telephony.Sms.MESSAGE_TYPE_INBOX.toString(), since.toString(),
                since.toString(),
                page.id.toString(),
                page.until.toString())

        // 显式 try/catch 而非 runCatching：runCatching 会把 CancellationException 一起吞掉，
        // 使 WorkManager 的取消无法正常传播（协程取消语义被破坏）。这里先原样重抛。
        try {
            // Keep provider scans usable if an OEM rejects ORDER BY LIMIT.
            val order = "${Telephony.Sms.DATE} ASC, ${Telephony.Sms._ID} ASC"
            val cursor = try {
                applicationContext.contentResolver.query(
                    Telephony.Sms.CONTENT_URI, projection, selection, args,
                    "$order LIMIT ${SCAN_PAGE_SIZE + 1}",
                )
            } catch (error: RuntimeException) {
                if (error is SecurityException) throw error
                Log.w(TAG, "bounded SMS scan rejected by Provider; retrying ordered query", error)
                applicationContext.contentResolver.query(
                    Telephony.Sms.CONTENT_URI, projection, selection, args, order,
                )
            }
            cursor?.use { cursor ->
                val idIndex = cursor.getColumnIndexOrThrow(Telephony.Sms._ID)
                val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                val sentIndex = cursor.getColumnIndex(Telephony.Sms.DATE_SENT)
                val threadIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.THREAD_ID)
                val subIndex = cursor.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
                val readIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.READ)

                // One bounded Room lookup per table, before taking the receipt-processing lock.
                val pageIds = mutableListOf<Long>()
                val pageThreads = mutableSetOf<Long>()
                // Bound preflight even when an OEM silently ignores SQL LIMIT.
                while (pageIds.size <= SCAN_PAGE_SIZE && cursor.moveToNext()) {
                    pageIds.add(cursor.getLong(idIndex))
                    pageThreads.add(cursor.getLong(threadIndex))
                }
                if (pageIds.size > SCAN_PAGE_SIZE) exhausted = false
                cursor.moveToPosition(-1)
                val localIds = applicationContext.messagesDB.getExistingIds(pageIds).toSet()
                val localThreads = applicationContext.conversationsDB
                    .getExistingThreadIds(pageThreads.toList()).toSet()
                ShadowRepository.incrementCounter(applicationContext, "RECOVERY_SCAN_STARTED")
                
                while (scanned < pageIds.size && cursor.moveToNext()) {
                        coroutineContext.ensureActive()
                        if (
                            scanned >= SCAN_PAGE_SIZE ||
                                android.os.SystemClock.elapsedRealtime() - scanStarted >=
                                    SCAN_BUDGET_MS
                        ) {
                            exhausted = false
                            break
                        }
                        // Previous row is complete (including all its skip paths) before advancing
                        // its checkpoint.
                        scanned++
                        kotlinx.coroutines.yield()
                        synchronized(SmsIngressJournal.processLock) {
                            val id = cursor.getLong(idIndex)
                            val alreadyLocal =
                                id in localIds
                            val address = cursor.getString(addressIndex).orEmpty()
                    val body = cursor.getString(bodyIndex).orEmpty()
                    val date = cursor.getLong(dateIndex)
                    val threadId = cursor.getLong(threadIndex)
                    if (!alreadyLocal && threadId > 0L) requiredSmsIds.getOrPut(threadId) { mutableSetOf() }.add(id)
                    val rawSubscriptionId = if (subIndex >= 0) {
                        cursor.getInt(subIndex)
                    } else {
                        SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    }
                    val subscriptionId =
                                ReceivingProviderIdentity.confirmedSubscription(
                                    applicationContext,
                                    id,
                                    rawSubscriptionId,
                                )
                            val isRead = cursor.getInt(readIndex) == 1
                            lastDate = date
                            lastId = id
                            if (threadId > 0L && (!alreadyLocal ||
                                threadId !in localThreads)) {
                                threadIdsToSync.add(threadId)
                                repairedAny = true
                            }

                            if (address.isBlank()) {
                        ShadowRepository.incrementCounter(applicationContext, "RECOVERY_ITEM_SKIPPED")
                                return@synchronized
                            }

                    val ingress = SmsIngressIdentity(applicationContext).provider(address, body,
                        if (sentIndex >= 0) cursor.getLong(sentIndex) else 0L, date, subscriptionId, id)
                    val uniqueId = ingress.workId
                            if (journal.ownsProvider(id, date) || journal.completedWork(uniqueId))
                                return@synchronized
                            // A previous sync may have written the message but failed before
                            // creating
                            // conversation metadata. Repair that UI-only gap even for
                            // forwarded/filtered
                            // messages; keep forwarding and command replay decisions below
                            // unchanged.
                            if (alreadyLocal && threadId > 0L && threadId !in localThreads) {
                        threadIdsToSync.add(threadId)
                        repairedAny = true
                    }
                    // 与 FULL / MINIMAL 两条链路保持一致的过滤：白名单优先 → 应用黑名单 →
                    // 关键词 → 系统屏蔽号（微秒级，不查联系人；blockUnknownNumbers 是已知差异，
                    // 见 IncomingSmsService.isFilteredCheap 的注释）。
                    // 必须放在转发之前：否则降级路径命中过滤、且 Room 写入被预算闸门跳过时，
                    // 这条短信会被本轮扫描当成"新短信"，绕过用户规则直接转发到 TG / Webhook / 邮件。
                    val filteredOut = IncomingSmsService.isFilteredForReceive(applicationContext, address, body)

                    if (filteredOut) {
                        // 三路径统一语义：过滤只决定"是否转发/通知"，不决定"是否入库"。
                        // 已在 Room 里的不动；不在的补进本地库（与降级路径一致），否则它会持续以
                        // 「Provider 有、Room 没有」的形态被后续每一轮扫描重复检出。
                        ShadowRepository.incrementCounter(applicationContext, "RECOVERY_ITEM_FILTERED")
                        if (!alreadyLocal) {
                            threadIdsToSync.add(threadId)
                            repairedAny = true
                        }
                                return@synchronized
                            }

                            val providerAlreadyForwarded =
                                uniqueId == "sms-$id" && id in forwardedMessageIds
                            val ownerAlreadyForwarded = forwardedWorkIds.any {
                                it == uniqueId || it.startsWith("$uniqueId-")
                            }
                            if (providerAlreadyForwarded || ownerAlreadyForwarded) {
                        if (!alreadyLocal) { threadIdsToSync.add(threadId)
                                    repairedAny = true }
                        ShadowRepository.incrementCounter(applicationContext, "RECOVERY_ITEM_SKIPPED")
                                return@synchronized
                            }

                    // alreadyLocal 但没登记过转发 = 降级路径"写了 Room 却没来得及转发"（P1-A 的洞）。
                    // 这种情形只补转发，**不重复通知、不消费远程指令**：降级路径本来就把这两件事
                    // 列为"明确不做"，恢复路径无权替它补做决定——远程指令会真的发出短信，是风险
                    // 最高的操作，宁可不执行（缺一次指令是 fail-safe，误执行不是）。
                    val backfillForwardingOnly = alreadyLocal || ingress.workId.startsWith("sms-minimal-")
                            // Suppressing command/notification replay must not suppress missing UI
                            // data.
                            if (!alreadyLocal && threadId > 0L) threadIdsToSync.add(threadId)
                    if (backfillForwardingOnly) {
                        ShadowRepository.incrementCounter(applicationContext, "RECOVERY_FORWARD_BACKFILLED")
                    } else {
                        ShadowRepository.incrementCounter(applicationContext, "RECOVERY_ITEM_FOUND")
                    }
                    repairedAny = true

                    val pushPlus = PushPlusConfig(applicationContext)

                    val multiConfig = MultiForwardConfig(applicationContext)
                    val rulesConfig = ForwardingRulesConfig(applicationContext)
                    val enabledForwardChannels = buildSet {
                        if (pushPlus.enabled) add(ForwardingChannels.PUSHPLUS)
                        addAll(multiConfig.enabledChannelIds())
                    }
                    val historicalSim =
                                org.fossify.messages.forwarding.ForwardingSimSnapshot(
                                    -1,
                                    "未知接收卡（历史卡槽不可核验）",
                                    "",
                                    "",
                                    subscriptionId,
                                    source = "provider_recovery",
                                    observedAt = date,
                                )
                            val simSlotIndex: Int? = null
                            // P2-E：与 FULL 路径一样传 resolveContent，否则规则配了自定义模板时，
                    // 恢复补发的正文会退回默认模板（与正常链路不一致）。
                    val ruleDecision = if (rulesConfig.enabled) {
                        ForwardingRuleEngine(rulesConfig.rules) { date }
                                        .evaluate(
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
                                                        simSnapshot = historicalSim,
                                                    )
                                } else {
                                    ForwardingMessageFormatter.format(
                                        context = applicationContext,
                                        sender = address,
                                        body = text,
                                        receivedAt = date,
                                        subscriptionId = subscriptionId,
                                                            simSnapshot = historicalSim,
                                    ).content
                                }
                            },
                        )
                    } else {
                        null
                    }
                    if (ruleDecision?.blockedChannels?.isNotEmpty() == true) {
                        val blockedNames = ruleDecision.blockedChannels
                            .map(ForwardingChannels::displayName)
                            .joinToString("、")
                        rulesConfig.lastDecision = "补偿恢复 · $address · $blockedNames · ${ruleDecision.reason}"
                    }
                            // Historical recovery never executes remote commands.
                            val remoteCommandConsumed = false

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

                            if (!remoteCommandConsumed && (multiConfig.anyEnabled() || pushPlus.enabled)) {
                        val targets = ruleDecision?.targets.orEmpty()
                        if (targets.isNotEmpty()) {
                            // P2-E：与 FULL 路径一致——按规则生成的目标精准投递，并携带规则渲染后的正文
                            // （`bodyAlreadyRendered = true`，避免 Worker 再用默认模板覆盖）。
                            targets.forEach { target ->
                                MultiChannelForwardWorker.enqueueSingle(
                                            originalSim = historicalSim,
                                            context = applicationContext,
                                    sender = address,
                                    body = target.renderedContent,
                                    receivedAt = ingress.receivedAt,
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
                                    allowSimLabelPrefix = target.allowSimLabelPrefix,
                                )
                            }
                        } else {
                            IncomingSmsService.enqueueForwardingFallback(
                                context = applicationContext, address = address, body = body,
                                receivedAt = ingress.receivedAt, subscriptionId = subscriptionId,
                                uniqueId = uniqueId, operationId = null, ruleDecision = ruleDecision,
                                threadId = threadId,
                                        originalSim = historicalSim,
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
                    }
                    ShadowRepository.incrementCounter(applicationContext, "RECOVERY_SCAN_COMPLETED")
            } ?: error("sms_provider_cursor_unavailable")
            for (threadId in threadIdsToSync.filter { it > 0L }) {
                coroutineContext.ensureActive()
                applicationContext.syncThreadToLocal(threadId,
                    requiredSmsIds = requiredSmsIds[threadId].orEmpty(), requireProvider = true)
                check(applicationContext.conversationsDB.getConversationWithThreadId(threadId) != null) {
                    "recovery_conversation_not_saved"
                }
                check(requiredSmsIds[threadId].orEmpty().all {
                    applicationContext.messagesDB.getMessageWithId(it)?.let { message ->
                        message.threadId == threadId && !message.isMMS
                    } == true
                }) { "recovery_messages_not_saved" }
            }
        } catch (e: CancellationException) {
            // WorkManager 取消（协程取消）必须原样传播，否则会被误当成扫描失败而 retry。
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "recovery scan failed", e)
            return Result.retry()
        }

        if (scanned > 0) scanState.checkpoint(page, lastDate, lastId)
        if (exhausted) {
            synchronized(SmsRecoveryScanState.lock) {
                check(
                    prefs.edit().putLong(KEY_LAST_CHECKED,
                            maxOf(prefs.getLong(KEY_LAST_CHECKED, 0L), page.until),
                        )
                        .commit()
                )
                scanState.complete(page)
            }
        }
        if (repairedAny) {
            refreshMessages()
            refreshConversations()
        }
        val continueScan = !exhausted || scanState.hasRequest() || journal.hasReadyPending()
        if (continueScan) enqueueRunner(applicationContext, Data.EMPTY)
        val retryPending = journal.hasPending() ||
            SmsIngressSpool(applicationContext).hasPending()
        return if (!continueScan && retryPending) Result.retry() else Result.success()
    }

    /**
     * 读出"已登记过转发入队"的短信 id 集合（P1-A 判重依据）。
     *
     * workId 约定为 `sms-<messageId>`，或其后缀变体 `sms-<messageId>-<instanceId | channel |
     * ruleId8-actionId8>`——见各处 enqueue 调用点。
     *
     * 为什么用 [ForwardingHistoryStore] 而不是另建存储：`MultiChannelForwardWorker.enqueueSingle`
     * 在**入队时**（不是执行时）就会 `registerQueued(workId = uniqueId)`，所以"历史里存在该前缀
     * 记录"天然等价于"这条短信已经进过转发队列"，无需新增持久化结构。
     *
     * 已知边界：[ForwardingHistoryStore] 保留最近 2000 条完成记录及尚未结束的任务，极老的短信可能查不到记录，
     * 此时会退化为"再转发一次"。这是刻意的保守偏差——宁可重发，不可漏发。
     */
    private fun readForwardedMessageIds(): Set<Long> {
        return runCatching {
            ForwardingHistoryStore(applicationContext).workIds()
                .mapNotNull(::parseSmsWorkId)
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
     * 正常情形由 [MultiChannelForwardWorker.enqueueSingle] 自己写；但当所有渠道都被规则拦掉、 或渠道实例不可用时，一次 enqueue
     * 都不会发生，也就没有痕迹 → 下一轮扫描会再次尝试补发 （周期任务每 15 分钟一次）。这里补一条 `registerSkipped` 把它标记为"已处理"。
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

    companion object {
        private val scanMutex = Mutex()
        private const val PERIODIC_MINUTES = 15L
        private const val DRAIN_BUDGET_MS = 5000L
        private const val SCAN_PAGE_SIZE = 100
        private const val SCAN_BUDGET_MS = 15_000L
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

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SmsRecoveryWorker>(PERIODIC_MINUTES, TimeUnit.MINUTES).build()
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
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val stored = prefs.getLong(KEY_LAST_CHECKED, 0L)
            val requested = inputData.getLong(KEY_FORCED_SINCE, 0L)
            SmsRecoveryScanState(context)
                .request(
                    if (requested > 0 && stored > 0) minOf(requested, stored)
                    else if (stored > 0) stored else System.currentTimeMillis()
                )
            enqueueRunner(context, inputData, continuation = false)
        }

        private fun enqueueRunner(context: Context, inputData: Data, continuation: Boolean = true) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NOW,
                if (continuation) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SmsRecoveryWorker>().setInputData(
                            Data.Builder()
                                .putAll(inputData)
                                .putBoolean("v128_trigger", true)
                                .build()
                        ).build(),
            )
            if (!continuation) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "$UNIQUE_NOW-follow-up", ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<SmsRecoveryWorker>()
                        .setInitialDelay(2, TimeUnit.SECONDS)
                        .setInputData(Data.Builder().putBoolean("v128_trigger", true).build()).build())
            }

        }

        /**
         * Request all unfinished work from persisted checkpoints; no misleading fixed "full"
         * window.
         */
        fun enqueueFullResync(context: Context) {
            enqueueNow(
                context)
        }

        /**
         * 回前台 / 解锁补偿同步入口。
         *
         * 与 [enqueueFullResync] 的区别是带最小间隔节流：用户频繁切前台时不会反复触发重量级扫描， 但首次调用 (无节流记录)
         * 必定执行，保证升级后第一次回前台一定能补偿。
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
            // Receipt observation must never skip an unscanned interval.
            prefs.edit().putLong("last_observed", receivedAt).apply()
        }
    }
}
