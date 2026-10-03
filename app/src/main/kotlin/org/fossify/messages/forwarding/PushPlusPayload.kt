package org.fossify.messages.forwarding

import org.json.JSONObject

/** SMS text is untrusted input; keep the existing HTML template without interpreting SMS markup. */
internal object PushPlusPayload {
    fun create(token: String, topic: String, title: String, body: String): JSONObject {
        require(token.isNotBlank()) { "PushPlus Token 不能为空" }
        val escaped = buildString(body.length) {
            body.forEach { char ->
                append(when (char) {
                    '&' -> "&amp;"
                    '<' -> "&lt;"
                    '>' -> "&gt;"
                    '"' -> "&quot;"
                    '\'' -> "&#39;"
                    '\n' -> "<br/>"
                    else -> char.toString()
                })
            }
        }
        return JSONObject()
            .put("token", token.trim())
            .put("title", title)
            .put("content", escaped)
            .put("template", "html")
            .also { if (topic.isNotBlank()) it.put("topic", topic.trim()) }
    }

    fun requireAccepted(response: JSONObject) {
        check(response.optInt("code", -1) == 200) { "PushPlus 拒绝发送（业务码 ${response.optInt("code", -1)}）" }
    }
}
