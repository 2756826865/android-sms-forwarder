package org.fossify.messages.forwarding

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

internal object QmsgSender {
    fun send(keyOrUrl: String, message: String): Long? {
        val endpoint = QmsgProtocol.endpoint(keyOrUrl)
        ForwardingUrlPolicy.requireAllowed(endpoint, false)
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        return connection.withDisconnect {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            outputStream.bufferedWriter(StandardCharsets.UTF_8).use {
                it.write(QmsgProtocol.formBody(message))
            }
            val status = responseCode
            val responseText = (if (status in 200..299) inputStream else errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(status in 200..299) { "Qmsg HTTP $status: ${responseText.take(200)}" }
            val response = runCatching { JSONObject(responseText) }
                .getOrElse { error("Qmsg 返回内容不是有效 JSON") }
            QmsgProtocol.requireAccepted(response)
        }
    }
}
