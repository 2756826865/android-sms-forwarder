package org.fossify.messages.remote

import org.junit.Assert.*
import org.junit.Test

class DingTalkWhitelistRequestTest {
    private fun request(
        id: String = "message-1", staff: String = "001234", sender: String = "$:abc==",
        group: Boolean = true, mention: Boolean = true,
        kind: DingTalkWhitelistRequest.Kind = DingTalkWhitelistRequest.Kind.USER,
    ) = DingTalkWhitelistRequest(id, kind, staff, sender, "cidAbc==", group, mention, "https://oapi.dingtalk.com/robot/sendBySession?session=test")

    @Test fun exactRequestsAcceptLeadingMentionAndAliases() {
        assertEquals(DingTalkWhitelistRequest.Kind.USER, DingTalkWhitelistRequest.parseKind("@机器人 申请用户白名单"))
        assertEquals(DingTalkWhitelistRequest.Kind.GROUP, DingTalkWhitelistRequest.parseKind("申请群白名单"))
        assertEquals(DingTalkWhitelistRequest.Kind.GROUP, DingTalkWhitelistRequest.parseKind("/申请群主白名单"))
        assertEquals(DingTalkWhitelistRequest.Kind.USER, DingTalkWhitelistRequest.parseKind("  /白名单\n"))
    }

    @Test fun ordinaryTextAndSmsBodyNeverBecomeAnIdentityRequest() {
        assertNull(DingTalkWhitelistRequest.parseKind("请问怎样申请用户白名单"))
        assertNull(DingTalkWhitelistRequest.parseKind("/发信 10086 申请用户白名单"))
        assertNull(DingTalkWhitelistRequest.parseKind("申请群白名单 删除所有用户"))
        assertNull(DingTalkWhitelistRequest.parseKind(" ".repeat(513) + "申请用户白名单"))
    }

    @Test fun replyPreservesPreferredIdAndGroupIdExactly() {
        val text = request().replyText()
        assertTrue(text.contains("senderStaffId）：\n001234\n"))
        assertTrue(text.contains("conversationId）：\ncidAbc==\n"))
        assertFalse(text.contains("$:abc=="))
        assertTrue(text.contains("尚未加入白名单"))
        assertTrue(text.contains("未发送短信"))
    }

    @Test fun senderFallbackAndMissingIdsDoNotInventIdentity() {
        assertTrue(request(staff = "").replyText().contains("senderId）：\n$:abc==\n"))
        val missing = request(staff = "", sender = "").replyText()
        assertTrue(missing.contains("未提供用户 ID"))
        assertFalse(missing.contains("用户白名单 ID（"))
    }

    @Test fun privateChatGroupRequestExplainsHowToGetAGroupId() {
        val text = request(group = false, kind = DingTalkWhitelistRequest.Kind.GROUP).replyText()
        assertTrue(text.contains("当前是私聊"))
        assertFalse(text.contains("conversationId）："))
    }

    @Test fun groupDiscoveryRequiresMentionButPrivateChatDoesNot() {
        val limiter = DingTalkWhitelistReplyLimiter()
        assertFalse(limiter.claim("source", request(mention = false), 1000))
        assertTrue(limiter.claim("source", request(group = false, mention = false), 1000))
        assertFalse(limiter.claim("source", request(id = ""), 1000))
    }

    @Test fun duplicateAndBurstRequestsAreSuppressedWithoutCrossSourceBlocking() {
        val limiter = DingTalkWhitelistReplyLimiter()
        assertTrue(limiter.claim("a", request(), 1000))
        assertFalse(limiter.claim("a", request(id = "message-2"), 2000))
        assertFalse(limiter.claim("a", request(), 32_000))
        assertTrue(limiter.claim("a", request(id = "message-2"), 32_000))
        assertTrue(limiter.claim("b", request(), 32_000))
        assertTrue(limiter.claim("a", request(id = "message-3", staff = "other"), 32_000))
    }

    @Test fun expiredCacheAndClockChangesDoNotBlockDiscoveryForever() {
        val limiter = DingTalkWhitelistReplyLimiter()
        assertTrue(limiter.claim("source", request(), 500_000))
        assertTrue(limiter.claim("source", request(), 1000))
        assertTrue(limiter.claim("source", request(), 302_000))
    }
}
