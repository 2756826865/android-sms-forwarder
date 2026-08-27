package org.fossify.messages.helpers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.models.SmsSendContext
import org.fossify.messages.models.SmsSendDeliveryState
import org.fossify.messages.models.SmsSendOperationEntity
import org.fossify.messages.models.SmsSendPartEntity
import org.fossify.messages.models.SmsSendState
import org.fossify.messages.models.SmsSendTriggerType
import kotlin.coroutines.cancellation.CancellationException

/**
 * Shadow repository for SMS send operations.
 *
 * Observes the real send chain without intercepting or modifying it.
 * All persistence is asynchronous and fail-open: any database error
 * is logged and never propagated to the caller.
 *
 * HMAC for address and body is computed inside the async scope to
 * avoid blocking the business thread. Plaintext address/body is never
 * written to the database.
 */
object SmsSendRepository {
    private const val TAG = "SmsSendRepository"
    private val sendScope = CoroutineScope(
        Dispatchers.IO.limitedParallelism(1) + SupervisorJob()
    )

    fun createOperation(context: Context, sendOperationId: String, sendContext: SmsSendContext) {
        if (!Config.newInstance(context).smsSendOperationShadowEnabled) return
        sendScope.launch {
            try {
                val addressHmac = ShadowHmacHelper.calculateHmac(sendContext.address)
                val bodyHmac = ShadowHmacHelper.calculateHmac(sendContext.body)
                val operation = SmsSendOperationEntity(
                    sendOperationId = sendOperationId,
                    triggerType = sendContext.triggerType.name,
                    addressHmac = addressHmac,
                    bodyHmac = bodyHmac,
                    bodyLength = sendContext.body?.length,
                    subscriptionId = sendContext.subscriptionId,
                    threadId = sendContext.threadId,
                    requireDeliveryReport = sendContext.requireDeliveryReport,
                    messageUri = sendContext.messageUri,
                    state = SmsSendState.PENDING.name
                )
                context.getMessagesDB().SmsSendDao().insertOperation(operation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to create send operation: ${e.message}")
            }
        }
    }

    fun associateProvider(context: Context, operationId: String, providerMessageId: Long?, messageUri: String?) {
        if (!Config.newInstance(context).smsSendOperationShadowEnabled) return
        sendScope.launch {
            try {
                context.getMessagesDB().SmsSendDao().updateProviderAssociation(operationId, providerMessageId, messageUri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to associate provider: ${e.message}")
            }
        }
    }

    fun recordPartCount(context: Context, operationId: String, partCount: Int) {
        if (!Config.newInstance(context).smsSendOperationShadowEnabled) return
        sendScope.launch {
            try {
                val dao = context.getMessagesDB().SmsSendDao()
                val existing = dao.getOperationById(operationId) ?: return@launch
                dao.updateOperation(existing.copy(partCount = partCount))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record part count: ${e.message}")
            }
        }
    }

    fun recordSubmitting(context: Context, operationId: String) {
        if (!Config.newInstance(context).smsSendOperationShadowEnabled) return
        sendScope.launch {
            try {
                val now = System.currentTimeMillis()
                val dao = context.getMessagesDB().SmsSendDao()
                val existing = dao.getOperationById(operationId) ?: return@launch
                dao.updateOperation(existing.copy(state = SmsSendState.SUBMITTING.name, submittingAt = now, updatedAt = now))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record submitting: ${e.message}")
            }
        }
    }

    fun recordSubmitted(context: Context, operationId: String, partCount: Int) {
        if (!Config.newInstance(context).smsSendOperationShadowEnabled) return
        sendScope.launch {
            try {
                val now = System.currentTimeMillis()
                val dao = context.getMessagesDB().SmsSendDao()
                val existing = dao.getOperationById(operationId) ?: return@launch
                dao.updateOperation(existing.copy(
                    state = SmsSendState.SUBMITTED.name,
                    partCount = partCount,
                    submittedAt = now,
                    updatedAt = now
                ))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record submitted: ${e.message}")
            }
        }
    }

    /**
     * Pure function to aggregate the Send Axis state from all parts of an operation.
     *
     * Rules:
     * 1. If existing operation is already FAILED or UNKNOWN_AFTER_SUBMIT, it remains in that state (Idempotency / Terminal).
     * 2. If any part has sentState == FAILED -> FAILED
     * 3. If all parts have sentState == SENT -> SENT
     * 4. Otherwise (any PENDING / SUBMITTED) -> SUBMITTED (in-flight)
     */
    fun aggregateSendState(
        parts: List<SmsSendPartEntity>,
        currentSendState: SmsSendState = SmsSendState.SUBMITTED
    ): SmsSendState {
        if (currentSendState == SmsSendState.FAILED || currentSendState == SmsSendState.UNKNOWN_AFTER_SUBMIT) {
            return currentSendState
        }
        if (parts.isEmpty()) {
            return currentSendState
        }

        // Rule 1: Failure priority
        if (parts.any { it.sentState == SmsSendState.FAILED.name }) {
            return SmsSendState.FAILED
        }

        // Rule 2: Sent success (all parts are sent)
        val allSent = parts.all {
            it.sentState == SmsSendState.SENT.name
        }
        if (allSent) {
            return SmsSendState.SENT
        }

        // Rule 3: In-flight / pending
        return SmsSendState.SUBMITTED
    }

    /**
     * Pure function to aggregate the Delivery Axis state from all parts of an operation.
     *
     * Rules:
     * 1. If requireDeliveryReport is false -> NOT_REQUESTED
     * 2. In multipart, only parts where delivery report was requested (usually the last part) receive callbacks.
     * 3. If any part has deliveredState == DELIVERY_FAILED -> DELIVERY_FAILED
     * 4. If any part has deliveredState == DELIVERED -> DELIVERED
     * 5. Otherwise -> WAITING
     */
    fun aggregateDeliveryState(
        parts: List<SmsSendPartEntity>,
        requireDeliveryReport: Boolean,
        currentDeliveryState: SmsSendDeliveryState? = null
    ): SmsSendDeliveryState {
        if (!requireDeliveryReport) {
            return SmsSendDeliveryState.NOT_REQUESTED
        }
        if (parts.isEmpty()) {
            return currentDeliveryState ?: SmsSendDeliveryState.WAITING
        }

        // Rule 2: Any requested delivery failed -> DELIVERY_FAILED
        if (parts.any { it.deliveredState == SmsSendDeliveryState.DELIVERY_FAILED.name }) {
            return SmsSendDeliveryState.DELIVERY_FAILED
        }

        // Rule 3: Any requested delivery succeeded -> DELIVERED (Supports multipart where only last part has deliveryIntent)
        if (parts.any { it.deliveredState == SmsSendDeliveryState.DELIVERED.name }) {
            return SmsSendDeliveryState.DELIVERED
        }

        // Rule 4: Waiting for delivery report
        return SmsSendDeliveryState.WAITING
    }

    /**
     * Backward-compatible helper delegating to [aggregateSendState].
     */
    fun aggregateOperationState(
        parts: List<SmsSendPartEntity>,
        currentOperationState: SmsSendState = SmsSendState.SUBMITTED
    ): SmsSendState = aggregateSendState(parts, currentOperationState)

    fun recordSentResult(context: Context, operationId: String, partIndex: Int?, resultCode: Int?) {
        if (!Config.newInstance(context).smsSendOperationShadowEnabled) return
        sendScope.launch {
            try {
                val now = System.currentTimeMillis()
                val dao = context.getMessagesDB().SmsSendDao()
                val existing = dao.getOperationById(operationId) ?: return@launch
                val isSuccess = (resultCode == android.app.Activity.RESULT_OK)
                val targetPartState = if (isSuccess) SmsSendState.SENT.name else SmsSendState.FAILED.name

                if (partIndex != null) {
                    val parts = dao.getPartsByOperationId(operationId)
                    parts.find { it.partIndex == partIndex }?.let { part ->
                        dao.updatePart(
                            part.copy(
                                sentState = targetPartState,
                                sentResultCode = resultCode,
                                sentAt = now,
                                updatedAt = now
                            )
                        )
                    }
                }

                val allParts = dao.getPartsByOperationId(operationId)
                val currentOpState = runCatching { SmsSendState.valueOf(existing.state) }.getOrDefault(SmsSendState.SUBMITTED)
                val newSendState = aggregateSendState(allParts, currentOpState)

                dao.updateOperation(
                    existing.copy(
                        state = newSendState.name,
                        sentAt = if (newSendState == SmsSendState.SENT && existing.sentAt == null) now else existing.sentAt,
                        failedAt = if (newSendState == SmsSendState.FAILED && existing.failedAt == null) now else existing.failedAt,
                        updatedAt = now
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record sent result: ${e.message}")
            }
        }
    }

    fun recordDeliveredResult(context: Context, operationId: String, partIndex: Int?, resultCode: Int?) {
        if (!Config.newInstance(context).smsSendOperationShadowEnabled) return
        sendScope.launch {
            try {
                val now = System.currentTimeMillis()
                val dao = context.getMessagesDB().SmsSendDao()
                val existing = dao.getOperationById(operationId) ?: return@launch
                val isDelivered = (resultCode == android.provider.Telephony.Sms.STATUS_COMPLETE || resultCode == android.app.Activity.RESULT_OK)
                val targetPartDeliveredState = if (isDelivered) SmsSendDeliveryState.DELIVERED.name else SmsSendDeliveryState.DELIVERY_FAILED.name

                if (partIndex != null) {
                    val parts = dao.getPartsByOperationId(operationId)
                    parts.find { it.partIndex == partIndex }?.let { part ->
                        dao.updatePart(
                            part.copy(
                                deliveredState = targetPartDeliveredState,
                                deliveredResultCode = resultCode,
                                deliveredAt = now,
                                updatedAt = now
                            )
                        )
                    }
                }

                val allParts = dao.getPartsByOperationId(operationId)
                val currentSendState = runCatching { SmsSendState.valueOf(existing.state) }.getOrDefault(SmsSendState.SUBMITTED)
                val aggregatedSendState = aggregateSendState(allParts, currentSendState)
                val aggregatedDeliveryState = aggregateDeliveryState(allParts, existing.requireDeliveryReport)

                dao.updateOperation(
                    existing.copy(
                        state = aggregatedSendState.name,
                        deliveredAt = if (aggregatedDeliveryState == SmsSendDeliveryState.DELIVERED && existing.deliveredAt == null) now else existing.deliveredAt,
                        updatedAt = now
                    )
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record delivered result: ${e.message}")
            }
        }
    }

    fun recordFailure(context: Context, operationId: String, errorClass: String?) {
        if (!Config.newInstance(context).smsSendOperationShadowEnabled) return
        sendScope.launch {
            try {
                val now = System.currentTimeMillis()
                val dao = context.getMessagesDB().SmsSendDao()
                val existing = dao.getOperationById(operationId) ?: return@launch
                dao.updateOperation(existing.copy(state = SmsSendState.FAILED.name, errorClass = errorClass, failedAt = now, updatedAt = now))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record failure: ${e.message}")
            }
        }
    }

    fun recordParts(context: Context, operationId: String, partCount: Int) {
        if (!Config.newInstance(context).smsSendOperationShadowEnabled) return
        sendScope.launch {
            try {
                val dao = context.getMessagesDB().SmsSendDao()
                for (i in 0 until partCount) {
                    dao.insertPart(SmsSendPartEntity(
                        sendOperationId = operationId,
                        partIndex = i,
                        partCount = partCount
                    ))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record parts: ${e.message}")
            }
        }
    }
}
