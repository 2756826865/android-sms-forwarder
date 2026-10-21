package org.fossify.messages.forwarding.plugin.impl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.extensions.messagingUtils
import org.fossify.messages.forwarding.plugin.ForwardChannelPlugin
import org.fossify.messages.forwarding.plugin.model.ChannelResult
import org.fossify.messages.forwarding.plugin.model.ForwardPayload
import org.fossify.messages.models.SmsSendState
import org.fossify.messages.models.SmsSendTriggerType
import org.fossify.messages.forwarding.SmsDirectSendGuard

/**
 * 短信直发转发插件 (SMS_DIRECT)
 *
 * 严格边界保护：
 * 1. 绝对禁止插件直接调用 SmsManager；
 * 2. 统一通过 messagingUtils -> SmsSendCoordinator 事实状态机外发；
 * 3. 杜绝自动循环重发，严守防重复扣费红线。
 */
class SmsDirectChannelPlugin(
    override val pluginId: String = "sms_direct",
    override val displayName: String = "SMS Direct Forwarding"
) : ForwardChannelPlugin {

    override fun validateConfig(config: Map<String, String>): Boolean {
        val targetPhone = config["target_phone"] ?: config["phone"] ?: config["destination"]
        return !targetPhone.isNullOrBlank()
    }

    override suspend fun send(context: Context, payload: ForwardPayload): ChannelResult = withContext(Dispatchers.IO) {
        val destination = payload.targetConfig["target_phone"] ?: payload.targetConfig["phone"] ?: payload.targetConfig["destination"]
        if (destination.isNullOrBlank()) {
            return@withContext ChannelResult.Failed(
                errorClass = "InvalidConfig",
                errorMessage = "Destination phone number is missing in targetConfig"
            )
        }
        if ((payload.metadata["outbox_attempt_count"]?.toIntOrNull() ?: 0) > 0) {
            return@withContext ChannelResult.Failed("SmsDirectRetryBlocked", "旧短信直发任务结果未确认，已阻止自动重发")
        }
        val source = payload.metadata["sms_direct_source"]?.takeIf(String::isNotBlank)
            ?: payload.metadata["outbox_task_id"]?.takeIf(String::isNotBlank)?.let { "outbox:$it" }
            ?: payload.sourceMessageId.takeIf(String::isNotBlank)?.let { "plugin:$it" }
            ?: return@withContext ChannelResult.Failed("MissingSendIdentity", "短信直发缺少稳定任务标识，未发送")

        val text = payload.rawContentForTransmission ?: ""
        val subId = payload.targetConfig["subscription_id"]?.toIntOrNull() ?: -1

        val existingOpId = payload.metadata["send_operation_id"]
        if (!existingOpId.isNullOrBlank()) {
            val existingOp = runCatching { context.getMessagesDB().SmsSendDao().getOperationById(existingOpId) }.getOrNull()
            if (existingOp != null && (existingOp.state == SmsSendState.SENT.name || existingOp.state == SmsSendState.DELIVERED.name)) {
                return@withContext ChannelResult.Success
            }
            if (existingOp != null) {
                return@withContext ChannelResult.Failed("ExistingSmsOperation", "已有短信发送记录，结果未确认；未再次提交，请先核对收件端")
            }
        }

        try {
            var submitted = false
            SmsDirectSendGuard.sendPrepared(context, source, destination) { before ->
                val uris = context.messagingUtils.sendSmsMessage(
                    text = text,
                    addresses = setOf(destination),
                    subId = subId,
                    requireDeliveryReport = false,
                    triggerType = SmsSendTriggerType.FORWARDING_SMS_DIRECT,
                    beforeBillableSubmit = before
                )
                submitted = uris.isNotEmpty()
            }
            if (submitted) {
                ChannelResult.Success
            } else {
                ChannelResult.Failed("SendFailed", "No message URI returned from sendSmsMessage")
            }
        } catch (e: Exception) {
            ChannelResult.Failed(e.javaClass.simpleName, e.message ?: "SMS send failed")
        }
    }
}
