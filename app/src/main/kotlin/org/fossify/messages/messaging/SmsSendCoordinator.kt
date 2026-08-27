package org.fossify.messages.messaging

import android.content.Context
import android.util.Log
import org.fossify.messages.helpers.Config
import org.fossify.messages.helpers.SmsSendRepository
import org.fossify.messages.models.SmsSendContext
import java.util.UUID

/**
 * Shadow coordinator for SMS send operations.
 *
 * Observes the real send chain without intercepting or modifying it.
 * Does not call SmsManager, WorkManager, or any business logic.
 * All persistence is delegated to [SmsSendRepository] which is
 * asynchronous and fail-open.
 *
 * UUID is generated synchronously so the operationId is available
 * immediately for correlation, while DB persistence happens async.
 */
object SmsSendCoordinator {
    private const val TAG = "SmsSendCoordinator"

    /**
     * Called at the start of a send operation (after provider insert).
     * Returns the operationId for correlation, or null if shadow
     * tracking is disabled.
     */
    fun beginSend(context: Context, sendContext: SmsSendContext): String? {
        if (!Config.newInstance(context).smsSendOperationShadowEnabled) return null
        val operationId = UUID.randomUUID().toString()
        SmsSendRepository.createOperation(context, operationId, sendContext)
        return operationId
    }

    /**
     * Called just before the SmsManager API call is made.
     */
    fun observeSubmitting(context: Context, operationId: String?) {
        if (operationId == null) return
        SmsSendRepository.recordSubmitting(context, operationId)
    }

    /**
     * Called after the SmsManager API call returns successfully.
     * [partCount] is the number of message parts submitted.
     */
    fun observeApiSubmitted(context: Context, operationId: String?, partCount: Int) {
        if (operationId == null) return
        SmsSendRepository.recordSubmitted(context, operationId, partCount)
        SmsSendRepository.recordParts(context, operationId, partCount)
    }

    /**
     * Called when the SmsManager API call throws an exception.
     * [errorClass] is the exception class name for diagnostics.
     */
    fun observeFailure(context: Context, operationId: String?, errorClass: String?) {
        if (operationId == null) return
        SmsSendRepository.recordFailure(context, operationId, errorClass)
    }
}
