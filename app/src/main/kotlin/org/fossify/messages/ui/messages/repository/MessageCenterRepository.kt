package org.fossify.messages.ui.messages.repository

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.ui.messages.model.MessageDetailTimeline
import org.fossify.messages.ui.messages.model.MessageHistoryItem
import org.fossify.messages.ui.messages.model.MessageTimelineStage

/**
 * 消息中心只读数据仓库
 */
class MessageCenterRepository(private val context: Context) {

    suspend fun getMessageHistory(limit: Int = 50): List<MessageHistoryItem> = withContext(Dispatchers.IO) {
        val smsDao = context.getMessagesDB().SmsSendDao()
        val operations = smsDao.getRecentOperations(limit)

        operations.map { op ->
            val parts = smsDao.getPartsByOperationId(op.sendOperationId)
            val partsDelivered = parts.count { it.deliveredState == "DELIVERED" || it.deliveredResultCode == 0 }
            MessageHistoryItem(
                operationId = op.sendOperationId,
                triggerType = op.triggerType,
                state = op.state,
                addressHmac = op.addressHmac,
                bodyLength = op.bodyLength ?: 0,
                subscriptionId = op.subscriptionId ?: -1,
                partsCount = if (parts.isNotEmpty()) parts.size else 1,
                partsDeliveredCount = partsDelivered,
                parts = parts,
                errorClass = op.errorClass,
                submittedAt = op.submittedAt,
                sentAt = op.sentAt,
                deliveredAt = op.deliveredAt,
                createdAt = op.createdAt
            )
        }
    }

    suspend fun getMessageDetailTimeline(operationId: String): MessageDetailTimeline? = withContext(Dispatchers.IO) {
        val smsDao = context.getMessagesDB().SmsSendDao()
        val op = smsDao.getOperationById(operationId) ?: return@withContext null
        val parts = smsDao.getPartsByOperationId(operationId)

        val stages = mutableListOf<MessageTimelineStage>()
        stages.add(MessageTimelineStage("Created", op.createdAt, "SUCCESS", "Trigger: ${op.triggerType}"))
        if (op.submittedAt != null) {
            stages.add(MessageTimelineStage("Submitting/Submitted", op.submittedAt, "SUCCESS", "SubId: ${op.subscriptionId}"))
        }
        if (op.sentAt != null) {
            stages.add(MessageTimelineStage("Sent to Baseband", op.sentAt, "SUCCESS", "Sent Callback OK"))
        }
        if (op.deliveredAt != null) {
            stages.add(MessageTimelineStage("Delivered Receipt", op.deliveredAt, "SUCCESS", "Operator Receipt Received"))
        }
        if (op.failedAt != null) {
            stages.add(MessageTimelineStage("Failed", op.failedAt, "FAILED", op.errorClass ?: "Generic Failure"))
        }

        MessageDetailTimeline(
            operationId = operationId,
            operation = op,
            parts = parts,
            stages = stages
        )
    }
}
