package org.fossify.messages.forwarding

import java.net.HttpURLConnection

/** Releases the connection even when configuration, I/O or response validation throws. */
internal inline fun <T> HttpURLConnection.withDisconnect(block: HttpURLConnection.() -> T): T =
    try {
        block()
    } finally {
        disconnect()
    }
