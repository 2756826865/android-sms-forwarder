package org.fossify.messages.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class FeishuMessageTextTest {
    @Test fun officialKeyAlreadyContainsAtSign() {
        assertEquals("/短信发送 10086 测试", FeishuMessageText.stripLeadingMentions(
            "@_user_1 /短信发送 10086 测试", listOf("@_user_1")))
    }

    @Test fun adjacentPrefixAndFullWidthSpacesAreSupported() {
        assertEquals("/短信发送 10086 测试", FeishuMessageText.stripLeadingMentions(
            "　@_user_1/短信发送 10086 测试", listOf("@_user_1")))
    }

    @Test fun mentionsInsideSmsBodyArePreserved() {
        assertEquals("/短信发送 10086 联系 @_user_1 @小明", FeishuMessageText.stripLeadingMentions(
            "@_user_1 /短信发送 10086 联系 @_user_1 @小明", listOf("@_user_1")))
    }

    @Test fun undeclaredMentionCannotTurnIntoCommand() {
        val text = "@其他人 /短信发送 10086 测试"
        assertEquals(text, FeishuMessageText.stripLeadingMentions(text, emptyList()))
    }

    @Test fun mentionPrefixDoesNotEatLongerId() {
        val text = "@_user_10 /短信发送 10086 测试"
        assertEquals(text, FeishuMessageText.stripLeadingMentions(text, listOf("@_user_1")))
    }

    @Test fun multipleLeadingMentionsAreRemoved() {
        assertEquals("/短信发送 10086 测试", FeishuMessageText.stripLeadingMentions(
            "@_user_1 @_user_2 /短信发送 10086 测试", listOf("_user_1", "@_user_2")))
    }
}
