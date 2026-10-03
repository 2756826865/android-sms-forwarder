package org.fossify.messages.forwarding

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QmsgProtocolTest {
    @Test
    fun `plain key uses current v3 endpoint`() {
        assertEquals(
            "https://qmsg.zendee.cn/v3/send/abc123",
            QmsgProtocol.endpoint(" abc123 "),
        )
    }

    @Test
    fun `full endpoint is preserved for compatible deployments`() {
        assertEquals(
            "https://example.com/v3/send/key",
            QmsgProtocol.endpoint("https://example.com/v3/send/key"),
        )
    }

    @Test
    fun `message is encoded as form data`() {
        assertEquals("msg=A%2BB+%26+%E9%AA%8C%E8%AF%81%E7%A0%81", QmsgProtocol.formBody("A+B & 验证码"))
    }

    @Test
    fun `accepted response returns asynchronous message id`() {
        val id = QmsgProtocol.requireAccepted(
            JSONObject().put("success", true).put("code", 0).put("data", 63796),
        )
        assertEquals(63796L, id)
    }

    @Test
    fun `accepted response may omit message id`() {
        assertNull(QmsgProtocol.requireAccepted(JSONObject().put("success", true).put("code", 0)))
    }

    @Test(expected = IllegalStateException::class)
    fun `http success with business failure is rejected`() {
        QmsgProtocol.requireAccepted(
            JSONObject().put("success", false).put("code", 500).put("message", "限流"),
        )
    }
}
