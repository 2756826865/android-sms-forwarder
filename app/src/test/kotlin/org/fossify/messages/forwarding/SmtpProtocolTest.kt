package org.fossify.messages.forwarding

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class SmtpProtocolTest {
    @Test fun completeMultilineReplyIsAccepted() {
        assertEquals(250, SmtpProtocol.readReply(StringReader("250-server\r\n250-AUTH LOGIN\r\n250 SIZE 1000\r\n").buffered()))
    }

    @Test(expected = IllegalStateException::class)
    fun truncatedMultilineReplyIsRejected() {
        SmtpProtocol.readReply(StringReader("250-server\r\n250-AUTH LOGIN\r\n").buffered())
    }

    @Test(expected = IllegalArgumentException::class)
    fun mismatchedMultilineReplyIsRejected() {
        SmtpProtocol.readReply(StringReader("250-server\r\n550 rejected\r\n").buffered())
    }

    @Test(expected = IllegalArgumentException::class)
    fun mailboxCannotInjectSmtpCommands() {
        SmtpProtocol.requireMailbox("user@example.com\r\nRCPT TO:other@example.com")
    }

    @Test fun mailboxIsTrimmed() {
        assertEquals("user@example.com", SmtpProtocol.requireMailbox(" user@example.com "))
    }
}
