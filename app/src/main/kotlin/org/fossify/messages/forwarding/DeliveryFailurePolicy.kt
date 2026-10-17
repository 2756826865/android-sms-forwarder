package org.fossify.messages.forwarding

import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

internal object DeliveryFailurePolicy {
    /** A read/write failure can occur after acceptance; an initial connection failure cannot. */
    fun isUnknown(error: Throwable): Boolean {
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
