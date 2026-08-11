package org.fossify.messages.messaging

import android.content.Context
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import org.fossify.messages.extensions.subscriptionManagerCompat

object SimSendResolver {
    const val MODE_FOLLOW_RECEIVE = 0
    const val MODE_SIM1 = 1
    const val MODE_SIM2 = 2
    const val MODE_DEFAULT = 3

    fun resolveSubscriptionId(context: Context, receiveSubId: Int?, configuredMode: Int): Int? {
        val manager = runCatching { context.subscriptionManagerCompat() }.getOrNull()
        val active = manager?.activeSubscriptionInfoList.orEmpty().sortedBy { it.simSlotIndex }

        return when (configuredMode) {
            MODE_SIM1 -> active.firstOrNull { it.simSlotIndex == 0 }?.subscriptionId
            MODE_SIM2 -> active.firstOrNull { it.simSlotIndex == 1 }?.subscriptionId
            MODE_DEFAULT -> {
                val defaultId = SmsManager.getDefaultSmsSubscriptionId()
                if (defaultId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) defaultId else active.firstOrNull()?.subscriptionId
            }
            else -> receiveSubId?.takeIf { it != SubscriptionManager.INVALID_SUBSCRIPTION_ID && it >= 0 }
                ?: active.firstOrNull()?.subscriptionId
        }
    }

    fun modeLabel(mode: Int): String = when (mode) {
        MODE_SIM1 -> "SIM1"
        MODE_SIM2 -> "SIM2"
        MODE_DEFAULT -> "系统默认短信卡"
        else -> "跟随接收卡"
    }

    fun describeForLog(context: Context, receiveSubId: Int?, configuredMode: Int): String {
        val resolvedId = resolveSubscriptionId(context, receiveSubId, configuredMode)
        val slotName = slotLabel(context, resolvedId)
        return when (configuredMode) {
            MODE_FOLLOW_RECEIVE -> slotName?.let { "跟随接收卡→$it" } ?: modeLabel(MODE_FOLLOW_RECEIVE)
            else -> slotName?.let { "${modeLabel(configuredMode)}→$it" } ?: modeLabel(configuredMode)
        }
    }

    private fun slotLabel(context: Context, subscriptionId: Int?): String? {
        if (subscriptionId == null || subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID) return null
        return runCatching { context.subscriptionManagerCompat() }.getOrNull()
            ?.activeSubscriptionInfoList
            ?.find { it.subscriptionId == subscriptionId }
            ?.let { info -> "SIM${info.simSlotIndex + 1}" }
    }
}
