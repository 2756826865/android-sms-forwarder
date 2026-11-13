package org.fossify.messages.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextTest {
    @Test fun emptyBigTextFallsBackToSmsBody() {
        assertEquals("验证码 123456", extractNotificationText("  ", "验证码 123456", "短信"))
    }
    @Test fun expandedBodyTakesPriority() {
        assertEquals("完整短信内容", extractNotificationText("完整短信内容", "截断…", null))
    }
    @Test fun inboxStyleLinesAreNotDropped() {
        assertEquals("第一条\n第二条", extractNotificationText(null, null, "两条新消息", listOf("第一条", "第二条")))
    }
    @Test fun messagingStyleProvidesBody() {
        assertEquals("消息正文", extractNotificationText(null, "", null, messages = listOf("消息正文")))
    }
    @Test fun subTextIsLastFallback() {
        assertEquals("子文本", extractNotificationText(null, " ", "子文本"))
    }
    @Test fun emptyNotificationDoesNotInventContent() {
        assertEquals("", extractNotificationText(null, null, null))
    }
    @Test fun invisibleOnlyNotificationTreatedAsEmpty() {
        assertEquals("", extractNotificationText("\u200B\u3000\u00A0\uFEFF", " \u200D ", "   "))
    }
    @Test fun stripInvisiblePreservesInteriorSpaces() {
        assertEquals("验证码\u3000123456\u00A0有效", "\u200B  验证码\u3000123456\u00A0有效\u3000 ".stripInvisibleAndTrim())
    }
}
