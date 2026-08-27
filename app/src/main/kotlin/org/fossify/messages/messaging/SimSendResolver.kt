package org.fossify.messages.messaging

import android.content.Context
import android.telephony.SubscriptionManager

/**
 * Legacy SIM resolution helper, maintained for backward compatibility.
 * Delegates all logic to the unified [SubscriptionResolver].
 */
@Deprecated("Use SubscriptionResolver.resolve() instead", ReplaceWith("SubscriptionResolver"))
object SimSendResolver {
    const val MODE_FOLLOW_RECEIVE = SubscriptionResolver.MODE_FOLLOW_RECEIVE
    const val MODE_SIM1 = SubscriptionResolver.MODE_SIM1
    const val MODE_SIM2 = SubscriptionResolver.MODE_SIM2
    const val MODE_DEFAULT = SubscriptionResolver.MODE_DEFAULT

    fun resolveSubscriptionId(context: Context, receiveSubId: Int?, configuredMode: Int): Int? {
        val request = SimResolutionRequest(
            receivedSubId = receiveSubId,
            configuredMode = configuredMode,
            allowFallback = true
        )
        val result = SubscriptionResolver.resolve(context, request)
        return if (result.resolvedSubscriptionId != SubscriptionManager.INVALID_SUBSCRIPTION_ID && result.resolvedSubscriptionId >= 0) {
            result.resolvedSubscriptionId
        } else {
            null
        }
    }

    fun modeLabel(mode: Int): String = when (mode) {
        MODE_SIM1 -> "SIM1"
        MODE_SIM2 -> "SIM2"
        MODE_DEFAULT -> "系统默认短信卡"
        else -> "跟随接收卡"
    }

    fun describeForLog(context: Context, receiveSubId: Int?, configuredMode: Int): String {
        val request = SimResolutionRequest(
            receivedSubId = receiveSubId,
            configuredMode = configuredMode,
            allowFallback = true
        )
        val result = SubscriptionResolver.resolve(context, request)
        val slotName = result.resolvedSlotIndex?.let { "SIM${it + 1}" }

        return when (configuredMode) {
            MODE_FOLLOW_RECEIVE -> slotName?.let { "跟随接收卡→$it" } ?: modeLabel(MODE_FOLLOW_RECEIVE)
            else -> slotName?.let { "${modeLabel(configuredMode)}→$it" } ?: modeLabel(configuredMode)
        }
    }
}
