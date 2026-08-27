package org.fossify.messages.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber
import org.fossify.messages.extensions.getNotificationBitmap
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.showReceivedMessageNotification
import org.fossify.messages.extensions.syncThreadToLocal
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.ForwardingHistoryStore
import org.fossify.messages.forwarding.ForwardingRuleEngine
import org.fossify.messages.forwarding.ForwardingRulesConfig
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.PushPlusConfig
import org.fossify.messages.forwarding.PushPlusWorker
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import org.fossify.messages.remote.RemoteSmsCommandProcessor
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
        val lastChecked = prefs.getLong(KEY_LAST_CHECKED, now - FIRST_LOOKBACK_MS)
        val since = (lastChecked - OVERLAP_MS).coerceAtLeast(now - MAX_LOOKBACK_MS)
        val localIds = applicationContext.messagesDB.getAll().asSequence().map { it.id }.toHashSet()
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

        runCatching {
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
                    
                    if (id in localIds || address.isBlank()) {
                        ShadowRepository.incrementCounter(applicationContext, "RECOVERY_ITEM_SKIPPED")
                        continue
                    }

                    ShadowRepository.incrementCounter(applicationContext, "RECOVERY_ITEM_FOUND")
                    threadIdsToSync.add(threadId)
                    repairedAny = true
                    val uniqueId = "sms-$id"
                    val pushPlus = PushPlusConfig(applicationContext)

                    val multiConfig = MultiForwardConfig(applicationContext)
                    val rulesConfig = ForwardingRulesConfig(applicationContext)
                    val enabledForwardChannels = buildSet {
                        if (pushPlus.enabled) add(ForwardingChannels.PUSHPLUS)
                        addAll(multiConfig.enabledChannelIds())
                    }
                    val simSlotIndex = resolveSimSlotIndex(subscriptionId)
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
                        rulesConfig.lastDecision = "补偿恢复 · $address · $blockedNames · ${ruleDecision.reason}"
                    }
                    val remoteCommandAllowed = !rulesConfig.affectsRemoteCommands() ||
                        !rulesConfig.enabled ||
                        rulesConfig.rules.none { it.enabled } ||
                        ruleDecision?.matchedRules?.isNotEmpty() == true
                    val remoteCommandConsumed = RemoteSmsCommandProcessor.tryConsume(
                        context = applicationContext,
                        sender = address,
                        body = body,
                        subscriptionId = subscriptionId,
                        messageTimestamp = date,
                        messageId = id,
                        allowExecution = remoteCommandAllowed,
                    )

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
                    if (pushPlus.enabled && !remoteCommandConsumed && (allowedForwardChannels == null || ForwardingChannels.PUSHPLUS in allowedForwardChannels)) {
                        PushPlusWorker.enqueue(applicationContext, address, body, date, subscriptionId, uniqueId)
                    }
                    if (!remoteCommandConsumed && multiConfig.anyEnabled()) {
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

                    if (!isRead) {
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
        }.onFailure {
            return Result.retry()
        }

        prefs.edit().putLong(KEY_LAST_CHECKED, newestSeen).apply()
        if (repairedAny) {
            refreshMessages()
            refreshConversations()
        }
        return Result.success()
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
        var channels = allowedForwardChannels
            ?.filter { it != ForwardingChannels.PUSHPLUS }
            ?.toSet()
            ?: emptySet()
        if (rulesConfig.scope == ForwardingRulesConfig.SCOPE_FORWARDING_ONLY && multiConfig.smsDirectEnabled) {
            channels = channels + ForwardingChannels.SMS_DIRECT
        }
        return channels
    }

    companion object {
        private const val PREFS = "sms_recovery_state"
        private const val KEY_LAST_CHECKED = "last_checked"
        private const val UNIQUE_NOW = "sms-recovery-now"
        private const val UNIQUE_PERIODIC = "sms-recovery-periodic"
        private const val FIRST_LOOKBACK_MS = 7 * 24 * 60 * 60 * 1000L
        private const val OVERLAP_MS = 60 * 1000L
        private const val MAX_LOOKBACK_MS = 7 * 24 * 60 * 60 * 1000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SmsRecoveryWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_PERIODIC,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        fun enqueueNow(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_NOW,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SmsRecoveryWorker>().build(),
            )
        }

        /** Force a longer lookback (for screen-off swallow / missed SMS_DELIVER). */
        fun enqueueFullResync(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val forcedSince = now - FULL_RESYNC_LOOKBACK_MS
            val previous = prefs.getLong(KEY_LAST_CHECKED, 0L)
            if (previous == 0L || previous > forcedSince) {
                prefs.edit().putLong(KEY_LAST_CHECKED, forcedSince).commit()
            }
            enqueueNow(context)
        }

        fun markObserved(context: Context, receivedAt: Long) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val previous = prefs.getLong(KEY_LAST_CHECKED, 0L)
            if (receivedAt > previous) prefs.edit().putLong(KEY_LAST_CHECKED, receivedAt).apply()
        }

        private const val FULL_RESYNC_LOOKBACK_MS = 24 * 60 * 60 * 1000L
    }
}
