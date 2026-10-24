package org.fossify.messages.forwarding

import android.annotation.SuppressLint
import android.content.Context
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber
import org.fossify.messages.extensions.subscriptionManagerCompat
import android.telephony.SubscriptionInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ForwardingSimSnapshot(val slotIndex: Int, val description: String, val receiverNumber: String, val customLabel: String)

data class ForwardingPayload(val title: String, val content: String)

object ForwardingMessageFormatter {
    private fun String.replaceWhenPresent(tag: String, value: () -> String): String =
        if (contains(tag)) replace(tag, value()) else this

    /** Text-only endpoints have no separate title field to carry the configured SIM name. */
    @SuppressLint("MissingPermission")
    fun withCustomSimLabel(
        context: Context,
        config: MultiForwardConfig,
        subscriptionId: Int,
        content: String,
        snapshot: ForwardingSimSnapshot? = null,
    ): String {
        val description = if (snapshot != null) {
            if (snapshot.customLabel.isBlank() || snapshot.slotIndex < 0) return content
            describeSim(snapshot)
        } else {
            val resolvedInfo = if (subscriptionId >= 0) {
                resolveSimInfo(context, subscriptionId)
            } else {
                runCatching {
                    val activeList = context.subscriptionManagerCompat().activeSubscriptionInfoList
                    if (activeList?.size == 1) activeList[0] else null
                }.getOrNull()
            }
            val slot = resolvedInfo?.simSlotIndex?.takeIf { it >= 0 } ?: return content
            val label = config.customSimLabel(slot)
            if (label.isBlank()) return content
            SimLabelPresentation.describeSlot(slot, label)
        }
        return SimLabelPresentation.addLabel(content, description, config.enablePrivacyMask)
    }

    fun renderRuleTemplate(
        context: Context,
        template: String,
        sender: String,
        body: String,
        receivedAt: Long,
        subscriptionId: Int,
        simSnapshot: ForwardingSimSnapshot? = null,
    ): String {
        if (template.isBlank()) return body
        val battery by lazy { TemplateDataRetriever.batterySnapshot(context) }
        val contactName by lazy {
            runCatching { context.getNameAndPhotoFromPhoneNumber(sender).name }
                .getOrNull()?.takeIf { it.isNotBlank() && it != sender } ?: sender
        }
        val formattedTime by lazy { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(receivedAt)) }
        val dateOnly by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(receivedAt)) }
        val timeOnly by lazy { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(receivedAt)) }
        val receivedSim by lazy { simSnapshot ?: captureSim(context, subscriptionId) }
        val sim by lazy { receivedSim?.let(::describeSim).orEmpty() }
        val receiverNumber by lazy { receivedSim?.receiverNumber.orEmpty() }
        val simIndex by lazy { receivedSim?.slotIndex?.takeIf { it >= 0 }?.let { (it + 1).toString() }.orEmpty() }
        val code by lazy { org.fossify.messages.rule.template.TemplateRenderer.extractVerificationCode(body) }
        return template
            .replaceWhenPresent("{{CODE}}") { code }.replaceWhenPresent("{{code}}") { code }
            .replaceWhenPresent("{{VERIFICATION_CODE}}") { code }.replaceWhenPresent("{{verification_code}}") { code }
            .replaceWhenPresent("{{验证码}}") { code }
            .replace("{{FROM}}", sender).replace("{{from}}", sender)
            .replace("{{SENDER}}", sender).replace("{{sender}}", sender)
            .replaceWhenPresent("{{发件人}}") { sender }
            .replaceWhenPresent("{{CONTACT_NAME}}") { contactName }.replaceWhenPresent("{{contact_name}}") { contactName }
            .replaceWhenPresent("{{联系人}}") { contactName }
            .replace("{{SMS}}", body).replace("{{sms}}", body)
            .replace("{{BODY}}", body).replace("{{body}}", body)
            .replace("{{CONTENT}}", body).replace("{{content}}", body)
            .replaceWhenPresent("{{短信内容}}") { body }.replaceWhenPresent("{{正文}}") { body }
            .replaceWhenPresent("{{RECEIVE_TIME}}") { formattedTime }.replaceWhenPresent("{{receive_time}}") { formattedTime }
            .replaceWhenPresent("{{TIME}}") { formattedTime }.replaceWhenPresent("{{time}}") { formattedTime }
            .replaceWhenPresent("{{接收时间}}") { formattedTime }.replaceWhenPresent("{{时间}}") { formattedTime }
            .replaceWhenPresent("{{DATE_YMD}}") { dateOnly }.replaceWhenPresent("{{date_ymd}}") { dateOnly }
            .replaceWhenPresent("{{日期}}") { dateOnly }
            .replaceWhenPresent("{{DATE_HMS}}") { timeOnly }.replaceWhenPresent("{{date_hms}}") { timeOnly }
            .replace("{{TIMESTAMP}}", receivedAt.toString()).replace("{{timestamp}}", receivedAt.toString())
            .replaceWhenPresent("{{SIM_SLOT}}") { sim }.replaceWhenPresent("{{sim_slot}}") { sim }
            .replaceWhenPresent("{{CARD_SLOT}}") { sim }.replaceWhenPresent("{{card_slot}}") { sim }
            .replaceWhenPresent("{{SIM}}") { sim }.replaceWhenPresent("{{sim}}") { sim }
            .replaceWhenPresent("{sim}") { sim }
            .replaceWhenPresent("{{卡槽}}") { sim }.replaceWhenPresent("{{卡槽备注}}") { sim }
            .replaceWhenPresent("{{SIM_INDEX}}") { simIndex }.replaceWhenPresent("{{sim_index}}") { simIndex }
            .replaceWhenPresent("{{SIM_ID}}") { simIndex }.replaceWhenPresent("{{sim_id}}") { simIndex }
            .replaceWhenPresent("{{卡槽序号}}") { simIndex }
            .replaceWhenPresent("{{RECEIVER_NUMBER}}") { receiverNumber }.replaceWhenPresent("{{receiver_number}}") { receiverNumber }
            .replaceWhenPresent("{{RECEIVER}}") { receiverNumber }.replaceWhenPresent("{{receiver}}") { receiverNumber }
            .replaceWhenPresent("{receiver}") { receiverNumber }
            .replaceWhenPresent("{{接收号码}}") { receiverNumber }
            .replaceWhenPresent("{{DEVICE_NAME}}") { TemplateDataRetriever.getDeviceName() }
            .replaceWhenPresent("{{device_name}}") { TemplateDataRetriever.getDeviceName() }
            .replaceWhenPresent("{{设备名称}}") { TemplateDataRetriever.getDeviceName() }
            .replaceWhenPresent("{{DEVICE_BRAND}}") { TemplateDataRetriever.getDeviceBrand() }
            .replaceWhenPresent("{{device_brand}}") { TemplateDataRetriever.getDeviceBrand() }
            .replaceWhenPresent("{{DEVICE_MODEL}}") { TemplateDataRetriever.getDeviceModel() }
            .replaceWhenPresent("{{device_model}}") { TemplateDataRetriever.getDeviceModel() }
            .replaceWhenPresent("{{BATTERY_INFO}}") { battery.info }.replaceWhenPresent("{{battery_info}}") { battery.info }
            .replaceWhenPresent("{{电量}}") { battery.info }
            .replaceWhenPresent("{{BATTERY_PCT}}") { battery.percent }.replaceWhenPresent("{{battery_pct}}") { battery.percent }
            .replaceWhenPresent("{{NET_TYPE}}") { TemplateDataRetriever.getNetworkType(context) }
            .replaceWhenPresent("{{net_type}}") { TemplateDataRetriever.getNetworkType(context) }
            .replaceWhenPresent("{{网络类型}}") { TemplateDataRetriever.getNetworkType(context) }
            .replaceWhenPresent("{{IP_LIST}}") { TemplateDataRetriever.getIpAddress() }
            .replaceWhenPresent("{{ip_list}}") { TemplateDataRetriever.getIpAddress() }
            .replaceWhenPresent("{{IP地址}}") { TemplateDataRetriever.getIpAddress() }
            .replaceWhenPresent("{{APP_VERSION}}") { TemplateDataRetriever.getAppVersion() }
            .replaceWhenPresent("{{app_version}}") { TemplateDataRetriever.getAppVersion() }
            .replaceWhenPresent("{{版本号}}") { TemplateDataRetriever.getAppVersion() }
            .replaceWhenPresent("{{CURRENT_TIME}}") { TemplateDataRetriever.getCurrentTime() }
            .replaceWhenPresent("{{current_time}}") { TemplateDataRetriever.getCurrentTime() }
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
        val battery by lazy { TemplateDataRetriever.batterySnapshot(context) }
        val contactName = runCatching {
            context.getNameAndPhotoFromPhoneNumber(sender).name
        }.getOrNull()?.takeIf { it.isNotBlank() && it != sender }
        val senderTitle = contactName ?: sender.ifBlank { "新短信" }
        
        val sim = if (includeSim && (subscriptionId >= 0 || simSnapshot?.slotIndex?.let { it >= 0 } == true)) {
            simSnapshot?.let(::describeSim) ?: getSimDescription(context, config, subscriptionId)
        } else {
            ""
        }
        
        val receiverNumber = if (subscriptionId >= 0 || simSnapshot?.slotIndex?.let { it >= 0 } == true) {
            simSnapshot?.receiverNumber ?: getReceiverNumber(context, config, subscriptionId)
        } else {
            ""
        }

        val title = buildList {
            if (titlePrefix.isNotBlank()) add(titlePrefix.trim())
            if (sim.isNotBlank()) add("【$sim】")
            add(senderTitle)
        }.joinToString(" ").ifBlank { "新短信" }

        val formattedTime by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(receivedAt))
        }
            
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
                    val code by lazy { org.fossify.messages.rule.template.TemplateRenderer.extractVerificationCode(body) }
                    val dateOnly by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(receivedAt)) }
                    val timeOnly by lazy { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(receivedAt)) }
                    val simSlotIdx by lazy {
                        (simSnapshot?.slotIndex ?: resolveSimInfo(context, subscriptionId)?.simSlotIndex)
                            ?.takeIf { it >= 0 }?.let { (it + 1).toString() }.orEmpty()
                    }

                    val result = customTemplate
                        // 1. 验证码提取 (核心修复)
                        .replaceWhenPresent("{{CODE}}") { code }
                        .replaceWhenPresent("{{code}}") { code }
                        .replaceWhenPresent("{code}") { code }
                        .replaceWhenPresent("{{VERIFICATION_CODE}}") { code }
                        .replaceWhenPresent("{{验证码}}") { code }
                        // 2. 发件人相关
                        .replace("{{FROM}}", sender)
                        .replace("{{SENDER}}", sender)
                        .replace("{sender}", sender)
                        .replace("{from}", sender)
                        .replace("{{发件人}}", sender)
                        .replaceWhenPresent("{{CONTACT_NAME}}") { contactName ?: sender }
                        .replaceWhenPresent("{{NAME}}") { contactName ?: sender }
                        .replaceWhenPresent("{name}") { contactName ?: sender }
                        // 3. 短信正文
                        .replace("{{SMS}}", body)
                        .replace("{{BODY}}", body)
                        .replace("{{CONTENT}}", body)
                        .replace("{sms}", body)
                        .replace("{body}", body)
                        .replace("{{短信内容}}", body)
                        // 4. 时间相关
                        .replaceWhenPresent("{{RECEIVE_TIME}}") { formattedTime }
                        .replaceWhenPresent("{{TIME}}") { formattedTime }
                        .replaceWhenPresent("{time}") { formattedTime }
                        .replaceWhenPresent("{{接收时间}}") { formattedTime }
                        .replaceWhenPresent("{{DATE_YMD}}") { dateOnly }
                        .replaceWhenPresent("{{DATE}}") { dateOnly }
                        .replaceWhenPresent("{date}") { dateOnly }
                        .replaceWhenPresent("{{DATE_HMS}}") { timeOnly }
                        .replaceWhenPresent("{{TIME_HMS}}") { timeOnly }
                        .replace("{{TIMESTAMP}}", receivedAt.toString())
                        // 5. 卡槽与接收号码
                        .replaceWhenPresent("{{SIM_SLOT}}") { sim }
                        .replaceWhenPresent("{{CARD_SLOT}}") { sim }
                        .replaceWhenPresent("{{SIM}}") { sim }
                        .replaceWhenPresent("{{sim}}") { sim }
                        .replaceWhenPresent("{sim}") { sim }
                        .replaceWhenPresent("{{卡槽}}") { sim }
                        .replaceWhenPresent("{{SIM_INDEX}}") { simSlotIdx }
                        .replaceWhenPresent("{{SIM_ID}}") { simSlotIdx }
                        .replaceWhenPresent("{{卡槽序号}}") { simSlotIdx }
                        .replaceWhenPresent("{{RECEIVER_NUMBER}}") { receiverNumber }
                        .replaceWhenPresent("{{RECEIVER}}") { receiverNumber }
                        .replaceWhenPresent("{receiver}") { receiverNumber }
                        .replaceWhenPresent("{{接收号码}}") { receiverNumber }
                        // 6. 设备与网络状态
                        .replaceWhenPresent("{{DEVICE_NAME}}") { TemplateDataRetriever.getDeviceName() }
                        .replaceWhenPresent("{{DEVICE_BRAND}}") { TemplateDataRetriever.getDeviceBrand() }
                        .replaceWhenPresent("{{DEVICE_MODEL}}") { TemplateDataRetriever.getDeviceModel() }
                        .replaceWhenPresent("{{BATTERY_INFO}}") { battery.info }
                        .replaceWhenPresent("{{BATTERY_PCT}}") { battery.percent }
                        .replaceWhenPresent("{{IP_LIST}}") { TemplateDataRetriever.getIpAddress() }
                        .replaceWhenPresent("{{NET_TYPE}}") { TemplateDataRetriever.getNetworkType(context) }
                        .replaceWhenPresent("{{APP_VERSION}}") { TemplateDataRetriever.getAppVersion() }
                        .replaceWhenPresent("{{CURRENT_TIME}}") { TemplateDataRetriever.getCurrentTime() }
                    
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
            val code by lazy { org.fossify.messages.rule.template.TemplateRenderer.extractVerificationCode(body) }
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

    /** Old queued snapshots also use the same display format, without changing their receive slot. */
    fun describeSim(snapshot: ForwardingSimSnapshot): String =
        if (snapshot.customLabel.isBlank()) snapshot.description
        else SimLabelPresentation.describeSlot(snapshot.slotIndex, snapshot.customLabel)

    fun previewSim(context: Context, slotIndex: Int?): ForwardingSimSnapshot? {
        val slot = slotIndex?.takeIf { it in 0..1 } ?: return null
        val config = MultiForwardConfig(context)
        val label = config.customSimLabel(slot)
        return ForwardingSimSnapshot(slot, SimLabelPresentation.describeSlot(slot, label), config.customSimNumber(slot), label)
    }

    fun captureSim(context: Context, subscriptionId: Int): ForwardingSimSnapshot {
        val config = MultiForwardConfig(context)
        val info = resolveSimInfo(context, subscriptionId)
            ?: if (subscriptionId < 0) {
                runCatching {
                    val activeList = context.subscriptionManagerCompat().activeSubscriptionInfoList
                    if (activeList?.size == 1) activeList[0] else null
                }.getOrNull()
            } else null
        val slot = info?.simSlotIndex?.takeIf { it >= 0 } ?: -1
        val custom = config.customSimLabel(slot)
        val carrier = info?.carrierName?.toString()?.takeIf(String::isNotBlank) ?: info?.displayName?.toString().orEmpty()
        val description = SimLabelPresentation.describeSlot(slot, custom, carrier)
        @Suppress("DEPRECATION")
        val number = config.customSimNumber(slot).ifBlank { info?.number.orEmpty() }
        return ForwardingSimSnapshot(slot, description, number, custom)
    }

    @SuppressLint("MissingPermission")
    fun getSimDescription(context: Context, config: MultiForwardConfig, subscriptionId: Int): String {
        val info = resolveSimInfo(context, subscriptionId) ?: return "未知接收卡"
        val slot = info.simSlotIndex.takeIf { it >= 0 } ?: return "未知接收卡"
        val custom = config.customSimLabel(slot)
        val carrier = info.carrierName?.toString()?.takeIf { it.isNotBlank() }
            ?: info.displayName?.toString().orEmpty()
        return SimLabelPresentation.describeSlot(slot, custom, carrier)
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
