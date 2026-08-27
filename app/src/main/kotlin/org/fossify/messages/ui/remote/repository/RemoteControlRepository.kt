package org.fossify.messages.ui.remote.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.models.RemoteCommandSourceType
import org.fossify.messages.ui.remote.model.RemoteAuthorizationState
import org.fossify.messages.ui.remote.model.RemoteCommandItem
import org.fossify.messages.ui.remote.model.RemoteControlCenterState

/**
 * 远程控制中心只读数据仓库
 */
class RemoteControlRepository(private val context: Context) {

    suspend fun getRemoteControlCenterState(limit: Int = 50): RemoteControlCenterState = withContext(Dispatchers.IO) {
        val db = context.getMessagesDB()
        val cmdDao = db.RemoteCommandDao()
        val smsDao = db.SmsSendDao()

        val rawCommands = runCatching { cmdDao.getRecentCommands(limit) }.getOrDefault(emptyList())

        var authCount = 0
        var rejectCount = 0
        var dupCount = 0
        var lastDingTalkTime: Long? = null

        val commandItems = rawCommands.map { cmd ->
            if (cmd.authorized) authCount++
            if (cmd.executionState == "REJECTED") rejectCount++
            if (cmd.executionState == "DUPLICATE") dupCount++

            if (cmd.sourceType == RemoteCommandSourceType.DINGTALK && lastDingTalkTime == null) {
                lastDingTalkTime = cmd.receivedAt
            }

            val associatedOp = cmd.sendOperationId?.let { opId ->
                runCatching { smsDao.getOperationById(opId) }.getOrNull()
            }

            RemoteCommandItem(
                commandId = cmd.commandId,
                sourceType = cmd.sourceType,
                sourceMessageKey = cmd.sourceMessageKey,
                commandType = cmd.commandType,
                targetHmac = cmd.targetHmac,
                payloadLength = cmd.payloadLength,
                requesterHmac = cmd.requesterHmac,
                authorized = cmd.authorized,
                authorizationReason = cmd.authorizationReason,
                executionState = cmd.executionState,
                sendOperationId = cmd.sendOperationId,
                associatedSendOperation = associatedOp,
                associatedSmsState = associatedOp?.state,
                receivedAt = cmd.receivedAt,
                updatedAt = cmd.updatedAt
            )
        }

        val authSummary = RemoteAuthorizationState(
            isRemoteSmsEnabled = true,
            isDingTalkEnabled = lastDingTalkTime != null,
            totalCommandsCount = rawCommands.size,
            totalAuthorizedCount = authCount,
            totalRejectedCount = rejectCount,
            totalDuplicateCount = dupCount
        )

        RemoteControlCenterState(
            commands = commandItems,
            authSummary = authSummary,
            isDingTalkConnected = lastDingTalkTime != null,
            lastDingTalkCommandTime = lastDingTalkTime,
            lastUpdated = System.currentTimeMillis()
        )
    }

    suspend fun getRecentCommands(limit: Int = 50): List<RemoteCommandItem> = withContext(Dispatchers.IO) {
        getRemoteControlCenterState(limit).commands
    }
}
