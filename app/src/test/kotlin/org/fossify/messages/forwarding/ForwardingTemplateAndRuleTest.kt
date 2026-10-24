package org.fossify.messages.forwarding

import android.content.Context
import android.content.ContextWrapper
import org.fossify.messages.autofill.VerificationCodeExtractor
import org.fossify.messages.rule.model.IncomingMessageContext
import org.fossify.messages.rule.template.TemplateRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForwardingTemplateAndRuleTest {

    private fun createDummyContext(): Context {
        return object : ContextWrapper(null) {}
    }

    @Test
    fun testRuleTemplateSupportsLowercaseAndChinesePlaceholders() {
        val template = "来自: {{发件人}} ({{from}}), 内容: {{短信内容}} ({{body}}), 验证码: {{验证码}} ({{code}}), 卡槽: {{卡槽}} ({{sim}}), 序号: {{卡槽序号}} ({{sim_slot}}), 接收: {{接收号码}} ({{receiver}})"
        val simSnapshot = ForwardingSimSnapshot(
            slotIndex = 0,
            description = "SIM 1 · 中国电信",
            receiverNumber = "13912345678",
            customLabel = "电信卡"
        )
        val rendered = ForwardingMessageFormatter.renderRuleTemplate(
            context = createDummyContext(),
            template = template,
            sender = "10086",
            body = "【中国移动】您的验证码是 987654，请勿泄露。",
            receivedAt = 1600000000000L,
            subscriptionId = 1,
            simSnapshot = simSnapshot
        )

        val expected = "来自: 10086 (10086), 内容: 【中国移动】您的验证码是 987654，请勿泄露。 (【中国移动】您的验证码是 987654，请勿泄露。), 验证码: 987654 (987654), 卡槽: SIM1 · 电信卡 (SIM1 · 电信卡), 序号: 1 (SIM1 · 电信卡), 接收: 13912345678 (13912345678)"
        assertEquals(expected, rendered)
    }

    @Test
    fun testTemplateRendererExtractsAccurateCode() {
        val template = "提取到的验证码: {{CODE}}"
        val body = "【招商银行】您正在登录，动态验证码为 456789，10分钟内有效。"
        val ctx = IncomingMessageContext(sender = "95555", body = body, subscriptionId = 1)
        val rendered = TemplateRenderer.render(template, ctx)
        assertEquals("提取到的验证码: 456789", rendered)
    }

    @Test
    fun testExtractorDoesNotMistakeSenderForCode() {
        val bodyWithoutCode = "【建设银行】尊敬的客户，您的尾号8888账户于10月24日入账1000.00元。"
        assertNull(VerificationCodeExtractor.extractCode(bodyWithoutCode))
    }

    @Test
    fun testTemplateRendererHandlesMissingCodeSafely() {
        val template = "验证码: [{{CODE}}]"
        val body = "普通的通知短信，没有任何数字密码。"
        val ctx = IncomingMessageContext(sender = "10010", body = body, subscriptionId = 2)
        val rendered = TemplateRenderer.render(template, ctx)
        assertEquals("验证码: []", rendered)
    }
}
