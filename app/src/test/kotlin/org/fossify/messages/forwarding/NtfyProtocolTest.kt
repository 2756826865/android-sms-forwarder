package org.fossify.messages.forwarding

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class NtfyProtocolTest {
    @Test fun chineseTitleUsesUtf8EncodedWord() {
        val title = "短信通知"
        val header = NtfyProtocol.titleHeader(title)
        val encoded = header.removePrefix("=?UTF-8?B?").removeSuffix("?=")
        assertEquals(title, String(Base64.getDecoder().decode(encoded), Charsets.UTF_8))
    }

    @Test fun printableAsciiTitleRemainsReadable() {
        assertEquals("SMS notice", NtfyProtocol.titleHeader("SMS notice"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun topicCannotEscapeUrlPath() { NtfyProtocol.requireTopic("alerts/other") }

    @Test(expected = IllegalArgumentException::class)
    fun topicLengthIsBounded() { NtfyProtocol.requireTopic("a".repeat(65)) }

    @Test fun topicAllowsMaximumLength() {
        assertEquals("a".repeat(64), NtfyProtocol.requireTopic("a".repeat(64)))
    }
}
