package org.fossify.messages.remote

import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Request
import okhttp3.WebSocket
import okio.ByteString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** No connection is started: replay callbacks locally and verify separation from SMS commands. */
class DingTalkWhitelistRoutingTest {
    private class Socket : WebSocket {
        val sent = mutableListOf<String>()
        override fun request() = Request.Builder().url("https://example.invalid/").build()
        override fun queueSize() = 0L
        override fun send(text: String): Boolean { sent += text; return true }
        override fun send(bytes: ByteString) = false
        override fun close(code: Int, reason: String?) = true
        override fun cancel() = Unit
    }

    private fun replay(client: DingTalkStreamClient, socket: Socket, text: String, messageId: String = "header-1") {
        val data = JSONObject().put("text", JSONObject().put("content", text))
            .put("senderStaffId", "001234").put("senderId", "$:encrypted==")
            .put("conversationType", "2").put("conversationId", "cidGroup==")
            .put("isInAtList", true).put("sessionWebhook", "https://oapi.dingtalk.com/robot/sendBySession?session=test")
        val event = JSONObject().put("type", "CALLBACK")
            .put("headers", JSONObject().put("topic", "/v1.0/im/bot/messages/get").put("messageId", messageId))
            .put("data", data.toString())
        val active = DingTalkStreamClient::class.java.getDeclaredField("running").apply { isAccessible = true }
        (active.get(client) as AtomicBoolean).set(true)
        DingTalkStreamClient::class.java.getDeclaredMethod("handleMessage", WebSocket::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(client, socket, event.toString())
    }

    @Test fun unlistedIdentityRequestIsAcknowledgedFirstAndNeverRoutedToSms() {
        val socket = Socket()
        val ids = mutableListOf<DingTalkWhitelistRequest>()
        val sms = mutableListOf<DingTalkRemoteCommand>()
        val client = DingTalkStreamClient("unused", "unused", onCommand = sms::add, onStatus = {}, onWhitelistRequest = {
            assertEquals(1, socket.sent.size) // Stream ACK was emitted before the reply callback.
            ids += it
        })
        replay(client, socket, "@机器人 申请用户白名单")
        assertTrue(sms.isEmpty())
        assertEquals("001234", ids.single().effectiveUserId)
        assertEquals("cidGroup==", ids.single().conversationId)
        assertEquals(200, JSONObject(socket.sent.single()).getInt("code"))
    }

    @Test fun normalSmsContainingRequestPhraseRemainsASmsCommand() {
        val socket = Socket()
        val sms = mutableListOf<DingTalkRemoteCommand>()
        val ids = mutableListOf<DingTalkWhitelistRequest>()
        val client = DingTalkStreamClient("unused", "unused", onCommand = sms::add, onStatus = {}, onWhitelistRequest = ids::add)
        replay(client, socket, "/发信 10086 申请用户白名单")
        assertTrue(ids.isEmpty())
        assertEquals(1, sms.size)
        assertEquals(1, socket.sent.size)
    }

    @Test fun groupAliasProducesGroupDiscovery() {
        val socket = Socket()
        val ids = mutableListOf<DingTalkWhitelistRequest>()
        val client = DingTalkStreamClient("unused", "unused", onCommand = { fail("SMS must not run") }, onStatus = {}, onWhitelistRequest = ids::add)
        replay(client, socket, "申请群主白名单")
        assertEquals(DingTalkWhitelistRequest.Kind.GROUP, ids.single().kind)
        assertTrue(ids.single().replyText().contains("不代表确认申请人是群主"))
    }
}
