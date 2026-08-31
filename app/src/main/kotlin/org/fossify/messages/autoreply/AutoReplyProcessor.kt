package org.fossify.messages.autoreply

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
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
        return when (simScope) {
            AutoReplyRule.SIM_SAME -> incomingSubId
            AutoReplyRule.SIM_1 -> 1
            AutoReplyRule.SIM_2 -> 2
            else -> if (incomingSubId > 0) incomingSubId else SmsManager.getDefaultSmsSubscriptionId()
        }
    }

    private fun sendSms(context: Context, destination: String, text: String, subId: Int, delaySeconds: Int) {
        if (delaySeconds > 0) {
            try {
                Thread.sleep((delaySeconds * 1000L).coerceAtMost(30000L))
            } catch (_: InterruptedException) {}
        }

        try {
            val smsManager = if (subId > 0 && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java).createForSubscriptionId(subId)
            } else if (subId > 0) {
                @Suppress("DEPRECATION")
                SmsManager.getSmsManagerForSubscriptionId(subId)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(text)
            if (parts.size > 1) {
                smsManager.sendMultipartTextMessage(destination, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(destination, null, text, null, null)
            }
            Log.i(TAG, "Successfully sent auto-reply SMS to $destination (subId=$subId)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send auto-reply SMS to $destination", e)
        }
    }

    private fun getTodayKey(): String {
        val cal = java.util.Calendar.getInstance()
        return "${cal.get(java.util.Calendar.YEAR)}_${cal.get(java.util.Calendar.DAY_OF_YEAR)}"
    }
}
