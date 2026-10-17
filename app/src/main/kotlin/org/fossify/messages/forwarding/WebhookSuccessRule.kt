package org.fossify.messages.forwarding

import org.json.JSONObject

/** Transport success and business acceptance are independent checks. */
internal data class WebhookSuccessRule(
    val mode: String = "http",
    val field: String = "",
    val expected: String = "",
) {
    fun validate() {
        require(mode in setOf("http", "contains", "json")) { "未知 Webhook 成功条件" }
        if (mode == "contains") require(expected.isNotEmpty()) { "请填写成功响应包含的内容" }
        if (mode == "json") require(field.isNotBlank() && field.split('.').all { it.isNotBlank() }) {
            "请填写 JSON 成功字段，如 code 或 data.success"
        }
    }

    fun requireAccepted(status: Int, response: String) {
        validate()
        if (status !in 200..299) throw WebhookRejectedException("Webhook HTTP $status")
        val accepted = when (mode) {
            "contains" -> response.contains(expected)
            "json" -> runCatching {
                var value: Any = JSONObject(response)
                for (key in field.split('.')) value = (value as JSONObject).get(key)
                value != JSONObject.NULL && value.toString() == expected
            }.getOrDefault(false)
            else -> true
        }
        if (!accepted) throw WebhookRejectedException("Webhook 响应未满足配置的成功条件")
    }
}

internal class WebhookRejectedException(message: String) : IllegalStateException(message)
