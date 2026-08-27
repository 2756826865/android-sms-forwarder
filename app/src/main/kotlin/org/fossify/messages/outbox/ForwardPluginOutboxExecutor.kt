package org.fossify.messages.outbox

import android.content.Context
import org.fossify.messages.forwarding.plugin.ChannelPluginManager
import org.fossify.messages.forwarding.plugin.model.ChannelResult
import org.fossify.messages.forwarding.plugin.model.ForwardPayload
import org.fossify.messages.models.OutboxTaskEntity
import org.fossify.messages.models.OutboxTaskType
import org.json.JSONObject

/**
 * 插件化转发 Outbox 任务执行器
 */
class ForwardPluginOutboxExecutor : OutboxExecutor {

    override fun canExecute(taskType: String): Boolean {
        return taskType == OutboxTaskType.FORWARD_PLUGIN || taskType == OutboxTaskType.FORWARD_HTTP || taskType == OutboxTaskType.FORWARD_SMS
    }

    override suspend fun execute(context: Context, task: OutboxTaskEntity): OutboxExecutionResult {
        val payloadJson = runCatching {
            JSONObject(task.payloadPayload ?: "{}")
        }.getOrDefault(JSONObject())

        var pluginId = payloadJson.optString("pluginId", payloadJson.optString("channel", ""))
        if (pluginId.isBlank()) {
            pluginId = when (task.taskType) {
                OutboxTaskType.FORWARD_SMS -> "sms_direct"
                else -> "custom_webhook"
            }
        }

        val plugin = ChannelPluginManager.getPlugin(pluginId)
            ?: return OutboxExecutionResult.FatalFailure(
                errorClass = "UnknownPlugin",
                errorMessage = "Plugin '$pluginId' is not registered in ChannelPluginManager"
            )

        val targetConfigMap = mutableMapOf<String, String>()
        val configObj = payloadJson.optJSONObject("targetConfig") ?: payloadJson.optJSONObject("config")
        configObj?.keys()?.forEach { key ->
            targetConfigMap[key] = configObj.optString(key)
        }
        if (payloadJson.has("url")) targetConfigMap["url"] = payloadJson.optString("url")
        if (payloadJson.has("target_phone")) targetConfigMap["target_phone"] = payloadJson.optString("target_phone")
        if (payloadJson.has("phone")) targetConfigMap["target_phone"] = payloadJson.optString("phone")

        val metadataMap = mutableMapOf<String, String>()
        val metaObj = payloadJson.optJSONObject("metadata")
        metaObj?.keys()?.forEach { key ->
            metadataMap[key] = metaObj.optString(key)
        }
        if (payloadJson.has("send_operation_id")) metadataMap["send_operation_id"] = payloadJson.optString("send_operation_id")

        val forwardPayload = ForwardPayload(
            sourceMessageId = task.sourceId,
            senderHash = payloadJson.optString("senderHash", ""),
            bodyHash = task.payloadHmac,
            receivedTime = payloadJson.optLong("receivedTime", task.createdAt),
            metadata = metadataMap,
            targetConfig = targetConfigMap,
            rawContentForTransmission = payloadJson.optString("content", "")
        )

        return when (val result = plugin.send(context, forwardPayload)) {
            is ChannelResult.Success -> OutboxExecutionResult.Success
            is ChannelResult.Retry -> OutboxExecutionResult.Retry(
                errorClass = "ChannelRetryableFailure",
                errorMessage = result.reason
            )
            is ChannelResult.Failed -> OutboxExecutionResult.FatalFailure(
                errorClass = result.errorClass,
                errorMessage = result.errorMessage
            )
        }
    }
}
