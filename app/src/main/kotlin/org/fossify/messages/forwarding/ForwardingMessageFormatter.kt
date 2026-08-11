package org.fossify.messages.forwarding

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionManager
import org.fossify.commons.helpers.SimpleContactsHelper
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
        val contactName = try {
            SimpleContactsHelper(context).getNameFromPhoneNumber(sender)
        } catch (_: NumberFormatException) {
            null
        } catch (_: Exception) {
            null
        }?.takeIf { it.isNotBlank() && it != sender }
        val senderTitle = contactName ?: sender.ifBlank { "新短信" }
        val sim = if (includeSim && subscriptionId >= 0) {
            getSimDescription(context, config, subscriptionId)
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
                if (includeTime) add("接收时间：$formattedTime")
            }

            MultiForwardConfig.TEMPLATE_DETAILED -> buildList {
                add(body)
                if (includeSender && sender.isNotBlank()) add("发送号码：$sender")
                if (includeSim && sim.isNotBlank()) add("SIM：$sim")
                if (includeTime) add("接收时间：$formattedTime")
            }

            MultiForwardConfig.TEMPLATE_EMOJI -> buildList {
                add("📩新短信通知")
                if (includeSender && sender.isNotBlank()) add("📞号码：$sender")
                if (includeTime) add("⏰时间：$formattedTime")
                add("💬消息：$body")
                if (includeSim && sim.isNotBlank()) add("📶SIM 卡：$sim")
            }

            MultiForwardConfig.TEMPLATE_CUSTOM -> {
                val customTemplate = config.customTemplate
                if (customTemplate.isNotBlank()) {
                    listOf(
                        customTemplate
                            .replace("{sender}", sender)
                            .replace("{name}", contactName ?: sender)
                            .replace("{body}", body)
                            .replace("{time}", formattedTime)
                            .replace("{sim}", sim)
                    )
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
}
