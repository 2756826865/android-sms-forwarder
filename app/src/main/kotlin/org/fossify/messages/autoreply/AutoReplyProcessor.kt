package org.fossify.messages.autoreply

import android.content.Context
import android.util.Log
import org.fossify.messages.extensions.config
import org.fossify.messages.messaging.MessagingUtils
import org.fossify.messages.messaging.SimResolutionRequest
import org.fossify.messages.messaging.SubscriptionResolver
import org.fossify.messages.models.SmsSendTriggerType
import java.util.concurrent.TimeUnit

object AutoReplyProcessor {
    private const val TAG = "AutoReplyProcessor"
    
    sealed class Result {
        data class Executed(val ruleName: String, val replyContent: String, val toSender: String, val simSlot: Int, val delaySeconds: Int = 0, val ruleId: String = "") : Result()
        data class Skipped(val reason: String) : Result()
    }

    fun processIncoming(context: Context, senderNumber: String, messageBody: String, incomingSubId: Int, sourceId: String): Result {
        require(sourceId.isNotBlank()) { "缺少来源标识，已停止自动回复" }
        return synchronized(this) {
            val prepared = prepareIncoming(context, senderNumber, messageBody, incomingSubId)
            if (prepared is Result.Executed) {
                val rule = AutoReplyConfig(context).rules.first { it.id == prepared.ruleId }
                // Persist only a compact rule digest. WorkManager input has a 10 KiB limit.
                // No budget or deduplication claim is consumed before durable enqueue succeeds.
                val data = androidx.work.workDataOf(
                    "rule" to prepared.ruleId, "to" to prepared.toSender,
                    "ruleSnapshot" to ruleDigest(rule), "text" to prepared.replyContent,
                    "sub" to prepared.simSlot, "source" to sourceId
                )
                val request = androidx.work.OneTimeWorkRequestBuilder<AutoReplyWorker>()
                    .setInputData(data)
                    .setInitialDelay(prepared.delaySeconds.coerceIn(0, 30).toLong(), TimeUnit.SECONDS)
                    .build()
                val config = AutoReplyConfig(context)
                config.lastDecision = "规则 [${prepared.ruleName}] 自动回复排队中，尚未提交发送"
                try {
                    androidx.work.WorkManager.getInstance(context)
                        .enqueueUniqueWork("auto-reply-$sourceId", androidx.work.ExistingWorkPolicy.KEEP, request)
                        .result.get()
                } catch (e: Exception) {
                    config.lastDecision = "自动回复排队失败，未提交发送"
                    throw e
                }
            }
            prepared
        }
    }

    internal fun ruleDigest(rule: AutoReplyRule): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(rule.toString().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** One atomic reservation immediately before submission; unknown outcomes retain the claim. */
    internal fun reserveSubmission(context: Context, source: String, sender: String, rule: AutoReplyRule): Boolean =
        synchronized(this) {
            val config = AutoReplyConfig(context)
            val limits = context.getSharedPreferences("auto_reply_limits", Context.MODE_PRIVATE)
            val legacyClaims = context.getSharedPreferences("auto_reply_submission_claims", Context.MODE_PRIVATE)
            if (legacyClaims.contains(source) || limits.contains("submitted_$source")) return@synchronized false
            val today = getTodayKey()
            val count = if (limits.getString("day", "") == today) limits.getInt("count", 0) else 0
            val now = System.currentTimeMillis()
            val cooldownKey = "cooldown_" + android.net.Uri.encode(sender)
            val last = limits.getLong(cooldownKey, 0L)
            if (count >= config.dailyLimit ||
                (rule.rateLimitMinutes > 0 && now - last < TimeUnit.MINUTES.toMillis(rule.rateLimitMinutes.toLong()))) {
                config.lastDecision = "自动回复取消：已达到每日上限或号码冷却期"
                return@synchronized false
            }
            limits.edit().putString("day", today).putInt("count", count + 1)
                .putLong(cooldownKey, now).putLong("submitted_$source", now).commit()
        }

    private fun prepareIncoming(
        context: Context,
        senderNumber: String,
        messageBody: String,
        incomingSubId: Int
    ): Result {
        val config = AutoReplyConfig(context)
        if (!config.enabled) {
            return Result.Skipped("自动回复功能未启用")
        }

        val rules = config.rules.filter { it.enabled }
        if (rules.isEmpty()) {
            return Result.Skipped("无已启用的自动回复规则")
        }

        // Daily limit check
        val todayKey = getTodayKey()
        val limits = context.getSharedPreferences("auto_reply_limits", Context.MODE_PRIVATE)
        val currentDailyCount = if (limits.getString("day", "") == todayKey) limits.getInt("count", 0) else 0
        if (currentDailyCount >= config.dailyLimit) {
            val msg = "已达单日自动回复上限 (${config.dailyLimit} 条)"
            config.lastDecision = msg
            Log.w(TAG, msg)
            return Result.Skipped(msg)
        }

        for (rule in rules) {
            if (!matchesRule(rule, senderNumber, messageBody)) {
                continue
            }

            // Rate limit check for this sender
            if (rule.rateLimitMinutes > 0) {
                val lastReplyTime = limits.getLong("cooldown_" + android.net.Uri.encode(senderNumber), 0L)
                val cooldownMillis = TimeUnit.MINUTES.toMillis(rule.rateLimitMinutes.toLong())
                val now = System.currentTimeMillis()
                if (now - lastReplyTime < cooldownMillis) {
                    val minutesRemaining = ((cooldownMillis - (now - lastReplyTime)) / (1000 * 60)).coerceAtLeast(1)
                    val msg = "号码 $senderNumber 处于冷却期中 (设定冷却: ${rule.formatCooldownLabel()}，还剩约 ${minutesRemaining} 分钟)"
                    config.lastDecision = msg
                    Log.i(TAG, msg)
                    return Result.Skipped(msg)
                }
            }

            // Perform auto-reply
            val replyText = rule.replyContent.trim()
            if (replyText.isBlank()) {
                return Result.Skipped("规则 [${rule.name}] 回复内容为空")
            }

            val targetSubId = resolveSubscriptionId(context, rule.simScope, incomingSubId)
            
            return Result.Executed(
                ruleName = rule.name,
                replyContent = replyText,
                toSender = senderNumber,
                simSlot = targetSubId,
                delaySeconds = rule.delaySeconds,
                ruleId = rule.id
            )
        }

        return Result.Skipped("未匹配到适用的自动回复规则")
    }

    private fun matchesRule(rule: AutoReplyRule, sender: String, body: String): Boolean {
        // Sender filter
        if (rule.senderFilter.isNotBlank()) {
            val filters = rule.senderFilter.split(",", "，", " ").map { it.trim() }.filter { it.isNotEmpty() }
            val matched = filters.any { filter ->
                sender.contains(filter, ignoreCase = true) || filter == "*"
            }
            if (!matched) return false
        }

        // Include keywords
        if (rule.includeKeywords.isNotEmpty()) {
            val matched = rule.includeKeywords.any { kw ->
                body.contains(kw, ignoreCase = true)
            }
            if (!matched) return false
        }

        // Exclude keywords
        if (rule.excludeKeywords.isNotEmpty()) {
            val excluded = rule.excludeKeywords.any { kw ->
                body.contains(kw, ignoreCase = true)
            }
            if (excluded) return false
        }

        // Include Regex
        if (rule.includeRegex.isNotBlank()) {
            val matched = runCatching {
                Regex(rule.includeRegex, RegexOption.IGNORE_CASE).containsMatchIn(body)
            }.getOrDefault(false)
            if (!matched) return false
        }

        return true
    }

    private fun resolveSubscriptionId(context: Context, simScope: String, incomingSubId: Int): Int {
        val configuredMode = when (simScope) {
            AutoReplyRule.SIM_SAME -> SubscriptionResolver.MODE_FOLLOW_RECEIVE
            AutoReplyRule.SIM_1 -> SubscriptionResolver.MODE_SIM1
            AutoReplyRule.SIM_2 -> SubscriptionResolver.MODE_SIM2
            else -> SubscriptionResolver.MODE_DEFAULT
        }
        val result = SubscriptionResolver.resolve(
            context,
            SimResolutionRequest(
                targetAddress = null,
                receivedSubId = incomingSubId,
                configuredMode = configuredMode,
                allowFallback = false
            )
        )
        check(result.isSuccessful) { "自动回复指定发送卡不可用，已停止发送：${result.errorReason}" }
        return result.resolvedSubscriptionId
    }

    internal fun sendSms(context: Context, destination: String, text: String, subId: Int) {
        check(AutoReplyConfig(context).enabled) { "自动回复已关闭，取消发送" }
        try {
            // 统一走应用发送链：先写系统短信 Provider 和本地 DB，再创建发送观测记录，
            // 最后由 SmsSender 提交。避免自动回复发送成功却不显示在会话和运行大盘。
            MessagingUtils(context).sendSmsMessage(
                text = text,
                addresses = setOf(destination),
                subId = subId,
                requireDeliveryReport = context.config.enableDeliveryReports,
                triggerType = SmsSendTriggerType.AUTO_REPLY
            )
            Log.i(TAG, "Auto-reply submitted")
        } catch (e: Exception) {
            Log.w(TAG, "Auto-reply submission failed: ${e.javaClass.simpleName}")
            throw e
        }
    }

    private fun getTodayKey(): String {
        val cal = java.util.Calendar.getInstance()
        return "${cal.get(java.util.Calendar.YEAR)}_${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
    }
}
