package org.fossify.messages.outbox

import android.content.Context
import android.util.Log
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.helpers.RemoteCommandRepository
import org.fossify.messages.models.OutboxSourceType
import org.fossify.messages.models.OutboxTaskEntity
import org.fossify.messages.models.OutboxTaskType
import org.fossify.messages.models.SmsSendState
import org.fossify.messages.models.SmsSendTriggerType
import org.json.JSONObject

/**
 * SMS 发送 Outbox 执行器
 *
 * 职责：
 * 1. 将 OutboxTask(taskType = SEND_SMS) 解码并映射为标准短信发信调用；
 * 2. 严格的重复执行保护：若检测到已有处于 SUBMITTED / SENT / UNKNOWN_AFTER_SUBMIT 的发信事实，严禁二次调用底层发信；
 * 3. 关联 RemoteCommandExecution (若来源于远程指令)，更新其 sendOperationId 与生命周期；
 * 4. 遵守短信不可自动盲目重试原则，杜绝运营商二次扣费。
 */
class SendSmsOutboxExecutor : OutboxExecutor {

    override fun canExecute(taskType: String): Boolean = taskType == OutboxTaskType.SEND_SMS

    override suspend fun execute(context: Context, task: OutboxTaskEntity): OutboxExecutionResult {
        val payloadJson = task.payloadPayload ?: return OutboxExecutionResult.FatalFailure(
            errorClass = "IllegalArgumentException",
            errorMessage = "Missing payloadPayload for SEND_SMS outbox task ${task.taskId}"
        )

        val json = runCatching { JSONObject(payloadJson) }.getOrElse { error ->
            return OutboxExecutionResult.FatalFailure(
                errorClass = error.javaClass.name,
                errorMessage = "Invalid JSON in payloadPayload: ${error.message}"
            )
        }

        val target = json.optString("target", "").trim()
        val body = json.optString("body", "")
        val subId = json.optInt("subscriptionId", -1)
        val requireDeliveryReport = json.optBoolean("requireDeliveryReport", context.config.enableDeliveryReports)
        val triggerTypeName = json.optString("triggerType", SmsSendTriggerType.LEGACY_UNKNOWN.name)
        val triggerType = runCatching { SmsSendTriggerType.valueOf(triggerTypeName) }
            .getOrDefault(SmsSendTriggerType.LEGACY_UNKNOWN)

        if (target.isBlank() || body.isBlank()) {
            return OutboxExecutionResult.FatalFailure(
                errorClass = "IllegalArgumentException",
                errorMessage = "Target number or body is blank"
            )
        }

        // 1. 检查重复发送保护 (At-Most-Once / Idempotency Guard)
        if (task.sourceType == OutboxSourceType.REMOTE_COMMAND && task.sourceId.isNotBlank()) {
            val remoteCmd = context.getMessagesDB().RemoteCommandDao().findById(task.sourceId)
            val existingSendOpId = remoteCmd?.sendOperationId
            if (!existingSendOpId.isNullOrBlank()) {
                val existingOp = context.getMessagesDB().SmsSendDao().getOperationById(existingSendOpId)
                if (existingOp != null) {
                    val state = existingOp.state
                    if (state in setOf(
                            SmsSendState.SUBMITTED.name,
                            SmsSendState.SENT.name,
                            SmsSendState.DELIVERED.name,
                            SmsSendState.UNKNOWN_AFTER_SUBMIT.name
                        )
                    ) {
                        Log.w(TAG, "SmsSendOperation $existingSendOpId already in state $state. Skipping duplicate send.")
                        return OutboxExecutionResult.Success
                    }
                }
            }
        }

        // 2. 执行底层发送
        return try {
            val uris = context.messagingUtils.sendSmsMessage(
                text = body,
                addresses = setOf(target),
                subId = subId,
                requireDeliveryReport = requireDeliveryReport,
                triggerType = triggerType
            )

            // 3. 关联 RemoteCommandExecution (如果来源是远程指令)
            if (task.sourceType == OutboxSourceType.REMOTE_COMMAND && task.sourceId.isNotBlank()) {
                RemoteCommandRepository.recordExecutionSuccess(
                    context = context,
                    commandId = task.sourceId,
                    sendOperationId = null
                )
            }

            OutboxExecutionResult.Success
        } catch (e: Exception) {
            Log.w(TAG, "Failed to send SMS via outbox executor for task ${task.taskId}: ${e.message}")
            if (task.sourceType == OutboxSourceType.REMOTE_COMMAND && task.sourceId.isNotBlank()) {
                RemoteCommandRepository.recordExecutionFailure(
                    context = context,
                    commandId = task.sourceId,
                    errorClass = e.javaClass.name,
                    errorMessage = e.message
                )
            }
            OutboxExecutionResult.Retry(
                errorClass = e.javaClass.name,
                errorMessage = e.message
            )
        }
    }

    companion object {
        private const val TAG = "SendSmsOutboxExecutor"
    }
}
