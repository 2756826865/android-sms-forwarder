package org.fossify.messages.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.subscriptionManagerCompat
import org.fossify.messages.forwarding.MultiForwardConfig

/**
 * 统一 SIM 解析请求参数
 */
data class SimResolutionRequest(
    /** 显式指定的 subscriptionId（如 UI 手动选择），最高优先级 */
    val explicitSubId: Int? = null,

    /** 显式指定的物理卡槽索引 (0=SIM1, 1=SIM2) */
    val explicitSlotIndex: Int? = null,

    /** 目标联系人号码（用于查询绑定的历史偏好卡） */
    val targetAddress: String? = null,

    /** 接收短信时的 subscriptionId（用于跟随接收卡模式） */
    val receivedSubId: Int? = null,

    /** 业务配置的发送模式（如 MODE_FOLLOW_RECEIVE, MODE_SIM1, MODE_SIM2, MODE_DEFAULT） */
    val configuredMode: Int? = null,

    /** 是否允许降级到系统默认卡/首张可用卡（默认为 true） */
    val allowFallback: Boolean = true
)

/**
 * 解析来源枚举
 */
enum class SimResolutionSource {
    EXPLICIT_SUB_ID,       // 显式指定 subscriptionId 命中
    EXPLICIT_SLOT,         // 显式指定 slotIndex 命中
    CONFIGURED_MODE,       // 按照业务配置模式 (SIM1/SIM2/跟随接收) 命中
    ADDRESS_PREFERENCE,    // 按照目标号码历史偏好命中
    SYSTEM_DEFAULT,        // 命中系统默认短信卡
    FALLBACK_FIRST_ACTIVE, // 降级命中当前第一张活跃卡
    NONE                   // 解析未命中 / 失败
}

/**
 * 失败原因枚举
 */
enum class SimErrorReason {
    PERMISSION_DENIED,         // 缺少 READ_PHONE_STATE 权限
    NO_ACTIVE_SUBSCRIPTION,    // 系统无任何可用/启用的 SIM 卡
    SUBSCRIPTION_NOT_ACTIVE,   // 指定的 subscriptionId 当前不在线
    SLOT_NOT_FOUND,            // 指定的物理卡槽未插入或未启用
    INVALID_REQUEST            // 请求参数冲突或非法
}

/**
 * 降级原因枚举
 */
enum class SimFallbackReason {
    REQUESTED_SUB_ID_NOT_ACTIVE,   // 期望的 subId 已失效/离线
    REQUESTED_SLOT_EMPTY,          // 期望的卡槽无可用卡
    FOLLOW_RECEIVE_UNAVAILABLE,    // 跟随接收卡已离线
    ADDRESS_PREF_NOT_ACTIVE,       // 联系人偏好的 subId 已不在活跃列表中
    DEFAULT_SMS_SUB_INVALID        // 系统默认短信卡无效
}

/**
 * 不可变 SIM 卡活跃快照
 */
data class SubscriptionSnapshot(
    val subscriptionId: Int,
    val simSlotIndex: Int,
    val displayName: String,
    val carrierName: String,
    val number: String
)

/**
 * 统一 SIM 解析输出结果
 */
data class SimResolutionResult(
    /** 最终解析出的 subscriptionId（有效值 >= 0，失败为 INVALID_SUBSCRIPTION_ID / -1） */
    val resolvedSubscriptionId: Int,

    /** 最终对应的物理卡槽索引 (0 或 1，未知/无卡为 null) */
    val resolvedSlotIndex: Int?,

    /** 解析来源 */
    val resolutionSource: SimResolutionSource,

    /** 是否触发了降级 */
    val isFallback: Boolean = false,

    /** 降级原因（未发生降级时为 null） */
    val fallbackReason: SimFallbackReason? = null,

    /** 失败原因（解析成功时为 null） */
    val errorReason: SimErrorReason? = null,

    /** 显示标签（如 "SIM1 (中国移动)"） */
    val simDisplayName: String? = null
) {
    val isSuccessful: Boolean
        get() = resolvedSubscriptionId >= 0 && errorReason == null

    companion object {
        fun failure(error: SimErrorReason): SimResolutionResult = SimResolutionResult(
            resolvedSubscriptionId = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
            resolvedSlotIndex = null,
            resolutionSource = SimResolutionSource.NONE,
            isFallback = false,
            fallbackReason = null,
            errorReason = error,
            simDisplayName = null
        )
    }
}

/**
 * 统一 Subscription / SIM 卡解析引擎
 *
 * 核心设计原则：
 * 1. subscriptionId 为长期发送身份，slotIndex 为物理卡槽定位，严禁使用 List Index 作为身份标识。
 * 2. 纯函数式决策流，Fail-Safe，绝不在异常或权限不足时导致崩溃。
 * 3. 拒绝静默 fallback，明确记录 fallbackReason 与 errorReason。
 */
object SubscriptionResolver {

    const val MODE_FOLLOW_RECEIVE = 0
    const val MODE_SIM1 = 1
    const val MODE_SIM2 = 2
    const val MODE_DEFAULT = 3

    /**
     * 统一对外部暴露的解析入口
     */
    fun resolve(context: Context, request: SimResolutionRequest): SimResolutionResult {
        val hasPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        val snapshots = if (hasPermission) getActiveSubscriptionSnapshots(context) else emptyList()
        val defaultSubId = runCatching {
            SmsManager.getDefaultSmsSubscriptionId()
        }.getOrDefault(SubscriptionManager.INVALID_SUBSCRIPTION_ID)

        val getAddressPref: (String) -> Int? = { address ->
            val prefSubId = context.config.getUseSIMIdAtNumber(address)
            if (prefSubId > 0 || (prefSubId == 0 && snapshots.any { it.subscriptionId == 0 })) {
                prefSubId
            } else {
                null
            }
        }

        return resolveInternal(
            activeSubscriptions = snapshots,
            defaultSmsSubId = defaultSubId,
            getAddressPreferredSubId = getAddressPref,
            request = request,
            hasPhonePermission = hasPermission
        )
    }

    /**
     * 纯函数决策流（便于单元测试隔离 Telephony 依赖）
     */
    fun resolveInternal(
        activeSubscriptions: List<SubscriptionSnapshot>,
        defaultSmsSubId: Int,
        getAddressPreferredSubId: (String) -> Int?,
        request: SimResolutionRequest,
        hasPhonePermission: Boolean
    ): SimResolutionResult {
        if (!hasPhonePermission && activeSubscriptions.isEmpty()) {
            return if (request.allowFallback && defaultSmsSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                SimResolutionResult(
                    resolvedSubscriptionId = defaultSmsSubId,
                    resolvedSlotIndex = null,
                    resolutionSource = SimResolutionSource.SYSTEM_DEFAULT,
                    isFallback = true,
                    fallbackReason = SimFallbackReason.DEFAULT_SMS_SUB_INVALID,
                    errorReason = SimErrorReason.PERMISSION_DENIED,
                    simDisplayName = "系统默认卡(无READ_PHONE_STATE权限)"
                )
            } else {
                SimResolutionResult.failure(SimErrorReason.PERMISSION_DENIED)
            }
        }

        if (activeSubscriptions.isEmpty()) {
            return SimResolutionResult.failure(SimErrorReason.NO_ACTIVE_SUBSCRIPTION)
        }

        val sortedActives = activeSubscriptions.sortedBy { it.simSlotIndex }

        // Level 1: 显式指定 subscriptionId (最高优先)
        if (request.explicitSubId != null && request.explicitSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            val matched = sortedActives.find { it.subscriptionId == request.explicitSubId }
            if (matched != null) {
                return SimResolutionResult(
                    resolvedSubscriptionId = matched.subscriptionId,
                    resolvedSlotIndex = matched.simSlotIndex,
                    resolutionSource = SimResolutionSource.EXPLICIT_SUB_ID,
                    isFallback = false,
                    fallbackReason = null,
                    errorReason = null,
                    simDisplayName = formatDisplayName(matched)
                )
            } else if (!request.allowFallback) {
                return SimResolutionResult.failure(SimErrorReason.SUBSCRIPTION_NOT_ACTIVE)
            }
        }

        // Level 2: 显式指定物理卡槽 slotIndex (0 或 1)
        if (request.explicitSlotIndex != null) {
            val matched = sortedActives.find { it.simSlotIndex == request.explicitSlotIndex }
            if (matched != null) {
                return SimResolutionResult(
                    resolvedSubscriptionId = matched.subscriptionId,
                    resolvedSlotIndex = matched.simSlotIndex,
                    resolutionSource = SimResolutionSource.EXPLICIT_SLOT,
                    isFallback = false,
                    fallbackReason = null,
                    errorReason = null,
                    simDisplayName = formatDisplayName(matched)
                )
            } else if (!request.allowFallback) {
                return SimResolutionResult.failure(SimErrorReason.SLOT_NOT_FOUND)
            }
        }

        // Level 3: 业务配置模式 (MODE_SIM1, MODE_SIM2, MODE_FOLLOW_RECEIVE, MODE_DEFAULT)
        if (request.configuredMode != null) {
            when (request.configuredMode) {
                MODE_SIM1 -> {
                    val sim1 = sortedActives.find { it.simSlotIndex == 0 }
                    if (sim1 != null) {
                        return SimResolutionResult(
                            resolvedSubscriptionId = sim1.subscriptionId,
                            resolvedSlotIndex = sim1.simSlotIndex,
                            resolutionSource = SimResolutionSource.CONFIGURED_MODE,
                            isFallback = false,
                            fallbackReason = null,
                            errorReason = null,
                            simDisplayName = formatDisplayName(sim1)
                        )
                    } else if (!request.allowFallback) {
                        return SimResolutionResult.failure(SimErrorReason.SLOT_NOT_FOUND)
                    }
                }
                MODE_SIM2 -> {
                    val sim2 = sortedActives.find { it.simSlotIndex == 1 }
                    if (sim2 != null) {
                        return SimResolutionResult(
                            resolvedSubscriptionId = sim2.subscriptionId,
                            resolvedSlotIndex = sim2.simSlotIndex,
                            resolutionSource = SimResolutionSource.CONFIGURED_MODE,
                            isFallback = false,
                            fallbackReason = null,
                            errorReason = null,
                            simDisplayName = formatDisplayName(sim2)
                        )
                    } else if (!request.allowFallback) {
                        return SimResolutionResult.failure(SimErrorReason.SLOT_NOT_FOUND)
                    }
                }
                MODE_FOLLOW_RECEIVE -> {
                    if (request.receivedSubId != null && request.receivedSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        val follow = sortedActives.find { it.subscriptionId == request.receivedSubId }
                        if (follow != null) {
                            return SimResolutionResult(
                                resolvedSubscriptionId = follow.subscriptionId,
                                resolvedSlotIndex = follow.simSlotIndex,
                                resolutionSource = SimResolutionSource.CONFIGURED_MODE,
                                isFallback = false,
                                fallbackReason = null,
                                errorReason = null,
                                simDisplayName = formatDisplayName(follow)
                            )
                        } else if (!request.allowFallback) {
                            return SimResolutionResult.failure(SimErrorReason.SUBSCRIPTION_NOT_ACTIVE)
                        }
                    }
                }
                MODE_DEFAULT -> {
                    if (defaultSmsSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                        val defaultCard = sortedActives.find { it.subscriptionId == defaultSmsSubId }
                        if (defaultCard != null) {
                            return SimResolutionResult(
                                resolvedSubscriptionId = defaultCard.subscriptionId,
                                resolvedSlotIndex = defaultCard.simSlotIndex,
                                resolutionSource = SimResolutionSource.SYSTEM_DEFAULT,
                                isFallback = false,
                                fallbackReason = null,
                                errorReason = null,
                                simDisplayName = formatDisplayName(defaultCard)
                            )
                        }
                    }
                }
            }
        }

        // Level 4: 目标号码历史偏好 (Address Preference)
        if (!request.targetAddress.isNullOrBlank()) {
            val prefSubId = getAddressPreferredSubId(request.targetAddress)
            if (prefSubId != null && prefSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                val prefCard = sortedActives.find { it.subscriptionId == prefSubId }
                if (prefCard != null) {
                    return SimResolutionResult(
                        resolvedSubscriptionId = prefCard.subscriptionId,
                        resolvedSlotIndex = prefCard.simSlotIndex,
                        resolutionSource = SimResolutionSource.ADDRESS_PREFERENCE,
                        isFallback = false,
                        fallbackReason = null,
                        errorReason = null,
                        simDisplayName = formatDisplayName(prefCard)
                    )
                }
            }
        }

        // Level 5: 系统默认短信卡 (仅在无明确指定卡槽或卡号时命中)
        val hasExplicitRequest = request.explicitSubId != null || request.explicitSlotIndex != null || request.configuredMode != null
        if (!hasExplicitRequest && defaultSmsSubId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
            val defaultCard = sortedActives.find { it.subscriptionId == defaultSmsSubId }
            if (defaultCard != null) {
                return SimResolutionResult(
                    resolvedSubscriptionId = defaultCard.subscriptionId,
                    resolvedSlotIndex = defaultCard.simSlotIndex,
                    resolutionSource = SimResolutionSource.SYSTEM_DEFAULT,
                    isFallback = false,
                    fallbackReason = null,
                    errorReason = null,
                    simDisplayName = formatDisplayName(defaultCard)
                )
            }
        }

        // Level 6: 保底降级 (Fallback to first active card)
        if (request.allowFallback && sortedActives.isNotEmpty()) {
            val firstCard = sortedActives.first()
            val fallbackReason = when {
                request.explicitSubId != null -> SimFallbackReason.REQUESTED_SUB_ID_NOT_ACTIVE
                request.explicitSlotIndex != null -> SimFallbackReason.REQUESTED_SLOT_EMPTY
                request.configuredMode == MODE_FOLLOW_RECEIVE -> SimFallbackReason.FOLLOW_RECEIVE_UNAVAILABLE
                request.configuredMode in listOf(MODE_SIM1, MODE_SIM2) -> SimFallbackReason.REQUESTED_SLOT_EMPTY
                !request.targetAddress.isNullOrBlank() -> SimFallbackReason.ADDRESS_PREF_NOT_ACTIVE
                else -> SimFallbackReason.DEFAULT_SMS_SUB_INVALID
            }

            return SimResolutionResult(
                resolvedSubscriptionId = firstCard.subscriptionId,
                resolvedSlotIndex = firstCard.simSlotIndex,
                resolutionSource = SimResolutionSource.FALLBACK_FIRST_ACTIVE,
                isFallback = true,
                fallbackReason = fallbackReason,
                errorReason = null,
                simDisplayName = formatDisplayName(firstCard)
            )
        }

        return SimResolutionResult.failure(SimErrorReason.INVALID_REQUEST)
    }

    /**
     * 读取活跃卡列表并转为 Snapshot 实体
     */
    fun getActiveSubscriptionSnapshots(context: Context): List<SubscriptionSnapshot> {
        val activeList = runCatching {
            context.subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()
        }.getOrDefault(emptyList())

        val simDisplayConfig = MultiForwardConfig(context)

        return activeList.mapIndexed { index, info ->
            val slotIndex = info.simSlotIndex.takeIf { it >= 0 } ?: index
            val systemLabel = info.carrierName?.toString()?.takeIf(String::isNotBlank)
                ?: info.displayName?.toString().orEmpty()
            val finalLabel = simDisplayConfig.customSimLabel(slotIndex).ifBlank { systemLabel }
            val finalNumber = simDisplayConfig.customSimNumber(slotIndex).ifBlank { info.number.orEmpty() }

            SubscriptionSnapshot(
                subscriptionId = info.subscriptionId,
                simSlotIndex = slotIndex,
                displayName = finalLabel,
                carrierName = systemLabel,
                number = finalNumber
            )
        }.sortedBy { it.simSlotIndex }
    }

    private fun formatDisplayName(snapshot: SubscriptionSnapshot): String {
        val slotPrefix = "SIM${snapshot.simSlotIndex + 1}"
        return if (snapshot.displayName.isNotBlank()) {
            "$slotPrefix (${snapshot.displayName})"
        } else {
            slotPrefix
        }
    }
}
