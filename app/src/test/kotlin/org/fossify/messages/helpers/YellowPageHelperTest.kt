package org.fossify.messages.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YellowPageHelperTest {

    @Test
    fun testExtractSignature() {
        // 开头签名提取
        assertEquals("京东", YellowPageHelper.extractSignature("【京东】您的验证码是 123456，请勿泄露给他人。"))
        assertEquals("中国移动", YellowPageHelper.extractSignature("【中国移动】尊敬的客户，您的话费余额不足。"))
        assertEquals("招商银行", YellowPageHelper.extractSignature("[招商银行] 您尾号8888的账户转入人民币100.00元。"))
        assertEquals("微信支付", YellowPageHelper.extractSignature("〔微信支付〕你已成功扣款10.00元"))

        // 结尾签名提取
        assertEquals("菜鸟驿站", YellowPageHelper.extractSignature("凭提货码取件【菜鸟驿站】"))

        // 空串或无签名
        assertNull(YellowPageHelper.extractSignature(null))
        assertNull(YellowPageHelper.extractSignature(""))
        assertNull(YellowPageHelper.extractSignature("今天天气真好，出去散步吧"))
    }

    @Test
    fun testNormalizeNumber() {
        assertEquals("10086", YellowPageHelper.normalizeNumber("10086"))
        assertEquals("10086", YellowPageHelper.normalizeNumber("+86 10086"))
        assertEquals("10086", YellowPageHelper.normalizeNumber("+8610086"))
        assertEquals("10086", YellowPageHelper.normalizeNumber("008610086"))
        assertEquals("95588", YellowPageHelper.normalizeNumber(" 955-88 "))
    }

    @Test
    fun testIsCommercialOrSpecialAddress() {
        // 106 商业短信通道长号 -> 允许签名识别
        assertTrue(YellowPageHelper.isCommercialOrSpecialAddress("10690000123456"))
        assertTrue(YellowPageHelper.isCommercialOrSpecialAddress("+8610690888"))

        // 特服号 -> 允许识别
        assertTrue(YellowPageHelper.isCommercialOrSpecialAddress("10086"))
        assertTrue(YellowPageHelper.isCommercialOrSpecialAddress("95588"))
        assertTrue(YellowPageHelper.isCommercialOrSpecialAddress("12345"))
        assertTrue(YellowPageHelper.isCommercialOrSpecialAddress("4008888888"))

        // 常见短号接入码 -> 允许识别
        assertTrue(YellowPageHelper.isCommercialOrSpecialAddress("95555"))
        assertTrue(YellowPageHelper.isCommercialOrSpecialAddress("12110"))

        // 普通 11 位私人手机号码 -> 坚决不允许正文签名识别，严防私人联系人被篡改
        assertFalse(YellowPageHelper.isCommercialOrSpecialAddress("13812345678"))
        assertFalse(YellowPageHelper.isCommercialOrSpecialAddress("+8615988886666"))
        assertFalse(YellowPageHelper.isCommercialOrSpecialAddress("18611112222"))
        assertFalse(YellowPageHelper.isCommercialOrSpecialAddress("19900001111"))
    }
}
