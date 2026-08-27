package org.fossify.messages.helpers

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.models.ForwardingShadowAttempt
import org.fossify.messages.models.ForwardingShadowDelivery
import org.fossify.messages.models.MessageOperation
import org.fossify.messages.models.MessageOperationStep
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

object ShadowRepository {
    private const val TAG = "ShadowRepository"
    private val shadowScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun recordOperation(context: Context, operation: MessageOperation) {
        if (!Config.newInstance(context).shadowOperationTrackingEnabled) return
        shadowScope.launch {
            try {
                context.getMessagesDB().ShadowDaos().insertOperation(operation)
                incrementCounterInternal(context, "operationCreated")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record shadow operation: ${e.message}")
            }
        }
    }

    fun updateOperation(context: Context, operationId: String, transform: (MessageOperation) -> MessageOperation) {
        if (!Config.newInstance(context).shadowOperationTrackingEnabled) return
        shadowScope.launch {
            try {
                val dao = context.getMessagesDB().ShadowDaos()
                val existing = dao.getOperation(operationId)
                if (existing != null) {
                    dao.updateOperation(transform(existing))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update shadow operation: ${e.message}")
            }
        }
    }

    fun recordStep(context: Context, operationId: String, type: String, status: String, detail: String? = null) {
        if (!Config.newInstance(context).shadowOperationTrackingEnabled) return
        shadowScope.launch {
            try {
                val step = MessageOperationStep(
                    operationId = operationId,
                    stepType = type,
                    status = status,
                    detail = detail
                )
                context.getMessagesDB().ShadowDaos().insertStep(step)
                incrementCounterInternal(context, "${type}_${status}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record shadow step: ${e.message}")
            }
        }
    }

    fun recordDelivery(context: Context, operationId: String, channel: String, state: String) {
        if (!Config.newInstance(context).shadowOperationTrackingEnabled) return
        shadowScope.launch {
            try {
                val delivery = ForwardingShadowDelivery(
                    operationId = operationId,
                    channel = channel,
                    state = state
                )
                context.getMessagesDB().ShadowDaos().insertDelivery(delivery)
                incrementCounterInternal(context, "deliveryObserved")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record shadow delivery: ${e.message}")
            }
        }
    }

    fun recordForwardingDeliveryState(context: Context, deliveryId: String, state: String) {
        if (!Config.newInstance(context).shadowOperationTrackingEnabled) return
        shadowScope.launch {
            try {
                context.getMessagesDB().ShadowDaos().updateDeliveryState(deliveryId, state)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update shadow delivery state: ${e.message}")
            }
        }
    }

    fun recordAttempt(context: Context, operationId: String, channel: String, attempt: ForwardingShadowAttempt) {
        if (!Config.newInstance(context).shadowOperationTrackingEnabled) return
        shadowScope.launch {
            try {
                val dao = context.getMessagesDB().ShadowDaos()
                val delivery = dao.getDelivery(operationId, channel)
                if (delivery != null) {
                    dao.insertAttempt(attempt.copy(deliveryId = delivery.deliveryId))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to record shadow attempt: ${e.message}")
            }
        }
    }

    fun incrementCounter(context: Context, eventType: String) {
        if (!Config.newInstance(context).shadowOperationTrackingEnabled) return
        shadowScope.launch {
            incrementCounterInternal(context, eventType)
        }
    }

    private suspend fun incrementCounterInternal(context: Context, eventType: String) {
        try {
            val bucketKey = SimpleDateFormat("yyyy-MM-dd-HH", Locale.US).format(Date())
            context.getMessagesDB().ShadowDaos().incrementCounter(bucketKey, eventType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Silent fail for diagnostic counters
        }
    }
}
