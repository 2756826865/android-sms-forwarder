package org.fossify.messages.forwarding

import org.json.JSONObject
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

internal object QmsgProtocol {
    private const val MAX_MESSAGE_LENGTH = 1800

    fun endpoint(keyOrUrl: String): String {
        val value = keyOrUrl.trim()
        require(value.isNotBlank()) { "Qmsg Key 不能为空" }
        if (value.startsWith("http://") || value.startsWith("https://")) return value
        val encodedKey = URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")
        return "https://qmsg.zendee.cn/v3/send/$encodedKey"
    }

    fun formBody(message: String): String {
        require(message.isNotBlank()) { "Qmsg 消息不能为空" }
        require(message.length <= MAX_MESSAGE_LENGTH) { "Qmsg 消息不能超过 $MAX_MESSAGE_LENGTH 个字符" }
        return "msg=${URLEncoder.encode(message, StandardCharsets.UTF_8.name())}"
    }

    fun requireAccepted(response: JSONObject): Long? {
        check(response.optBoolean("success", false)) {
            response.optString("message").ifBlank { "Qmsg 请求被拒绝" }
        }
        check(response.optInt("code", -1) == 0) {
            response.optString("message").ifBlank { "Qmsg 返回了异常业务状态" }
        }
        return when (val data = response.opt("data")) {
            is Number -> data.toLong()
            is String -> data.toLongOrNull()
            else -> null
        }
    }
}
