package org.fossify.messages.forwarding

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber

data class ForwardingPayload(val title: String, val content: String)

object ForwardingMessageFormatter {
    /** 单遍占位符替换，彻底阻断外来短信正文中夹带 {{RECEIVER_NUMBER}} / {{IP_LIST}} 的二次模板注入泄露。 */
    private val PLACEHOLDER_REGEX = Regex("""\{\{([^\{\}]+)\}\}|\{([a-zA-Z_]+)\}""")

    private fun renderSinglePass(template: String, resolve: (String) -> String?): String =
        PLACEHOLDER_REGEX.replace(template) { match ->
            resolve(match.value) ?: match.value
        }

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
            if (snapshot.slotIndex < 0) return content
            val effectiveLabel = snapshot.customLabel.ifBlank { config.customSimLabel(snapshot.slotIndex) }
            if (effectiveLabel.isBlank()) return content
            SimLabelPresentation.describeSlot(snapshot.slotIndex, effectiveLabel)
        } else {
            val resolvedInfo = resolveSimInfo(context, subscriptionId)
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
        val netType by lazy { TemplateDataRetriever.getNetworkType(context) }
        val ipAddress by lazy { TemplateDataRetriever.getIpAddress() }
        val deviceName by lazy { TemplateDataRetriever.getDeviceName() }
        val deviceBrand by lazy { TemplateDataRetriever.getDeviceBrand() }
        val deviceModel by lazy { TemplateDataRetriever.getDeviceModel() }
        val appVersion by lazy { TemplateDataRetriever.getAppVersion() }
        val currentTime by lazy { TemplateDataRetriever.getCurrentTime() }
        return renderSinglePass(template) { token ->
            when (token) {
                "{{CODE}}", "{{code}}", "{{VERIFICATION_CODE}}", "{{verification_code}}", "{{验证码}}" -> code
                "{{FROM}}", "{{from}}", "{{SENDER}}", "{{sender}}", "{{发件人}}" -> sender
                "{{CONTACT_NAME}}", "{{contact_name}}", "{{联系人}}" -> contactName
                "{{SMS}}", "{{sms}}", "{{BODY}}", "{{body}}", "{{CONTENT}}", "{{content}}", "{{短信内容}}", "{{正文}}" -> body
                "{{RECEIVE_TIME}}", "{{receive_time}}", "{{TIME}}", "{{time}}", "{{接收时间}}", "{{时间}}" -> formattedTime
                "{{DATE_YMD}}", "{{date_ymd}}", "{{日期}}" -> dateOnly
                "{{DATE_HMS}}", "{{date_hms}}" -> timeOnly
                "{{TIMESTAMP}}", "{{timestamp}}" -> receivedAt.toString()
                "{{SIM_SLOT}}", "{{sim_slot}}", "{{CARD_SLOT}}", "{{card_slot}}", "{{SIM}}", "{{sim}}", "{sim}", "{{卡槽}}", "{{卡槽备注}}" -> sim
                "{{SIM_INDEX}}", "{{sim_index}}", "{{SIM_ID}}", "{{sim_id}}", "{{卡槽序号}}" -> simIndex
                "{{RECEIVER_NUMBER}}", "{{receiver_number}}", "{{RECEIVER}}", "{{receiver}}", "{receiver}", "{{接收号码}}" -> receiverNumber
                "{{DEVICE_NAME}}", "{{device_name}}", "{{设备名称}}" -> deviceName
                "{{DEVICE_BRAND}}", "{{device_brand}}" -> deviceBrand
                "{{DEVICE_MODEL}}", "{{device_model}}" -> deviceModel
                "{{BATTERY_INFO}}", "{{battery_info}}", "{{电量}}" -> battery.info
                "{{BATTERY_PCT}}", "{{battery_pct}}" -> battery.percent
                "{{NET_TYPE}}", "{{net_type}}", "{{网络类型}}" -> netType
                "{{IP_LIST}}", "{{ip_list}}", "{{IP地址}}" -> ipAddress
                "{{APP_VERSION}}", "{{app_version}}", "{{版本号}}" -> appVersion
                "{{CURRENT_TIME}}", "{{current_time}}" -> currentTime
                else -> null
            }
        }.let { rendered ->
                val config = runCatching { MultiForwardConfig(context) }.getOrNull()
                if (config?.enablePrivacyMask == true) {
                    PrivacyDataMasker.mask(
                        content = rendered,
                        maskVerificationCode = config.maskVerificationCode,
                        verificationCode = code
                    )
                } else {
                    rendered
                }
            }
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
        templateModeOverride: Int? = null,
        customTemplateOverride: String? = null,
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
            
        val content = when (templateModeOverride ?: config.templateMode) {
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
                val customTemplate = customTemplateOverride ?: config.customTemplate
                if (customTemplate.isNotBlank()) {
                    val code by lazy { org.fossify.messages.rule.template.TemplateRenderer.extractVerificationCode(body) }
                    val dateOnly by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(receivedAt)) }
                    val timeOnly by lazy { SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(receivedAt)) }
                    val simSlotIdx by lazy {
                        (simSnapshot?.slotIndex ?: resolveSimInfo(context, subscriptionId)?.simSlotIndex)
                            ?.takeIf { it >= 0 }?.let { (it + 1).toString() }.orEmpty()
                    }

                    val netType by lazy { TemplateDataRetriever.getNetworkType(context) }
                    val ipAddress by lazy { TemplateDataRetriever.getIpAddress() }
                    val deviceName by lazy { TemplateDataRetriever.getDeviceName() }
                    val deviceBrand by lazy { TemplateDataRetriever.getDeviceBrand() }
                    val deviceModel by lazy { TemplateDataRetriever.getDeviceModel() }
                    val appVersion by lazy { TemplateDataRetriever.getAppVersion() }
                    val currentTime by lazy { TemplateDataRetriever.getCurrentTime() }

                    val result = renderSinglePass(customTemplate) { token ->
                        when (token) {
                            "{{CODE}}", "{{code}}", "{code}", "{{VERIFICATION_CODE}}", "{{verification_code}}", "{{验证码}}" -> code
                            "{{FROM}}", "{{from}}", "{{SENDER}}", "{{sender}}", "{sender}", "{from}", "{{发件人}}" -> sender
                            "{{CONTACT_NAME}}", "{{contact_name}}", "{{NAME}}", "{{name}}", "{name}", "{{联系人}}" -> contactName ?: sender
                            "{{SMS}}", "{{sms}}", "{{BODY}}", "{{body}}", "{{CONTENT}}", "{{content}}", "{sms}", "{body}", "{{短信内容}}", "{{正文}}" -> body
                            "{{RECEIVE_TIME}}", "{{receive_time}}", "{{TIME}}", "{{time}}", "{time}", "{{接收时间}}", "{{时间}}" -> formattedTime
                            "{{DATE_YMD}}", "{{date_ymd}}", "{{DATE}}", "{{date}}", "{date}", "{{日期}}" -> dateOnly
                            "{{DATE_HMS}}", "{{date_hms}}", "{{TIME_HMS}}", "{{time_hms}}" -> timeOnly
                            "{{TIMESTAMP}}", "{{timestamp}}" -> receivedAt.toString()
                            "{{SIM_SLOT}}", "{{sim_slot}}", "{{CARD_SLOT}}", "{{card_slot}}", "{{SIM}}", "{{sim}}", "{sim}", "{{卡槽}}", "{{卡槽备注}}" -> sim
                            "{{SIM_INDEX}}", "{{sim_index}}", "{{SIM_ID}}", "{{sim_id}}", "{{卡槽序号}}" -> simSlotIdx
                            "{{RECEIVER_NUMBER}}", "{{receiver_number}}", "{{RECEIVER}}", "{{receiver}}", "{receiver}", "{{接收号码}}" -> receiverNumber
                            "{{DEVICE_NAME}}", "{{device_name}}", "{{设备名称}}" -> deviceName
                            "{{DEVICE_BRAND}}", "{{device_brand}}" -> deviceBrand
                            "{{DEVICE_MODEL}}", "{{device_model}}" -> deviceModel
                            "{{BATTERY_INFO}}", "{{battery_info}}", "{{电量}}" -> battery.info
                            "{{BATTERY_PCT}}", "{{battery_pct}}" -> battery.percent
                            "{{IP_LIST}}", "{{ip_list}}", "{{IP地址}}" -> ipAddress
                            "{{NET_TYPE}}", "{{net_type}}", "{{网络类型}}" -> netType
                            "{{APP_VERSION}}", "{{app_version}}", "{{版本号}}" -> appVersion
                            "{{CURRENT_TIME}}", "{{current_time}}" -> currentTime
                            else -> null
                        }
                    }
                    
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
        
        val finalTitle = if (config.enablePrivacyMask) {
            PrivacyDataMasker.mask(content = title, maskVerificationCode = false)
        } else {
            title
        }
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
        
        return ForwardingPayload(finalTitle, finalContent)
    }

    /** Subscription IDs are identities, never physical slot numbers. */
    @SuppressLint("MissingPermission")
    private fun resolveSimInfo(context: Context, subscriptionId: Int): android.telephony.SubscriptionInfo? {
        return org.fossify.messages.messaging.SubscriptionResolver.findActiveInfo(context, subscriptionId)
    }

    /**
     * Old queued snapshots also use the same display format, without changing their receive slot.
     */
    fun describeSim(snapshot: ForwardingSimSnapshot): String =
        if (snapshot.customLabel.isBlank()) snapshot.description
        else SimLabelPresentation.describeSlot(snapshot.slotIndex, snapshot.customLabel)

    fun previewSim(context: Context, slotIndex: Int?): ForwardingSimSnapshot? {
        val slot = slotIndex?.takeIf { it in 0..1 } ?: return null
        val config = MultiForwardConfig(context)
        val label = config.customSimLabel(slot)
        return ForwardingSimSnapshot(slot, SimLabelPresentation.describeSlot(slot, label), config.customSimNumber(slot), label)
    }

    fun captureSim(
        context: Context,
        subscriptionId: Int,
        confirmedSlot: Int? = null,
    ): ForwardingSimSnapshot {
        val config = MultiForwardConfig(context)
        val info = resolveSimInfo(context, subscriptionId)
        val slot =
            confirmedSlot?.takeIf { it >= 0 }
                ?: org.fossify.messages.messaging.ReceivingSubscriptionReader.slotForSubscription(
                    context,
                    subscriptionId,
                )
                ?: info?.simSlotIndex?.takeIf { it >= 0 }
                ?: -1
        val custom = config.customSimLabel(slot)
        val carrier = info?.carrierName?.toString()?.takeIf(String::isNotBlank) ?: info?.displayName?.toString().orEmpty()
        val description =
            if (slot >= 0) SimLabelPresentation.describeSlot(slot, custom, carrier)
            else {
                val reason =
                    if (subscriptionId < 0) "缺少已确认的接收身份"
                    else if (
                        context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                    )
                        "电话权限未授予"
                    else "订阅不可用或读取失败"
                "未知接收卡（$reason）"
            }
        @Suppress("DEPRECATION")
        val number = config.customSimNumber(slot).ifBlank { info?.number.orEmpty() }
        return ForwardingSimSnapshot(slot, description, number, custom,
            subscriptionId,
            source = "active_subscription",
            observedAt = System.currentTimeMillis(),
        )
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
