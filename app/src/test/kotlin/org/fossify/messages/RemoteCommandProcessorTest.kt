package org.fossify.messages

import org.fossify.messages.messaging.SubscriptionResolver
import org.fossify.messages.remote.RemoteSmsCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteCommandProcessorTest {

    @Test
    fun testCommandPrefixExactStartsWithMatching() {
        // 1. 标准默认前缀匹配
        val cmd1 = RemoteSmsCommand.parse("/发信 13800138000 你好世界")
        assertNotNull(cmd1)
        assertEquals("13800138000", cmd1?.targetNumber)
        assertEquals("你好世界", cmd1?.content)

        val cmd2 = RemoteSmsCommand.parse("/发短信 10086 查话费")
        assertNotNull(cmd2)
        assertEquals("10086", cmd2?.targetNumber)
        assertEquals("查话费", cmd2?.content)

        val cmd3 = RemoteSmsCommand.parse("#发信 10010 101")
        assertNotNull(cmd3)
        assertEquals("10010", cmd3?.targetNumber)
        assertEquals("101", cmd3?.content)

        // 2. 自定义前缀优先
        val cmdCustom = RemoteSmsCommand.parse("/sms 13900001111 报警测试", customPrefix = "/sms")
        assertNotNull(cmdCustom)
        assertEquals("13900001111", cmdCustom?.targetNumber)
        assertEquals("报警测试", cmdCustom?.content)

        // 3. 严禁字符串中间命中（前缀逃逸防范测试）
        // 曾经出现的漏洞：若用户普通短信内容为“请不要使用/发信 13800138000 诈骗”，不能被当作发信指令！
        val escapeCmd1 = RemoteSmsCommand.parse("收到请不要使用/发信 13800138000 诈骗")
        assertNull("正文中间包含前缀必须返回 null", escapeCmd1)

        val escapeCmd2 = RemoteSmsCommand.parse("abc/发信 13800138000 测试")
        assertNull("前缀未位于行首必须返回 null", escapeCmd2)

        val escapeCmd3 = RemoteSmsCommand.parse("特别提醒：#发短信 10086 查话费")
        assertNull("行首非前缀必须返回 null", escapeCmd3)
    }

    @Test
    fun testSimSlotTokenParsing() {
        // SIM1 指定
        val cmdSim1 = RemoteSmsCommand.parse("/发信 SIM1 13800138000 验证码")
        assertNotNull(cmdSim1)
        assertEquals(SubscriptionResolver.MODE_SIM1, cmdSim1?.sendMode)
        assertEquals("13800138000", cmdSim1?.targetNumber)
        assertEquals("验证码", cmdSim1?.content)

        // SIM2 指定 (小写兼容)
        val cmdSim2 = RemoteSmsCommand.parse("/发信 sim2 13800138000 验证码")
        assertNotNull(cmdSim2)
        assertEquals(SubscriptionResolver.MODE_SIM2, cmdSim2?.sendMode)

        // 默认卡
        val cmdDefault = RemoteSmsCommand.parse("/发信 默认 13800138000 验证码")
        assertNotNull(cmdDefault)
        assertEquals(SubscriptionResolver.MODE_DEFAULT, cmdDefault?.sendMode)

        // 系统默认卡
        val cmdSysDefault = RemoteSmsCommand.parse("/发信 系统默认 13800138000 验证码")
        assertNotNull(cmdSysDefault)
        assertEquals(SubscriptionResolver.MODE_DEFAULT, cmdSysDefault?.sendMode)

        // 未显式指定卡槽：默认为跟随接收卡
        val cmdFollow = RemoteSmsCommand.parse("/发信 13800138000 验证码")
        assertNotNull(cmdFollow)
        assertEquals(SubscriptionResolver.MODE_FOLLOW_RECEIVE, cmdFollow?.sendMode)
    }

    @Test
    fun testEffectiveSendModeFallback() {
        // 显式指定 SIM1 时，不受通道默认卡影响
        val cmdExplicit = RemoteSmsCommand("10086", "101", sendMode = SubscriptionResolver.MODE_SIM1)
        assertEquals(SubscriptionResolver.MODE_SIM1, cmdExplicit.effectiveSendMode(SubscriptionResolver.MODE_SIM2))

        // 未显式指定卡（MODE_FOLLOW_RECEIVE）时，降级使用通道默认配置卡
        val cmdUnspecified = RemoteSmsCommand("10086", "101", sendMode = SubscriptionResolver.MODE_FOLLOW_RECEIVE)
        assertEquals(SubscriptionResolver.MODE_SIM2, cmdUnspecified.effectiveSendMode(SubscriptionResolver.MODE_SIM2))
        assertEquals(SubscriptionResolver.MODE_DEFAULT, cmdUnspecified.effectiveSendMode(SubscriptionResolver.MODE_DEFAULT))
    }

    @Test
    fun testMalformedCommands() {
        // 缺少号码和内容
        assertNull(RemoteSmsCommand.parse("/发信"))
        // 缺少内容
        assertNull(RemoteSmsCommand.parse("/发信 13800138000"))
        // 空白内容
        assertNull(RemoteSmsCommand.parse("/发信 13800138000   "))
        // 仅有 SIM 标识
        assertNull(RemoteSmsCommand.parse("/发信 SIM1"))
        assertNull(RemoteSmsCommand.parse("/发信 SIM1 13800138000"))
    }
}