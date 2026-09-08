package org.fossify.messages

import org.fossify.messages.forwarding.withDisconnect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class HttpConnectionScopeTest {
    private class FakeConnection : HttpURLConnection(URL("https://example.invalid")) {
        var disconnectCount = 0
        override fun connect() = Unit
        override fun usingProxy() = false
        override fun disconnect() { disconnectCount++ }
    }

    @Test
    fun releasesAfterSuccessAndReturnsValue() {
        val connection = FakeConnection()
        assertEquals("response", connection.withDisconnect { "response" })
        assertEquals(1, connection.disconnectCount)
    }

    @Test
    fun releasesAfterFailureAndPreservesException() {
        val connection = FakeConnection()
        val failure = IOException("read timeout")
        val result = runCatching { connection.withDisconnect { throw failure } }
        assertSame(failure, result.exceptionOrNull())
        assertEquals(1, connection.disconnectCount)
    }
}
