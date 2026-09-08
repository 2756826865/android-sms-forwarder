package org.fossify.messages

import org.fossify.messages.forwarding.WebhookRequestUrl
import org.junit.Assert.assertEquals
import org.junit.Test

class WebhookRequestUrlTest {
    @Test
    fun addsParametersBeforeFragment() {
        assertEquals("https://example.invalid/send?msg=hello#section?x=1",
            WebhookRequestUrl.appendQuery("https://example.invalid/send#section?x=1", "msg=hello"))
        assertEquals("https://example.invalid/send?token=abc&msg=hello#section",
            WebhookRequestUrl.appendQuery("https://example.invalid/send?token=abc#section", "msg=hello"))
    }

    @Test
    fun preservesEncodingAndExistingSeparators() {
        val query = "msg=%E4%BD%A0+%26+%23"
        assertEquals("https://example.invalid/?$query",
            WebhookRequestUrl.appendQuery("https://example.invalid/", "?$query"))
        assertEquals("https://example.invalid/?$query",
            WebhookRequestUrl.appendQuery("https://example.invalid/?", query))
        assertEquals("https://example.invalid/?x=1&$query#section",
            WebhookRequestUrl.appendQuery("https://example.invalid/?x=1&#section", "&$query"))
    }

    @Test
    fun emptyParametersDoNotChangeUrl() {
        val url = "https://example.invalid/?x=1#section"
        listOf("", "?", "&", " ").forEach {
            assertEquals(url, WebhookRequestUrl.appendQuery(url, it))
        }
    }
}
