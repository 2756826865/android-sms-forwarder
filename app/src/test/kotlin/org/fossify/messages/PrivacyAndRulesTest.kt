package org.fossify.messages

import org.fossify.messages.forwarding.ForwardingRule
import org.fossify.messages.forwarding.ForwardingRuleEngine
import org.fossify.messages.forwarding.PrivacyDataMasker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyAndRulesTest {

    @Test
    fun testPrivacyDataMasker() {
        val raw = "尊敬的客户，您的尾号为6222021234567890的银行卡于12:00转账500元，身份证110101199003072345，电话13800138000，验证码951332"
        val masked = PrivacyDataMasker.mask(
            content = raw,
            maskPhone = true,
            maskIdCard = true,
            maskBankCard = true,
            maskVerificationCode = true,
            verificationCode = "951332"
        )

        assertTrue(masked.contains("138****8000"))
        assertTrue(masked.contains("110101********2345"))
        assertTrue(masked.contains("6222 **** **** 7890"))
        assertTrue(masked.contains("[******]"))
        assertFalse(masked.contains("13800138000"))
        assertFalse(masked.contains("110101199003072345"))
        assertFalse(masked.contains("6222021234567890"))
        assertFalse(masked.contains("951332"))
    }

    @Test
    fun testForwardingRuleTimeWindow() {
        val alwaysRule = ForwardingRule(
            name = "AllDayRule",
            timeWindowEnabled = false,
            includeKeywords = listOf("验证码")
        )
        val engine = ForwardingRuleEngine(listOf(alwaysRule))
        val decision = engine.evaluate(
            sender = "10086",
            body = "您的验证码是 1234",
            subscriptionId = 0,
            channelCandidates = setOf("telegram", "dingtalk")
        )
        assertTrue(decision.isAllowed("telegram"))
    }
}
