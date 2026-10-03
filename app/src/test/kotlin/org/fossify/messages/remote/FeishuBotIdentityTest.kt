package org.fossify.messages.remote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FeishuBotIdentityTest {
    @Test fun extractsLegacyTopLevelBotOpenId() {
        assertEquals("ou_bot", FeishuBotIdentity.extractOpenId(
            """{"code":0,"msg":"success","bot":{"open_id":"ou_bot"}}"""))
    }

    @Test fun acceptsDataWrappedBotOpenId() {
        assertEquals("ou_bot", FeishuBotIdentity.extractOpenId(
            """{"code":0,"data":{"bot":{"open_id":"ou_bot"}}}"""))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsErrorResponse() {
        FeishuBotIdentity.extractOpenId("""{"code":1001,"msg":"denied"}""")
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsMissingOpenId() {
        FeishuBotIdentity.extractOpenId("""{"code":0,"bot":{}}""")
    }

    @Test fun onlyTheCurrentBotCountsAsMentioned() {
        assertTrue(FeishuBotIdentity.isBotMentioned("ou_bot", listOf("ou_other", "ou_bot")))
        assertFalse(FeishuBotIdentity.isBotMentioned("ou_bot", listOf("ou_other")))
        assertFalse(FeishuBotIdentity.isBotMentioned(null, listOf("ou_bot")))
    }
}
