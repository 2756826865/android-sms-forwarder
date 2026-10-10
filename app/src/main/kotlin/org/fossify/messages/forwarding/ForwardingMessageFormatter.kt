package org.fossify.messages.forwarding

import android.annotation.SuppressLint
import android.content.Context
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ForwardingSimSnapshot(val slotIndex: Int, val description: String, val receiverNumber: String, val customLabel: String)

data class ForwardingPayload(val title: String, val content: String)

object ForwardingMessageFormatter {
    /** Text-only endpoints have no separate title field to carry the configured SIM name. */
    @SuppressLint("MissingPermission")
    fun withCustomSimLabel(
        context: Context,
        config: MultiForwardConfig,
        subscriptionId: Int,
        content: String,
        snapshot: ForwardingSimSnapshot? = null,
    ): String {
        if (subscriptionId < 0) return content
        val label = snapshot?.customLabel ?: runCatching {
            val slot = resolveSimInfo(context, subscriptionId)?.simSlotIndex
            slot?.takeIf { it >= 0 }?.let(config::customSimLabel).orEmpty().trim()
        }.getOrDefault("")
        if (label.isBlank()) return content
        val alreadyLabeled = content.lineSequence().any {
            it == "【$label】" || it == "卡槽：$label" || it == "📶卡槽：$label"
        }
        return if (alreadyLabeled) content else "【$label】\n$content"
    }

    fun renderRuleTemplate(
        context: Context,
        template: String,
        sender: String,
        body: String,
        receivedAt: Long,
        subscriptionId: Int
    ): String {
        if (template.isBlank()) return body
        val config = MultiForwardConfig(context)
        val contactName = runCatching { context.getNameAndPhotoFromPhoneNumber(sender).name }
            .getOrNull()?.takeIf { it.isNotBlank() && it != sender } ?: sender
        val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(receivedAt))
        val dateOnly = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(receivedAt))
        val timeOnly = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(receivedAt))
        val sim = if (subscriptionId >= 0) getSimDescription(context, config, subscriptionId) else ""
        val receiverNumber = if (subscriptionId >= 0) getReceiverNumber(context, config, subscriptionId) else ""
        val simIndex = resolveSimInfo(context, subscriptionId)?.simSlotIndex
            ?.takeIf { it >= 0 }?.let { (it + 1).toString() }.orEmpty()
        val code = org.fossify.messages.rule.template.TemplateRenderer.extractVerificationCode(body)
        return template
            .replace("{{CODE}}", code).replace("{{VERIFICATION_CODE}}", code)
            .replace("{{FROM}}", sender).replace("{{SENDER}}", sender)
            .replace("{{CONTACT_NAME}}", contactName)
            .replace("{{SMS}}", body).replace("{{BODY}}", body).replace("{{CONTENT}}", body)
            .replace("{{RECEIVE_TIME}}", formattedTime).replace("{{DATE_YMD}}", dateOnly)
            .replace("{{DATE_HMS}}", timeOnly).replace("{{TIMESTAMP}}", receivedAt.toString())
            .replace("{{SIM_SLOT}}", sim).replace("{{SIM_INDEX}}", simIndex)
            .replace("{{RECEIVER_NUMBER}}", receiverNumber)
            .replace("{{DEVICE_NAME}}", TemplateDataRetriever.getDeviceName())
            .replace("{{DEVICE_BRAND}}", TemplateDataRetriever.getDeviceBrand())
            .replace("{{DEVICE_MODEL}}", TemplateDataRetriever.getDeviceModel())
            .replace("{{BATTERY_INFO}}", TemplateDataRetriever.getBatteryInfo(context))
            .replace("{{BATTERY_PCT}}", TemplateDataRetriever.getBatteryPct(context))
            .replace("{{NET_TYPE}}", TemplateDataRetriever.getNetworkType(context))
            .replace("{{IP_LIST}}", TemplateDataRetriever.getIpAddress())
            .replace("{{APP_VERSION}}", TemplateDataRetriever.getAppVersion())
            .replace("{{CURRENT_TIME}}", TemplateDataRetriever.getCurrentTime())
    }

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
        simSnapshot: ForwardingSimSnapshot? = null,
    ): ForwardingPayload {
        val config = MultiForwardConfig(context)
        val contactName = runCatching {
            context.getNameAndPhotoFromPhoneNumber(sender).name
        }.getOrNull()?.takeIf { it.isNotBlank() && it != sender }
        val senderTitle = contactName ?: sender.ifBlank { "新短信" }
        
        val sim = if (includeSim && subscriptionId >= 0) {
            simSnapshot?.description ?: getSimDescription(context, config, subscriptionId)
        } else {
            ""
        }
        
        val receiverNumber = if (subscriptionId >= 0) {
            simSnapshot?.receiverNumber ?: getReceiverNumber(context, config, subscriptionId)
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
                    val simSlotIdx = (simSnapshot?.slotIndex ?: resolveSimInfo(context, subscriptionId)?.simSlotIndex)
                        ?.takeIf { it >= 0 }?.let { (it + 1).toString() }.orEmpty()

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

    /** Subscription IDs are identities, never physical slot numbers. */
    @SuppressLint("MissingPermission")
    private fun resolveSimInfo(context: Context, subscriptionId: Int): android.telephony.SubscriptionInfo? {
        return org.fossify.messages.messaging.SubscriptionResolver.findActiveInfo(context, subscriptionId)
    }

    fun captureSim(context: Context, subscriptionId: Int): ForwardingSimSnapshot {
        val config = MultiForwardConfig(context)
        val info = resolveSimInfo(context, subscriptionId)
        val slot = info?.simSlotIndex?.takeIf { it >= 0 } ?: -1
        val custom = config.customSimLabel(slot)
        val carrier = info?.carrierName?.toString().orEmpty()
        val description = if (slot < 0) "未知接收卡" else custom.ifBlank {
            if (carrier.isBlank()) "SIM${slot + 1}" else "SIM${slot + 1} · $carrier"
        }
        @Suppress("DEPRECATION")
        val number = config.customSimNumber(slot).ifBlank { info?.number.orEmpty() }
        return ForwardingSimSnapshot(slot, description, number, custom)
    }

    @SuppressLint("MissingPermission")
    fun getSimDescription(context: Context, config: MultiForwardConfig, subscriptionId: Int): String {
        val info = resolveSimInfo(context, subscriptionId) ?: return "未知接收卡"
        val slot = info.simSlotIndex.takeIf { it >= 0 } ?: return "未知接收卡"
        val custom = config.customSimLabel(slot)
        if (custom.isNotBlank()) return custom
        val carrier = info.carrierName?.toString()?.takeIf { it.isNotBlank() }
            ?: info.displayName?.toString().orEmpty()
        return if (carrier.isNotBlank()) "SIM${slot + 1} · $carrier" else "SIM${slot + 1}"
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun getReceiverNumber(context: Context, config: MultiForwardConfig, subscriptionId: Int): String {
        val info = resolveSimInfo(context, subscriptionId) ?: return ""
        val custom = info.simSlotIndex.takeIf { it >= 0 }?.let(config::customSimNumber).orEmpty()
        if (custom.isNotBlank()) return custom
        @Suppress("DEPRECATION")
        return info.number.orEmpty()
    }

    @SuppressLint("MissingPermission")
    fun getSimSlotName(context: Context, config: MultiForwardConfig, subscriptionId: Int): String {
        val slot = resolveSimInfo(context, subscriptionId)?.simSlotIndex?.takeIf { it >= 0 }
        return slot?.let { "SIM${it + 1}" } ?: "未知接收卡"
    }
}
