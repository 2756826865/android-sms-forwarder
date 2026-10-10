package org.fossify.messages.forwarding

import org.junit.Assert.*
import org.junit.Test

class WorkPayloadTextTest {
    @Test fun preservesShortText() {
        assertEquals("验证码 1234", WorkPayloadText.fitUtf8("验证码 1234", 6000))
    }

    @Test fun longChineseFitsByteBudget() {
        val result = WorkPayloadText.fitUtf8("汉".repeat(4000), 6000)
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 6000)
        assertTrue(result.endsWith("…(内容过长已截断)"))
    }

    @Test fun supplementaryCharactersAreNotSplit() {
        val result = WorkPayloadText.fitUtf8("😀".repeat(4000), 6000)
        val prefix = result.substringBefore("…(内容过长已截断)")
        assertEquals(0, prefix.length % 2)
        assertFalse(prefix.endsWith("\uD83D"))
        assertTrue(result.toByteArray(Charsets.UTF_8).size <= 6000)
    }
}
