package org.fossify.messages.remote

import org.json.JSONObject

internal object FeishuBotIdentity {
    fun extractOpenId(responseBody: String): String {
        val json = JSONObject(responseBody)
        check(json.optInt("code", -1) == 0) {
            "飞书机器人信息接口返回错误码 ${json.optInt("code", -1)}"
        }
        val bot = json.optJSONObject("bot")
            ?: json.optJSONObject("data")?.optJSONObject("bot")
        return bot?.optString("open_id").orEmpty().also {
            check(it.isNotBlank()) { "飞书未返回机器人 open_id" }
        }
    }

    fun isBotMentioned(botOpenId: String?, mentionedOpenIds: Iterable<String>): Boolean {
        val expected = botOpenId?.trim().orEmpty()
        return expected.isNotEmpty() && mentionedOpenIds.any { it == expected }
    }
}
