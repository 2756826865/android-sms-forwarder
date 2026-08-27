package org.fossify.messages.ui.remote.model

import org.fossify.messages.models.RemoteCommandExecutionEntity
import org.fossify.messages.models.SmsSendOperationEntity

/**
 * 远程指令列表项展示模型
 */
data class RemoteCommandItem(
    val commandId: String,
    val sourceType: String,               // SMS, DINGTALK
    val sourceMessageKey: String,
    val commandType: String,               // SEND_SMS, REBOOT, etc.
    val targetHmac: String?,
    val payloadLength: Int,
    val requesterHmac: String,
    val authorized: Boolean,
    val authorizationReason: String?,
    val executionState: String,           // RECEIVED, AUTHORIZED, REJECTED, DUPLICATE, RUNNING, SUCCESS, FAILED
    val sendOperationId: String?,
    val associatedSendOperation: SmsSendOperationEntity? = null,
    val associatedSmsState: String? = null, // SENT, DELIVERED, FAILED, UNKNOWN_AFTER_SUBMIT
    val receivedAt: Long,
    val updatedAt: Long
)

/**
 * 授权与安全状态概要
 */
data class RemoteAuthorizationState(
    val isRemoteSmsEnabled: Boolean = true,
    val isDingTalkEnabled: Boolean = false,
    val totalCommandsCount: Int = 0,
    val totalAuthorizedCount: Int = 0,
    val totalRejectedCount: Int = 0,
    val totalDuplicateCount: Int = 0
)

/**
 * 远程控制中心聚合 UI 状态
 */
data class RemoteControlCenterState(
    val commands: List<RemoteCommandItem> = emptyList(),
    val authSummary: RemoteAuthorizationState = RemoteAuthorizationState(),
    val isDingTalkConnected: Boolean = false,
    val lastDingTalkCommandTime: Long? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)
