package org.fossify.messages.outbox

import android.content.Context
import android.util.Log
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.ShadowRepository
import org.fossify.messages.messaging.sendMessageCompat
import org.fossify.messages.models.OutboxTaskEntity
import org.fossify.messages.models.OutboxTaskType
import org.fossify.messages.models.SmsSendState
import org.fossify.messages.models.SmsSendTriggerType
import org.json.JSONObject

/**
 * 短信直发转发渠道 Outbox 执行器
 *
 * 职责：
 * 1. 执行短信直发类转发任务 (FORWARD_SMS)；
 * 2. 具备严格的重复执行保护：若检测到已有处于 SUBMITTED / SENT 的发信事实，严禁二次调用基带发信；
 * 3. 关联阶段 1A ForwardingDelivery 状态回写。
 */
class ForwardSmsOutboxExecutor : OutboxExecutor {

    override fun canExecute(taskType: String): Boolean = taskType == OutboxTaskType.FORWARD_SMS

    override suspend fun execute(context: Context, task: OutboxTaskEntity): OutboxExecutionResult {
        val payloadJson = task.payloadPayload ?: return OutboxExecutionResult.FatalFailure(
            errorClass = "IllegalArgumentException",
            errorMessage = "Missing payloadPayload for FORWARD_SMS task ${task.taskId}"
        )

        val json = runCatching { JSONObject(payloadJson) }.getOrElse { error ->
            return OutboxExecutionResult.FatalFailure(
                errorClass = error.javaClass.name,
                errorMessage = "Invalid JSON in payloadPayload: ${error.message}"
            )
        }

        val phone = json.optString("phone", "").trim()
        val content = json.optString("content", "")
        val subId = json.optInt("subscriptionId", -1)
        val deliveryId = json.optString("deliveryId", "")
        val isTest = json.optBoolean("isTest", false)

        if (phone.isBlank() || content.isBlank()) {
            return OutboxExecutionResult.FatalFailure(
                errorClass = "IllegalArgumentException",
                errorMessage = "Target phone or content is blank"
            )
        }

        // 1. 重复发信保护检查
        if (deliveryId.isNotBlank()) {
            val existingDelivery = context.getMessagesDB().ShadowDaos().getDeliveryById(deliveryId)
            if (existingDelivery != null && existingDelivery.state in setOf("DELIVERED", "SUCCESS")) {
                Log.w(TAG, "Forwarding delivery $deliveryId already completed. Skipping duplicate SMS send.")
                return OutboxExecutionResult.Success
            }
        }

        // 2. 发送短信
        val triggerType = if (isTest) {
            SmsSendTriggerType.SMS_DIRECT_TEST
        } else {
            SmsSendTriggerType.FORWARDING_SMS_DIRECT
        }

        return try {
            context.sendMessageCompat(
                text = content,
                addresses = listOf(phone),
                subId = subId.takeIf { it >= 0 },
                attachments = emptyList(),
                propagateErrors = true,
                triggerType = triggerType
            )

            if (deliveryId.isNotBlank()) {
                ShadowRepository.recordForwardingDeliveryState(context, deliveryId, "DELIVERED")
            }

            OutboxExecutionResult.Success
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send forward SMS for task ${task.taskId}: ${e.message}")
            if (deliveryId.isNotBlank()) {
                ShadowRepository.recordForwardingDeliveryState(context, deliveryId, "FAILED")
            }
            OutboxExecutionResult.Retry(
                errorClass = e.javaClass.name,
                errorMessage = e.message
            )
        }
    }

    companion object {
        private const val TAG = "ForwardSmsOutboxExecutor"
    }
}
