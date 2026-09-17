package org.fossify.messages

import org.fossify.messages.forwarding.WebhookTemplateRenderer
import org.junit.Assert.assertEquals
import org.junit.Test

class WebhookTemplateRendererTest {
    @Test
    fun insertedTextIsNotExpandedAgain() {
        assertEquals(
            "[msg]|原文 [from] [time] [sim]|10086|20:30|SIM 1",
            WebhookTemplateRenderer.render(
                "[title]|[msg]|[from]|[time]|[sim]",
                mapOf(
                    "title" to "[msg]", "msg" to "原文 [from] [time] [sim]",
                    "from" to "10086", "time" to "20:30", "sim" to "SIM 1"
                )
            )
        )
    }

    @Test
    fun repeatedVariablesAndUnknownVariablesArePreservedCorrectly() {
        assertEquals(
            "value/value/[unknown]/[time]",
            WebhookTemplateRenderer.render("[msg]/[msg]/[unknown]/[time]", mapOf("msg" to "value"))
        )
    }

    @Test
    fun encodedValuesAreInsertedLiterally() {
        val encoded = "\\\"quoted\\\"\\n\\\\path ${'$'}1 [time]"
        assertEquals(
            "{\"content\":\"$encoded\"}",
            WebhookTemplateRenderer.render("{\"content\":\"[msg]\"}", mapOf("msg" to encoded))
        )
    }

    @Test
    fun receiverAndBracePlaceholdersAreReplacedCorrectly() {
        val template = "{\"receiver\":\"{receiver}\",\"double\":\"{{RECEIVER}}\",\"from\":\"{from}\",\"sim\":\"{sim}\",\"slot\":\"{sim_slot}\",\"body\":\"[msg]\"}"
        val rendered = WebhookTemplateRenderer.render(
            template,
            mapOf(
                "receiver" to "13800138000",
                "from" to "10086",
                "sim" to "SIM2 · 中国移动",
                "sim_slot" to "SIM2",
                "msg" to "验证码是 1234"
            )
        )
        assertEquals(
            "{\"receiver\":\"13800138000\",\"double\":\"13800138000\",\"from\":\"10086\",\"sim\":\"SIM2 · 中国移动\",\"slot\":\"SIM2\",\"body\":\"验证码是 1234\"}",
            rendered
        )
    }
}
