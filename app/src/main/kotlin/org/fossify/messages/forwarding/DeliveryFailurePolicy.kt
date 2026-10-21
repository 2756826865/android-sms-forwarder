package org.fossify.messages.forwarding

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

internal object DeliveryFailurePolicy {
    fun requireRemoteAccepted(success: Boolean, code: String, message: String, providerCode: Int? = null) {
        if (success) return
        val detail = providerCode?.let { "$message（错误码 $it）" } ?: message
        if (code in setOf("ack_timeout", "invalid_ack", "internal_error"))
            throw IOException("$detail；提交结果未知，停止自动重发")
        throw IllegalStateException(detail)
    }

    /** A read/write failure can occur after acceptance; an initial connection failure cannot. */
    fun isUnknown(error: Throwable): Boolean {
        if (error is SmsDirectPreSubmitFailure) return false
        var cause: Throwable? = error
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        while (cause != null && seen.add(cause)) {
            if (cause is TimeoutException || (cause is IOException && cause !is ConnectException &&
                cause !is UnknownHostException && cause !is NoRouteToHostException)) return true
            cause = cause.cause
        }
        return false
    }
}
