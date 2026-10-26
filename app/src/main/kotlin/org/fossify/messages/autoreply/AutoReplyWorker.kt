package org.fossify.messages.autoreply

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.fossify.messages.messaging.SimResolutionRequest
import org.fossify.messages.messaging.SubscriptionResolver

/** Delayed reply, with a durable one-submit claim. Unknown outcomes are never retried. */
class AutoReplyWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val source = inputData.getString("source") ?: return Result.failure()
        val target = inputData.getString("to") ?: return Result.failure()
        val text = inputData.getString("text") ?: return Result.failure()
        val sub = inputData.getInt("sub", -1)
        val config = AutoReplyConfig(applicationContext)
        val rule = config.rules.firstOrNull { it.id == inputData.getString("rule") }
            ?: return Result.failure()
        if (AutoReplyProcessor.ruleDigest(rule) != inputData.getString("ruleSnapshot")) return Result.failure()
        if (!config.enabled || !rule.enabled || rule.replyContent.trim() != text) return Result.failure()
        val resolved = SubscriptionResolver.resolve(applicationContext,
            SimResolutionRequest(receivedSubId = sub, configuredMode = when (rule.simScope) {
                AutoReplyRule.SIM_SAME -> SubscriptionResolver.MODE_FOLLOW_RECEIVE
                AutoReplyRule.SIM_1 -> SubscriptionResolver.MODE_SIM1
                AutoReplyRule.SIM_2 -> SubscriptionResolver.MODE_SIM2
                else -> SubscriptionResolver.MODE_DEFAULT
            }, allowFallback = false))
        if (!resolved.isSuccessful || resolved.resolvedSubscriptionId != sub) return Result.failure()
        if (!AutoReplyProcessor.reserveSubmission(applicationContext, source, target, rule)) return Result.failure()
        return try {
            AutoReplyProcessor.sendSms(applicationContext, target, text, sub)
            config.lastDecision = "规则 [${rule.name}] 自动回复已提交，最终结果请查看发送记录"
            Result.success()
        } catch (e: Exception) {
            config.lastDecision = "自动回复提交失败或结果不确定，未自动重试"
            android.util.Log.w("AutoReplyWorker", "Submission failed: ${e.javaClass.simpleName}")
            Result.failure()
        }
    }
}
