package org.fossify.messages.autofill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerificationCodeExtractorTest {

    @Test
    fun testSignatureNumberExclusion() {
        // 关键防御：签名是 10086、12306 等特服号时，绝不能误判为验证码
        assertEquals(
            "654321",
            VerificationCodeExtractor.extractCode("【10086】尊敬的客户，您本次办理业务的验证码为 654321，请于5分钟内完成验证。")
        )

        assertEquals(
            "8989",
            VerificationCodeExtractor.extractCode("【12306】您的购票登录动态口令是 8989，请勿向任何人泄露。")
        )

        assertEquals(
            "998877",
            VerificationCodeExtractor.extractCode("【95588】您申请网银转账，动态密码是[998877]，切勿告知他人。")
        )
    }

    @Test
    fun testCardTailAndYearExclusion() {
        // 排除卡尾号 8888 和年份 2026
        assertEquals(
            "345678",
            VerificationCodeExtractor.extractCode("【招商银行】您尾号8888的账户于2026年申请消费分期，授权码为 345678。")
        )
    }

    @Test
    fun testCommonSmsTemplates() {
        // 京东验证码
        assertEquals(
            "123456",
            VerificationCodeExtractor.extractCode("【京东】123456(登录验证码)，请于20分钟内填写，如非本人操作请忽略。")
        )

        // 微信/支付宝/通用
        assertEquals(
            "7890",
            VerificationCodeExtractor.extractCode("〔微信支付〕确认码：7890，如非本人操作请联系客服。")
        )

        // 英文短信
        assertEquals(
            "4321",
            VerificationCodeExtractor.extractCode("Your Google verification code is 4321. Don't share it.")
        )

        // 中间中括号包裹
        assertEquals(
            "567890",
            VerificationCodeExtractor.extractCode("您的验证码是【567890】，有效期10分钟。")
        )
    }

    @Test
    fun testNonVerificationSms() {
        // 普通短信不应误提取
        assertNull(VerificationCodeExtractor.extractCode("今天晚上7点在老地方聚餐，别迟到哦！"))
        assertNull(VerificationCodeExtractor.extractCode("【顺丰速运】您的快递已到达丰巢快递柜，请凭提货码取件。"))
        assertNull(VerificationCodeExtractor.extractCode(""))
    }
}
