package org.fossify.messages.recovery

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.models.OutboxSourceType
import org.fossify.messages.models.OutboxTaskState
import org.fossify.messages.models.RecoveryAction
import org.fossify.messages.models.RecoveryObjectType
import org.fossify.messages.models.RecoveryRecordEntity
import org.fossify.messages.models.RecoverySummary
import org.fossify.messages.models.RecoveryTriggerSource
import org.fossify.messages.models.RemoteCommandState
import org.fossify.messages.models.SmsSendState
import java.util.UUID

/**
 * 恢复与自愈扫描引擎
 *
 * 核心原则：
 * 1. 只修复与对齐状态，绝不直接执行物理发送或调用执行器；
 * 2. 严禁自动重新发送短信；
 * 3. 解除死锁软锁、推进到期退避任务；
 * 4. 沉淀 UNKNOWN_AFTER_SUBMIT 与跨实体事实对账；
 * 5. 记录 RecoveryRecord 审计事实。
 */
object RecoveryEngine {
    private const val TAG = "RecoveryEngine"
    private const val SUBMITTING_TIMEOUT_MS = 5 * 60_000L   // 5 min
    private const val SUBMITTED_TIMEOUT_MS = 30 * 60_000L   // 30 min

    suspend fun runRecoveryScan(
        context: Context,
        triggerSource: String = RecoveryTriggerSource.MANUAL
    ): RecoverySummary = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val db = context.getMessagesDB()
        val outboxDao = db.OutboxTaskDao()
        val smsSendDao = db.SmsSendDao()
        val remoteCommandDao = db.RemoteCommandDao()
        val shadowDao = db.ShadowDaos()
        val recoveryDao = db.RecoveryRecordDao()

        var unlockedTasks = 0
        var resetPendingTasks = 0
        var markedUnknownCount = 0
        var alignedCount = 0
        var totalScanned = 0

        // =====================================================================
        // 1. Outbox 死锁恢复 (RUNNING + lockExpiresAt < now)
        // =====================================================================
        val deadlockedTasks = try {
            outboxDao.findDeadlockedTasks(now)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan deadlocked outbox tasks: ${e.message}")
            emptyList()
        }
        totalScanned += deadlockedTasks.size

        for (task in deadlockedTasks) {
            try {
                outboxDao.releaseLock(task.taskId, now)
                outboxDao.updateState(task.taskId, OutboxTaskState.PENDING.name, now)
                unlockedTasks++

                val record = RecoveryRecordEntity(
                    recordId = UUID.randomUUID().toString(),
                    scanTime = now,
                    triggerSource = triggerSource,
                    objectType = RecoveryObjectType.OUTBOX_TASK,
                    objectId = task.taskId,
                    initialStatus = OutboxTaskState.RUNNING.name,
                    recoveredStatus = OutboxTaskState.PENDING.name,
                    actionTaken = RecoveryAction.UNLOCK_TASK,
                    detailMessage = "Unlocked deadlocked task after lock expired at ${task.lockExpiresAt}",
                    createdAt = now
                )
                recoveryDao.insert(record)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to recover deadlocked task ${task.taskId}: ${e.message}")
            }
        }

        // =====================================================================
        // 2. Outbox 到期退避推进 (RETRY_WAITING + nextRetryAt <= now)
        // =====================================================================
        val dueRetryTasks = try {
            outboxDao.findDueRetryTasks(now)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan due retry tasks: ${e.message}")
            emptyList()
        }
        totalScanned += dueRetryTasks.size

        for (task in dueRetryTasks) {
            try {
                outboxDao.updateState(task.taskId, OutboxTaskState.PENDING.name, now)
                resetPendingTasks++

                val record = RecoveryRecordEntity(
                    recordId = UUID.randomUUID().toString(),
                    scanTime = now,
                    triggerSource = triggerSource,
                    objectType = RecoveryObjectType.OUTBOX_TASK,
                    objectId = task.taskId,
                    initialStatus = OutboxTaskState.RETRY_WAITING.name,
                    recoveredStatus = OutboxTaskState.PENDING.name,
                    actionTaken = RecoveryAction.RESET_PENDING,
                    detailMessage = "Advanced due retry task to PENDING (nextRetryAt=${task.nextRetryAt})",
                    createdAt = now
                )
                recoveryDao.insert(record)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to advance retry task ${task.taskId}: ${e.message}")
            }
        }

        // =====================================================================
        // 3. SmsSendOperation 超时恢复与 UNKNOWN_AFTER_SUBMIT 沉淀 (绝不重发)
        // =====================================================================
        // 3.1 SUBMITTING 超时 (> 5 min)
        val submittingCutoff = now - SUBMITTING_TIMEOUT_MS
        val staleSubmittingOps = try {
            smsSendDao.findStaleSubmittingOperations(submittingCutoff)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan stale SUBMITTING operations: ${e.message}")
            emptyList()
        }
        totalScanned += staleSubmittingOps.size

        for (op in staleSubmittingOps) {
            try {
                smsSendDao.updateState(op.sendOperationId, SmsSendState.FAILED.name, now)
                markedUnknownCount++

                val record = RecoveryRecordEntity(
                    recordId = UUID.randomUUID().toString(),
                    scanTime = now,
                    triggerSource = triggerSource,
                    objectType = RecoveryObjectType.SMS_OPERATION,
                    objectId = op.sendOperationId,
                    initialStatus = SmsSendState.SUBMITTING.name,
                    recoveredStatus = SmsSendState.FAILED.name,
                    actionTaken = RecoveryAction.MARK_FAILED,
                    detailMessage = "SUBMITTING timed out (> 5 min) without API submission",
                    createdAt = now
                )
                recoveryDao.insert(record)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to recover SUBMITTING operation ${op.sendOperationId}: ${e.message}")
            }
        }

        // 3.2 SUBMITTED 超时 (> 30 min) -> UNKNOWN_AFTER_SUBMIT
        val submittedCutoff = now - SUBMITTED_TIMEOUT_MS
        val staleSubmittedOps = try {
            smsSendDao.findStaleSubmittedOperations(submittedCutoff)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan stale SUBMITTED operations: ${e.message}")
            emptyList()
        }
        totalScanned += staleSubmittedOps.size

        for (op in staleSubmittedOps) {
            try {
                smsSendDao.updateState(op.sendOperationId, SmsSendState.UNKNOWN_AFTER_SUBMIT.name, now)
                markedUnknownCount++

                val record = RecoveryRecordEntity(
                    recordId = UUID.randomUUID().toString(),
                    scanTime = now,
                    triggerSource = triggerSource,
                    objectType = RecoveryObjectType.SMS_OPERATION,
                    objectId = op.sendOperationId,
                    initialStatus = SmsSendState.SUBMITTED.name,
                    recoveredStatus = SmsSendState.UNKNOWN_AFTER_SUBMIT.name,
                    actionTaken = RecoveryAction.MARK_UNKNOWN,
                    detailMessage = "SUBMITTED timed out (> 30 min) without Sent/Delivered receipt. Marked UNKNOWN_AFTER_SUBMIT.",
                    createdAt = now
                )
                recoveryDao.insert(record)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to recover SUBMITTED operation ${op.sendOperationId}: ${e.message}")
            }
        }

        // =====================================================================
        // 4. RemoteCommandExecution 状态对账 (RUNNING -> 对齐底层 SmsSendOperation)
        // =====================================================================
        val runningCommands = try {
            remoteCommandDao.findRunningCommands()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan running remote commands: ${e.message}")
            emptyList()
        }
        totalScanned += runningCommands.size

        for (cmd in runningCommands) {
            val sendOpId = cmd.sendOperationId
            if (!sendOpId.isNullOrBlank()) {
                val sendOp = smsSendDao.getOperationById(sendOpId)
                if (sendOp != null) {
                    if (sendOp.state in setOf(SmsSendState.SENT.name, SmsSendState.DELIVERED.name)) {
                        remoteCommandDao.recordExecutionResult(
                            commandId = cmd.commandId,
                            state = RemoteCommandState.SUCCESS.name,
                            sendOperationId = sendOpId,
                            completedAt = sendOp.sentAt ?: now,
                            errorClass = null,
                            errorHmac = null,
                            now = now
                        )
                        alignedCount++

                        recoveryDao.insert(
                            RecoveryRecordEntity(
                                recordId = UUID.randomUUID().toString(),
                                scanTime = now,
                                triggerSource = triggerSource,
                                objectType = RecoveryObjectType.REMOTE_COMMAND,
                                objectId = cmd.commandId,
                                initialStatus = RemoteCommandState.RUNNING.name,
                                recoveredStatus = RemoteCommandState.SUCCESS.name,
                                actionTaken = RecoveryAction.SYNC_SUCCESS,
                                detailMessage = "Synchronized with successful SmsSendOperation $sendOpId",
                                createdAt = now
                            )
                        )
                    } else if (sendOp.state in setOf(SmsSendState.FAILED.name, SmsSendState.UNKNOWN_AFTER_SUBMIT.name)) {
                        remoteCommandDao.recordExecutionResult(
                            commandId = cmd.commandId,
                            state = RemoteCommandState.FAILED.name,
                            sendOperationId = sendOpId,
                            completedAt = sendOp.failedAt ?: now,
                            errorClass = sendOp.errorClass ?: "SmsSendOperationFailedException",
                            errorHmac = null,
                            now = now
                        )
                        alignedCount++

                        recoveryDao.insert(
                            RecoveryRecordEntity(
                                recordId = UUID.randomUUID().toString(),
                                scanTime = now,
                                triggerSource = triggerSource,
                                objectType = RecoveryObjectType.REMOTE_COMMAND,
                                objectId = cmd.commandId,
                                initialStatus = RemoteCommandState.RUNNING.name,
                                recoveredStatus = RemoteCommandState.FAILED.name,
                                actionTaken = RecoveryAction.SYNC_FAILED,
                                detailMessage = "Synchronized with failed SmsSendOperation $sendOpId (${sendOp.state})",
                                createdAt = now
                            )
                        )
                    }
                }
            }
        }

        // =====================================================================
        // 5. OutboxTask 事实对账 (RUNNING -> 底层事实已完成时同步标记 SUCCESS)
        // =====================================================================
        val runningOutboxTasks = try {
            outboxDao.findRunningTasks()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan running outbox tasks: ${e.message}")
            emptyList()
        }
        totalScanned += runningOutboxTasks.size

        for (task in runningOutboxTasks) {
            if (task.sourceType == OutboxSourceType.REMOTE_COMMAND && task.sourceId.isNotBlank()) {
                val remoteCmd = remoteCommandDao.findById(task.sourceId)
                if (remoteCmd?.executionState == RemoteCommandState.SUCCESS.name) {
                    outboxDao.markSuccess(task.taskId, now)
                    alignedCount++

                    recoveryDao.insert(
                        RecoveryRecordEntity(
                            recordId = UUID.randomUUID().toString(),
                            scanTime = now,
                            triggerSource = triggerSource,
                            objectType = RecoveryObjectType.OUTBOX_TASK,
                            objectId = task.taskId,
                            initialStatus = OutboxTaskState.RUNNING.name,
                            recoveredStatus = OutboxTaskState.SUCCESS.name,
                            actionTaken = RecoveryAction.ALIGN_STATE,
                            detailMessage = "Aligned outbox task with RemoteCommandExecution SUCCESS",
                            createdAt = now
                        )
                    )
                }
            } else if (task.sourceType == OutboxSourceType.FORWARDING_RULE && task.sourceId.isNotBlank()) {
                val delivery = shadowDao.getDeliveryById(task.sourceId)
                if (delivery != null && delivery.state in setOf("DELIVERED", "SUCCESS")) {
                    outboxDao.markSuccess(task.taskId, now)
                    alignedCount++

                    recoveryDao.insert(
                        RecoveryRecordEntity(
                            recordId = UUID.randomUUID().toString(),
                            scanTime = now,
                            triggerSource = triggerSource,
                            objectType = RecoveryObjectType.OUTBOX_TASK,
                            objectId = task.taskId,
                            initialStatus = OutboxTaskState.RUNNING.name,
                            recoveredStatus = OutboxTaskState.SUCCESS.name,
                            actionTaken = RecoveryAction.ALIGN_STATE,
                            detailMessage = "Aligned outbox task with ForwardingShadowDelivery DELIVERED",
                            createdAt = now
                        )
                    )
                }
            }
        }

        // =====================================================================
        // 6. ForwardingDelivery 状态对账 (RUNNING -> 对齐 OutboxTask 终态)
        // =====================================================================
        val runningDeliveries = try {
            shadowDao.findRunningDeliveries()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan running deliveries: ${e.message}")
            emptyList()
        }
        totalScanned += runningDeliveries.size

        for (delivery in runningDeliveries) {
            val tasks = outboxDao.findBySource(OutboxSourceType.FORWARDING_RULE, delivery.deliveryId)
            val matchingTask = tasks.firstOrNull()
            if (matchingTask != null) {
                if (matchingTask.state == OutboxTaskState.SUCCESS.name) {
                    shadowDao.updateDeliveryState(delivery.deliveryId, "DELIVERED", now)
                    alignedCount++

                    recoveryDao.insert(
                        RecoveryRecordEntity(
                            recordId = UUID.randomUUID().toString(),
                            scanTime = now,
                            triggerSource = triggerSource,
                            objectType = RecoveryObjectType.FORWARDING_DELIVERY,
                            objectId = delivery.deliveryId,
                            initialStatus = "RUNNING",
                            recoveredStatus = "DELIVERED",
                            actionTaken = RecoveryAction.ALIGN_STATE,
                            detailMessage = "Aligned delivery with OutboxTask SUCCESS",
                            createdAt = now
                        )
                    )
                } else if (matchingTask.state == OutboxTaskState.FAILED.name) {
                    shadowDao.updateDeliveryState(delivery.deliveryId, "FAILED", now)
                    alignedCount++

                    recoveryDao.insert(
                        RecoveryRecordEntity(
                            recordId = UUID.randomUUID().toString(),
                            scanTime = now,
                            triggerSource = triggerSource,
                            objectType = RecoveryObjectType.FORWARDING_DELIVERY,
                            objectId = delivery.deliveryId,
                            initialStatus = "RUNNING",
                            recoveredStatus = "FAILED",
                            actionTaken = RecoveryAction.ALIGN_STATE,
                            detailMessage = "Aligned delivery with OutboxTask FAILED",
                            createdAt = now
                        )
                    )
                }
            }
        }

        val totalRecovered = unlockedTasks + resetPendingTasks + markedUnknownCount + alignedCount
        Log.i(
            TAG,
            "Recovery scan [$triggerSource] finished: scanned=$totalScanned, recovered=$totalRecovered " +
                    "(unlocked=$unlockedTasks, resetPending=$resetPendingTasks, markedUnknown=$markedUnknownCount, aligned=$alignedCount)"
        )

        RecoverySummary(
            triggerSource = triggerSource,
            scanTime = now,
            totalScanned = totalScanned,
            totalRecovered = totalRecovered,
            unlockedTasks = unlockedTasks,
            resetPendingTasks = resetPendingTasks,
            markedUnknownCount = markedUnknownCount
        )
    }
}
