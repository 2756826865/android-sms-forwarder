package org.fossify.messages.forwarding

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/** Shared by instance testing and real forwarding so the same receipt rules apply. */
internal object WxPusherSender {
    fun send(appToken: String, targetId: String, title: String, body: String) {
        val token = appToken.trim()
        val target = targetId.trim()
        require(token.isNotEmpty() && target.isNotEmpty()) { "WxPusher AppToken 和 UID/Topic ID 不能为空" }
        val recipients = JSONObject()
        when {
            target.startsWith("UID_") -> recipients.put("uids", JSONArray().put(target))
            target.toLongOrNull()?.let { it > 0 } == true ->
                recipients.put("topicIds", JSONArray().put(target.toLong()))
            else -> error("WxPusher 目标须为 UID_ 开头的 UID 或数字 Topic ID")
        }
        val content = "【$title】\n$body"
        require(content.length <= 40_000 && content.toByteArray(StandardCharsets.UTF_8).size <= 65_535) {
            "WxPusher 内容超过服务端长度限制"
        }
        val payload = recipients.put("appToken", token)
            .put("content", content)
            .put("summary", title.take(100))
            .put("contentType", 1)
        val connection = URL("https://wxpusher.zjiecode.com/api/send/message")
            .openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 10_000
            connection.readTimeout = 12_000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(payload.toString().toByteArray(StandardCharsets.UTF_8)) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "WxPusher HTTP $status" }
            val result = runCatching { JSONObject(response) }.getOrElse { error("WxPusher 返回无效响应") }
            check(result.optBoolean("success", false) && result.optInt("code", -1) == 1000) {
                "WxPusher 拒绝发送（业务码 ${result.optInt("code", -1)}）"
            }
            val items = result.optJSONArray("data")
            check(items != null && items.length() == 1 && items.optJSONObject(0)?.optInt("code", -1) == 1000) {
                "WxPusher 接收目标未创建发送任务"
            }
        } finally {
            connection.disconnect()
        }
    }
}
