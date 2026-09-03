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
                    val simSlotIdx = runCatching {
                        val manager = context.getSystemService(SubscriptionManager::class.java)
                        val info = if (subscriptionId >= 0 && manager != null) manager.getActiveSubscriptionInfo(subscriptionId) else null
                        val slot = info?.simSlotIndex ?: if (subscriptionId > 0) subscriptionId - 1 else 0
                        (slot + 1).toString()
                    }.getOrDefault("1")

                    val result = customTemplate
                        // 1. 验证码提取 (核心修复)
                        .replace("{{CODE}}", code)
                        .replace("{{code}}", code)
                        .replace("{code}", code)
                        .replace("{{VERIFICATION_CODE}}", code)
                        .replace("{{验证码}}", code)
                        // 2. 发件人相关
                        .replace("{{FROM}}", sender)
                        .replace("{{SENDER}}", sender)
                        .replace("{sender}", sender)
                        .replace("{from}", sender)
                        .replace("{{发件人}}", sender)
                        .replace("{{CONTACT_NAME}}", contactName ?: sender)
                        .replace("{{NAME}}", contactName ?: sender)
                        .replace("{name}", contactName ?: sender)
                        // 3. 短信正文
                        .replace("{{SMS}}", body)
                        .replace("{{BODY}}", body)
                        .replace("{{CONTENT}}", body)
                        .replace("{sms}", body)
                        .replace("{body}", body)
                        .replace("{{短信内容}}", body)
                        // 4. 时间相关
                        .replace("{{RECEIVE_TIME}}", formattedTime)
                        .replace("{{TIME}}", formattedTime)
                        .replace("{time}", formattedTime)
                        .replace("{{接收时间}}", formattedTime)
                        .replace("{{DATE_YMD}}", dateOnly)
                        .replace("{{DATE}}", dateOnly)
                        .replace("{date}", dateOnly)
                        .replace("{{DATE_HMS}}", timeOnly)
                        .replace("{{TIME_HMS}}", timeOnly)
                        .replace("{{TIMESTAMP}}", receivedAt.toString())
                        // 5. 卡槽与接收号码
                        .replace("{{SIM_SLOT}}", sim)
                        .replace("{{CARD_SLOT}}", sim)
                        .replace("{{SIM}}", sim)
                        .replace("{{sim}}", sim)
                        .replace("{sim}", sim)
                        .replace("{{卡槽}}", sim)
                        .replace("{{SIM_INDEX}}", simSlotIdx)
                        .replace("{{SIM_ID}}", simSlotIdx)
                        .replace("{{卡槽序号}}", simSlotIdx)
                        .replace("{{RECEIVER_NUMBER}}", receiverNumber)
                        .replace("{{RECEIVER}}", receiverNumber)
                        .replace("{receiver}", receiverNumber)
                        .replace("{{接收号码}}", receiverNumber)
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
        
        val finalContent = if (config.enablePrivacyMask) {
            val code = org.fossify.messages.rule.template.TemplateRenderer.extractVerificationCode(body)
            PrivacyDataMasker.mask(
                content = content,
                maskVerificationCode = config.maskVerificationCode,
                verificationCode = code
            )
        } else {
            content
        }
        
        return ForwardingPayload(title, finalContent)
    }

    @SuppressLint("MissingPermission")
    fun getSimDescription(
        context: Context,
        config: MultiForwardConfig,
        subscriptionId: Int,
    ): String = runCatching {
        val manager = context.getSystemService(SubscriptionManager::class.java)
        var info = if (subscriptionId >= 0 && manager != null) {
            runCatching { manager.getActiveSubscriptionInfo(subscriptionId) }.getOrNull()
        } else null

        if (info == null && manager != null) {
            val list = runCatching { manager.activeSubscriptionInfoList }.getOrNull()
            info = list?.firstOrNull { it.subscriptionId == subscriptionId }
                ?: list?.firstOrNull { it.simSlotIndex == subscriptionId }
                ?: (if (subscriptionId > 0) list?.firstOrNull { it.simSlotIndex == subscriptionId - 1 } else null)
                ?: list?.firstOrNull()
        }

        if (info != null) {
            val custom = config.customSimLabel(info.simSlotIndex)
            if (custom.isNotBlank()) return@runCatching custom
            val slotNum = info.simSlotIndex + 1
            val carrier = info.carrierName?.toString()?.takeIf { it.isNotBlank() }
                ?: info.displayName?.toString()?.takeIf { it.isNotBlank() }
                .orEmpty()
            if (carrier.isNotBlank()) "SIM$slotNum · $carrier" else "SIM$slotNum"
        } else {
            val fallbackSlot = if (subscriptionId == 1 || subscriptionId == 0) "SIM${subscriptionId + 1}" else if (subscriptionId > 1) "SIM$subscriptionId" else "SIM1"
            fallbackSlot
        }
    }.getOrDefault(if (subscriptionId > 0) "SIM${subscriptionId}" else "SIM1")

    @SuppressLint("MissingPermission", "HardwareIds")
    fun getReceiverNumber(
        context: Context,
        config: MultiForwardConfig,
        subscriptionId: Int,
    ): String = runCatching {
        val manager = context.getSystemService(SubscriptionManager::class.java)
        var info = if (subscriptionId >= 0 && manager != null) {
            runCatching { manager.getActiveSubscriptionInfo(subscriptionId) }.getOrNull()
        } else null

        if (info == null && manager != null) {
            val list = runCatching { manager.activeSubscriptionInfoList }.getOrNull()
            info = list?.firstOrNull { it.subscriptionId == subscriptionId }
                ?: list?.firstOrNull { it.simSlotIndex == subscriptionId }
                ?: (if (subscriptionId > 0) list?.firstOrNull { it.simSlotIndex == subscriptionId - 1 } else null)
                ?: list?.firstOrNull()
        }

        val custom = info?.simSlotIndex?.let(config::customSimNumber).orEmpty()
        if (custom.isNotBlank()) return@runCatching custom
        @Suppress("DEPRECATION")
        info?.number ?: ""
    }.getOrDefault("")
}
