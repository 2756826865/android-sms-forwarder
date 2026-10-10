package org.fossify.messages.remote

import com.lark.oapi.service.im.v1.model.*
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BotWhitelistRoutingTest {
    @Test fun wecomDiscoveryUsesRealCallbackUserAndChatIdsWithoutSms() {
        val ids = mutableListOf<BotWhitelistRequest>()
        val client = WeComStreamClient("unused", "unused", onCommand = { fail("SMS path must not run") },
            onStatus = {}, onWhitelistRequest = ids::add)
        val headers = JSONObject().put("req_id", "wecom-request")
        val body = JSONObject().put("msgtype", "text").put("msgid", "wecom-message")
            .put("from", JSONObject().put("userid", "member-001"))
            .put("chattype", "group").put("chatid", "wrGroupID")
            .put("text", JSONObject().put("content", "@智能机器人 申请群白名单"))
        WeComStreamClient::class.java.getDeclaredMethod("handleMessageCallback", JSONObject::class.java, JSONObject::class.java)
            .apply { isAccessible = true }.invoke(client, headers, body)
        val request = ids.single()
        assertEquals("member-001", request.userId)
        assertEquals("from.userid", request.userField)
        assertEquals("wrGroupID", request.groupId)
        assertEquals("wecom-request", request.replyTarget)
        assertTrue(request.replyText().contains("尚未加入白名单"))
    }

    @Test fun feishuDiscoveryPrefersOpenIdAndUsesMetadataForBotMention() {
        val ids = mutableListOf<BotWhitelistRequest>()
        val client = FeishuStreamClient("unused", "unused", onCommand = { fail("SMS path must not run") },
            onStatus = {}, onWhitelistRequest = ids::add)
        val running = FeishuStreamClient::class.java.getDeclaredField("running").apply { isAccessible = true }
        (running.get(client) as AtomicBoolean).set(true)
        FeishuStreamClient::class.java.getDeclaredField("botOpenId").apply { isAccessible = true }.set(client, "ouBot")
        val user = UserId().apply { openId = "ouMember"; userId = "wrongAlternative" }
        val mention = MentionEvent().apply { key = "@_user_1"; id = UserId().apply { openId = "ouBot" } }
        val message = EventMessage().apply {
            messageId = "omMessage"; messageType = "text"; chatType = "group"; chatId = "ocGroup"
            content = JSONObject().put("text", "@_user_1 申请用户白名单").toString()
            mentions = arrayOf(mention)
        }
        val data = P2MessageReceiveV1Data().apply { sender = EventSender().apply { senderId = user }; this.message = message }
        val event = P2MessageReceiveV1().apply { this.event = data }
        FeishuStreamClient::class.java.getDeclaredMethod("handleMessage", P2MessageReceiveV1::class.java)
            .apply { isAccessible = true }.invoke(client, event)
        val request = ids.single()
        assertEquals("ouMember", request.userId)
        assertEquals("open_id", request.userField)
        assertEquals("ocGroup", request.groupId)
        assertEquals("chat_id:ocGroup", request.replyTarget)
        assertTrue(request.isMentioned)
    }

    @Test fun telegramNegativeChatIdAndNumericUserIdRemainExact() {
        val request = BotWhitelistRequest("tg-message", DingTalkWhitelistRequest.Kind.GROUP, "Telegram",
            "123456789", "user_id", "-1001234567890", "chat_id", true, true, "-1001234567890")
        assertTrue(request.replyText().contains("user_id）：\n123456789\n"))
        assertTrue(request.replyText().contains("chat_id）：\n-1001234567890\n"))
        assertTrue(BotWhitelistReplyLimiter().claim("tg-source", request, 1000))
    }

    @Test fun unmentionedGroupsAndMissingReplyTargetsCannotReply() {
        val base = BotWhitelistRequest("id", DingTalkWhitelistRequest.Kind.USER, "飞书",
            "ouUser", "open_id", "ocGroup", "chat_id", true, false, "chat_id:ocGroup")
        val limiter = BotWhitelistReplyLimiter()
        assertFalse(limiter.claim("source", base, 1000))
        assertFalse(limiter.claim("source", base.copy(isMentioned = true, replyTarget = ""), 1000))
        assertTrue(limiter.claim("source", base.copy(isMentioned = true), 1000))
    }
}
