package org.fossify.messages.autoreply

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import org.fossify.messages.extensions.config
import org.fossify.messages.messaging.MessagingUtils
import org.fossify.messages.messaging.SimResolutionRequest
import org.fossify.messages.messaging.SubscriptionResolver
import org.fossify.messages.models.SmsSendTriggerType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object AutoReplyProcessor {
    private const val TAG = "AutoReplyProcessor"
    
    // In-memory rate limiting map: "sender_phone" -> timestampMillis
    private val lastReplyTimeMap = ConcurrentHashMap<String, Long>()
    
    // Daily count tracking: "day_key" -> count
    private val dailyCountMap = ConcurrentHashMap<String, Int>()

    sealed class Result {
        data class Executed(val ruleName: String, val replyContent: String, val toSender: String, val simSlot: Int) : Result()
        data class Skipped(val reason: String) : Result()
    }

    fun processIncoming(
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
        val currentDailyCount = dailyCountMap[todayKey] ?: 0
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
                val lastReplyTime = lastReplyTimeMap[senderNumber] ?: 0L
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
            
            // Execute send
            sendSms(context, senderNumber, replyText, targetSubId, rule.delaySeconds)

            // Update rate limit trackers
            val now = System.currentTimeMillis()
            lastReplyTimeMap[senderNumber] = now
            dailyCountMap[todayKey] = currentDailyCount + 1

            val decision = "规则 [${rule.name}] 成功向 $senderNumber 自动回复: $replyText"
            config.lastDecision = decision
            Log.i(TAG, decision)

            return Result.Executed(
                ruleName = rule.name,
                replyContent = replyText,
                toSender = senderNumber,
                simSlot = targetSubId
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
                allowFallback = true
            )
        )
        return if (result.isSuccessful) result.resolvedSubscriptionId else {
            if (incomingSubId >= 0) incomingSubId else SmsManager.getDefaultSmsSubscriptionId()
        }
    }

    private fun sendSms(context: Context, destination: String, text: String, subId: Int, delaySeconds: Int) {
        if (delaySeconds > 0) {
            try {
                Thread.sleep((delaySeconds * 1000L).coerceAtMost(30000L))
            } catch (_: InterruptedException) {}
        }

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
            Log.i(TAG, "Successfully sent auto-reply SMS to $destination (subId=$subId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send auto-reply SMS to $destination", e)
            throw e
        }
    }

    private fun getTodayKey(): String {
        val cal = java.util.Calendar.getInstance()
        return "${cal.get(java.util.Calendar.YEAR)}_${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
    }
}
