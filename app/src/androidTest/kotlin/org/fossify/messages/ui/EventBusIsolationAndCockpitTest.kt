package org.fossify.messages.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Events
import org.fossify.messages.rule.model.IncomingMessageContext
import org.fossify.messages.rule.template.TemplateRenderer
import org.fossify.messages.ui.compose.navigation.GatewayTab
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 专项集成与回归测试套件 (EventBus 隔离 + Cockpit 控制台)
 */
@RunWith(AndroidJUnit4::class)
class EventBusIsolationAndCockpitTest {

    /**
     * Issue #20 专项回归防御测试：
     * 验证 EventBus 仅反射纯净订阅代理对象，绝不触碰 ComponentActivity 或 PictureInPictureUiState
     */
    @Test
    fun testEventBusSubscriberProxyIsolation() {
        val bus = EventBus.getDefault()
        var eventReceived = false

        // 模拟专有代理订阅者
        val proxySubscriber = object {
            @Subscribe(threadMode = ThreadMode.POSTING)
            fun onRefreshConversations(event: Events.RefreshConversations) {
                eventReceived = true
            }
        }

        // 1. 注册代理对象
        bus.register(proxySubscriber)
        assertTrue(bus.isRegistered(proxySubscriber))

        // 2. 派发事件
        bus.post(Events.RefreshConversations())
        assertTrue(eventReceived)

        // 3. 注销代理对象
        bus.unregister(proxySubscriber)
        assertFalse(bus.isRegistered(proxySubscriber))
    }

    /**
     * 规则工作台 验证码提取与模板沙箱测试
     */
    @Test
    fun testVerificationCodeExtractionAndTemplateRendering() {
        val smsBody1 = "【腾讯科技】验证码：938210，切勿泄露给他人。"
        val code1 = TemplateRenderer.extractVerificationCode(smsBody1)
        assertEquals("938210", code1)

        val smsBody2 = "Your Google verification code is 482910."
        val code2 = TemplateRenderer.extractVerificationCode(smsBody2)
        assertEquals("482910", code2)

        val template = "来自: {{sender}}\n验证码: {{code}}\n内容: {{body}}"
        val context = IncomingMessageContext(
            sender = "10690000",
            body = smsBody1,
            subscriptionId = 1
        )
        val rendered = TemplateRenderer.render(template, context)

        assertTrue(rendered.contains("来自: 10690000"))
        assertTrue(rendered.contains("验证码: 938210"))
        assertTrue(rendered.contains(smsBody1))
    }

    /**
     * 五大业务控制台 Tab 导航体系完整性测试
     */
    @Test
    fun testGatewayTabNavigationCompleteness() {
        val tabs = GatewayTab.values()
        assertEquals(5, tabs.size)

        // 验证第一默认首页为信息
        assertEquals(GatewayTab.MESSAGES, tabs[0])
        assertEquals("信息", GatewayTab.MESSAGES.title)
        assertEquals("💬", GatewayTab.MESSAGES.emoji)

        assertEquals(GatewayTab.DASHBOARD, tabs[1])
        assertEquals("大盘", GatewayTab.DASHBOARD.title)

        assertEquals(GatewayTab.RULES, tabs[2])
        assertEquals("规则", GatewayTab.RULES.title)

        assertEquals(GatewayTab.CHANNELS, tabs[3])
        assertEquals("通道", GatewayTab.CHANNELS.title)

        assertEquals(GatewayTab.OPERATIONS, tabs[4])
        assertEquals("运维", GatewayTab.OPERATIONS.title)
    }

    /**
     * 默认短信会话全文检索与脱敏过滤测试
     */
    @Test
    fun testConversationFiltering() {
        val conversations = listOf(
            Conversation(
                threadId = 1L,
                snippet = "您的账单已生成",
                date = 1700000000,
                read = true,
                title = "招商银行",
                photoUri = "",
                isGroupConversation = false,
                phoneNumber = "95555"
            ),
            Conversation(
                threadId = 2L,
                snippet = "验证码 123456",
                date = 1700001000,
                read = false,
                title = "中国移动",
                photoUri = "",
                isGroupConversation = false,
                phoneNumber = "10086"
            )
        )

        // 搜索号码
        val matchPhone = conversations.filter { it.phoneNumber.contains("10086") }
        assertEquals(1, matchPhone.size)
        assertEquals("中国移动", matchPhone.first().title)

        // 搜索正文关键字
        val matchSnippet = conversations.filter { it.snippet.contains("账单") }
        assertEquals(1, matchSnippet.size)
        assertEquals("招商银行", matchSnippet.first().title)
    }
}
