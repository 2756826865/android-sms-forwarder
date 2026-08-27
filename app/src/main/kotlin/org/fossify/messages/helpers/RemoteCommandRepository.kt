package org.fossify.messages.helpers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.models.RemoteCommandContext
import org.fossify.messages.models.RemoteCommandExecutionEntity
import org.fossify.messages.models.RemoteCommandState
import java.util.UUID

/**
 * 远程指令持久化事实仓库
 *
 * 职责：
 * 1. 唯一事实创建与原子幂等判定；
 * 2. 状态机推进与审计记录；
 * 3. 异步、Fail-Open、脱敏存储，绝对不记录敏感明文。
 */
object RemoteCommandRepository {
    private const val TAG = "RemoteCommandRepository"
    private val commandScope = CoroutineScope(
        Dispatchers.IO.limitedParallelism(1) + SupervisorJob()
    )

    sealed class ClaimResult {
        data class NewCommand(val commandId: String, val entity: RemoteCommandExecutionEntity) : ClaimResult()
        data class Duplicate(val existingCommandId: String, val existingEntity: RemoteCommandExecutionEntity?) : ClaimResult()
        data class Error(val message: String) : ClaimResult()
    }

    /**
     * 原子声明或获取重复指令
     */
    suspend fun claimOrGetDuplicate(
        context: Context,
        cmdContext: RemoteCommandContext,
        authorized: Boolean = false,
        authorizationReason: String = "PENDING_CHECK"
    ): ClaimResult = withContext(Dispatchers.IO) {
        try {
            val targetHmac = ShadowHmacHelper.calculateHmac(cmdContext.rawTarget)
            val payloadHmac = ShadowHmacHelper.calculateHmac(cmdContext.rawPayload) ?: ""
            val requesterHmac = ShadowHmacHelper.calculateHmac(cmdContext.rawRequester) ?: ""
            val commandId = UUID.randomUUID().toString()

            val entity = RemoteCommandExecutionEntity(
                commandId = commandId,
                sourceType = cmdContext.sourceType,
                sourceMessageKey = cmdContext.sourceMessageKey,
                commandType = cmdContext.commandType,
                targetHmac = targetHmac,
                payloadHmac = payloadHmac,
                payloadLength = cmdContext.rawPayload.length,
                requestedSimMode = cmdContext.requestedSimMode,
                requesterHmac = requesterHmac,
                receivedAt = cmdContext.receivedAt,
                authorized = authorized,
                authorizationReason = authorizationReason,
                executionState = RemoteCommandState.RECEIVED.name
            )

            val dao = context.getMessagesDB().RemoteCommandDao()
            val rowId = dao.insertIgnore(entity)

            if (rowId != -1L) {
                ClaimResult.NewCommand(commandId, entity)
            } else {
                val existing = dao.findByIdempotencyKey(
                    cmdContext.sourceType,
                    cmdContext.sourceMessageKey,
                    payloadHmac
                )
                ClaimResult.Duplicate(existing?.commandId ?: "", existing)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to claim remote command idempotency: ${e.message}")
            ClaimResult.Error(e.message ?: "Unknown DB error")
        }
    }

    fun recordAuthorization(
        context: Context,
        commandId: String,
        authorized: Boolean,
        reason: String
    ) {
        commandScope.launch {
            try {
                val state = if (authorized) RemoteCommandState.AUTHORIZED.name else RemoteCommandState.REJECTED.name
                context.getMessagesDB().RemoteCommandDao().recordAuthorization(commandId, authorized, reason, state)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record authorization for $commandId: ${e.message}")
            }
        }
    }

    fun recordRunning(context: Context, commandId: String) {
        commandScope.launch {
            try {
                context.getMessagesDB().RemoteCommandDao().updateState(commandId, RemoteCommandState.RUNNING.name)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record running for $commandId: ${e.message}")
            }
        }
    }

    fun recordExecutionSuccess(
        context: Context,
        commandId: String,
        sendOperationId: String? = null
    ) {
        commandScope.launch {
            try {
                context.getMessagesDB().RemoteCommandDao().recordExecutionResult(
                    commandId = commandId,
                    state = RemoteCommandState.SUCCESS.name,
                    sendOperationId = sendOperationId,
                    completedAt = System.currentTimeMillis(),
                    errorClass = null,
                    errorHmac = null
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record success for $commandId: ${e.message}")
            }
        }
    }

    fun recordExecutionFailure(
        context: Context,
        commandId: String,
        errorClass: String?,
        errorMessage: String? = null
    ) {
        commandScope.launch {
            try {
                val errorHmac = ShadowHmacHelper.calculateHmac(errorMessage)
                context.getMessagesDB().RemoteCommandDao().recordExecutionResult(
                    commandId = commandId,
                    state = RemoteCommandState.FAILED.name,
                    sendOperationId = null,
                    completedAt = System.currentTimeMillis(),
                    errorClass = errorClass,
                    errorHmac = errorHmac
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record failure for $commandId: ${e.message}")
            }
        }
    }
}
