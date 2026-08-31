package org.fossify.messages.forwarding

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionManager
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ForwardingPayload(val title: String, val content: String)

object ForwardingMessageFormatter {
    fun format(
        context: Context,
        sender: String,
        body: String,
        receivedAt: Long,
        subscriptionId: Int,
        titlePrefix: String = "",
        includeSender: Boolean = true,
        includeSim: Boolean = true,
        includeTime: Boolean = true,
    ): ForwardingPayload {
        val config = MultiForwardConfig(context)
        val contactName = runCatching {
            context.getNameAndPhotoFromPhoneNumber(sender).name
        }.getOrNull()?.takeIf { it.isNotBlank() && it != sender }
        val senderTitle = contactName ?: sender.ifBlank { "新短信" }
        
        val sim = if (includeSim && subscriptionId >= 0) {
            getSimDescription(context, config, subscriptionId)
        } else {
            ""
        }
        
        val receiverNumber = if (subscriptionId >= 0) {
            getReceiverNumber(context, config, subscriptionId)
        } else {
            ""
        }

        val title = buildList {
            if (titlePrefix.isNotBlank()) add(titlePrefix.trim())
            if (sim.isNotBlank()) add("【$sim】")
            add(senderTitle)
        }.joinToString(" ").ifBlank { "新短信" }

        val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            .format(Date(receivedAt))
            
        val content = when (config.templateMode) {
            MultiForwardConfig.TEMPLATE_STANDARD -> buildList {
                add(body)
                if (includeSender && contactName != null && sender.isNotBlank()) add("号码：$sender")
                if (receiverNumber.isNotBlank()) add("接收号码：$receiverNumber")
                if (includeTime) add("接收时间：$formattedTime")
            }

            MultiForwardConfig.TEMPLATE_DETAILED -> buildList {
                add(body)
                if (includeSender && sender.isNotBlank()) add("发送号码：$sender")
                if (includeSim && sim.isNotBlank()) add("卡槽：$sim")
                if (receiverNumber.isNotBlank()) add("接收号码：$receiverNumber")
                if (includeTime) add("接收时间：$formattedTime")
                add("设备：${TemplateDataRetriever.getDeviceName()}")
            }

            MultiForwardConfig.TEMPLATE_EMOJI -> buildList {
                add("📩新短信通知")
                if (includeSender && sender.isNotBlank()) add("📞号码：$sender")
                if (receiverNumber.isNotBlank()) add("📲接收：$receiverNumber")
                if (includeTime) add("⏰时间：$formattedTime")
                add("💬消息：$body")
                if (includeSim && sim.isNotBlank()) add("📶卡槽：$sim")
                add("🔋电池：${TemplateDataRetriever.getBatteryInfo(context)}")
            }

            MultiForwardConfig.TEMPLATE_CUSTOM -> {
                val customTemplate = config.customTemplate
                if (customTemplate.isNotBlank()) {
                    val code = org.fossify.messages.rule.template.TemplateRenderer.extractVerificationCode(body)
                    val dateOnly = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(receivedAt))
                    val timeOnly = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(receivedAt))
                    val simSlotIdx = if (subscriptionId >= 0) subscriptionId.toString() else "1"

                    val result = customTemplate
                        // 1. 验证码提取 (核心修复)
                        .replace("{{CODE}}", code)
                        .replace("{{code}}", code)
                        .replace("{code}", code)
                        .replace("{{VERIFICATION_CODE}}", code)
                        // 2. 发件人相关
                        .replace("{{FROM}}", sender)
                        .replace("{{SENDER}}", sender)
                        .replace("{sender}", sender)
                        .replace("{from}", sender)
                        .replace("{{CONTACT_NAME}}", contactName ?: sender)
                        .replace("{{NAME}}", contactName ?: sender)
                        .replace("{name}", contactName ?: sender)
                        // 3. 短信正文
                        .replace("{{SMS}}", body)
                        .replace("{{BODY}}", body)
                        .replace("{{CONTENT}}", body)
                        .replace("{sms}", body)
                        .replace("{body}", body)
                        // 4. 时间相关
                        .replace("{{RECEIVE_TIME}}", formattedTime)
                        .replace("{{TIME}}", formattedTime)
                        .replace("{time}", formattedTime)
                        .replace("{{DATE_YMD}}", dateOnly)
                        .replace("{{DATE}}", dateOnly)
                        .replace("{date}", dateOnly)
                        .replace("{{DATE_HMS}}", timeOnly)
                        .replace("{{TIME_HMS}}", timeOnly)
                        .replace("{{TIMESTAMP}}", receivedAt.toString())
                        // 5. 卡槽与接收号码
                        .replace("{{SIM_SLOT}}", sim)
                        .replace("{{CARD_SLOT}}", sim)
                        .replace("{sim}", sim)
                        .replace("{{SIM_INDEX}}", simSlotIdx)
                        .replace("{{SIM_ID}}", simSlotIdx)
                        .replace("{{RECEIVER_NUMBER}}", receiverNumber)
                        .replace("{{RECEIVER}}", receiverNumber)
                        .replace("{receiver}", receiverNumber)
                        // 6. 设备与网络状态
                        .replace("{{DEVICE_NAME}}", TemplateDataRetriever.getDeviceName())
                        .replace("{{DEVICE_BRAND}}", TemplateDataRetriever.getDeviceBrand())
                        .replace("{{DEVICE_MODEL}}", TemplateDataRetriever.getDeviceModel())
                        .replace("{{BATTERY_INFO}}", TemplateDataRetriever.getBatteryInfo(context))
                        .replace("{{BATTERY_PCT}}", TemplateDataRetriever.getBatteryPct(context))
                        .replace("{{IP_LIST}}", TemplateDataRetriever.getIpAddress())
                        .replace("{{NET_TYPE}}", TemplateDataRetriever.getNetworkType(context))
                        .replace("{{APP_VERSION}}", TemplateDataRetriever.getAppVersion())
                        .replace("{{CURRENT_TIME}}", TemplateDataRetriever.getCurrentTime())
                    
                    listOf(result)
                } else {
                    buildList {
                        add(body)
                        if (includeTime) add("接收时间：$formattedTime")
                    }
                }
            }

            else -> buildList {
                add(body)
                if (includeTime) add("接收时间：$formattedTime")
            }
        }.joinToString("\n")
        
        return ForwardingPayload(title, content)
    }

    @SuppressLint("MissingPermission")
    private fun getSimDescription(
        context: Context,
        config: MultiForwardConfig,
        subscriptionId: Int,
    ): String = runCatching {
        val manager = context.getSystemService(SubscriptionManager::class.java)
        val info = manager.getActiveSubscriptionInfo(subscriptionId)
        val custom = info?.simSlotIndex?.let(config::customSimLabel).orEmpty()
        if (custom.isNotBlank()) return@runCatching custom
        val slot = info?.simSlotIndex?.plus(1)?.let { "SIM$it" } ?: "SIM"
        val carrier = info?.carrierName?.toString().orEmpty()
        listOf(slot, carrier).filter(String::isNotBlank).joinToString(" · ")
    }.getOrDefault("SIM")

    @SuppressLint("MissingPermission", "HardwareIds")
    private fun getReceiverNumber(
        context: Context,
        config: MultiForwardConfig,
        subscriptionId: Int,
    ): String = runCatching {
        if (subscriptionId < 0) return ""
        val manager = context.getSystemService(SubscriptionManager::class.java)
        val info = manager.getActiveSubscriptionInfo(subscriptionId)
        val custom = info?.simSlotIndex?.let(config::customSimNumber).orEmpty()
        if (custom.isNotBlank()) return@runCatching custom
        @Suppress("DEPRECATION")
        info?.number ?: ""
    }.getOrDefault("")
}
