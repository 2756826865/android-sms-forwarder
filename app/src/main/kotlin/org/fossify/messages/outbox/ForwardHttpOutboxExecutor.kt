package org.fossify.messages.outbox

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.messages.helpers.ShadowRepository
import org.fossify.messages.models.OutboxTaskEntity
import org.fossify.messages.models.OutboxTaskType
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * HTTP / Webhook 类转发渠道 Outbox 执行器
 *
 * 职责：
 * 1. 负责执行 Webhook / PushPlus / 钉钉 / 飞书 / 企业微信 / Bark / Gotify / 自定义 HTTP 等外发网络通知；
 * 2. 区分 2xx 成功、4xx 致命客户端错误 (不重试) 与 5xx/超时等网络可重试错误；
 * 3. 关联阶段 1A 事实模型，回写 ForwardingDelivery 最终状态。
 */
class ForwardHttpOutboxExecutor : OutboxExecutor {

    override fun canExecute(taskType: String): Boolean =
        taskType == OutboxTaskType.FORWARD_HTTP || taskType == OutboxTaskType.FORWARD_EMAIL

    override suspend fun execute(context: Context, task: OutboxTaskEntity): OutboxExecutionResult = withContext(Dispatchers.IO) {
        val payloadJson = task.payloadPayload ?: return@withContext OutboxExecutionResult.FatalFailure(
            errorClass = "IllegalArgumentException",
            errorMessage = "Missing payloadPayload for task ${task.taskId}"
        )

        val json = runCatching { JSONObject(payloadJson) }.getOrElse { error ->
            return@withContext OutboxExecutionResult.FatalFailure(
                errorClass = error.javaClass.name,
                errorMessage = "Invalid JSON payload: ${error.message}"
            )
        }

        val urlStr = json.optString("url", "").trim()
        val method = json.optString("method", "POST").uppercase()
        val headersObj = json.optJSONObject("headers")
        val bodyStr = json.optString("body", "")
        val deliveryId = json.optString("deliveryId", "")
        val channel = json.optString("channel", "HTTP")

        if (urlStr.isBlank()) {
            return@withContext OutboxExecutionResult.FatalFailure(
                errorClass = "MalformedURLException",
                errorMessage = "URL is blank for task ${task.taskId}"
            )
        }

        var connection: HttpURLConnection? = null
        try {
            val url = URL(urlStr)
            connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                doInput = true

                headersObj?.let { headers ->
                    val keys = headers.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        setRequestProperty(key, headers.optString(key))
                    }
                }

                if (method in setOf("POST", "PUT", "PATCH") && bodyStr.isNotEmpty()) {
                    doOutput = true
                    if (getRequestProperty("Content-Type") == null) {
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    }
                    outputStream.use { os ->
                        BufferedWriter(OutputStreamWriter(os, StandardCharsets.UTF_8)).use { writer ->
                            writer.write(bodyStr)
                            writer.flush()
                        }
                    }
                }
            }

            val statusCode = connection.responseCode
            val isSuccess = statusCode in 200..299
            val responseStream = if (isSuccess) connection.inputStream else connection.errorStream
            val responseBody = responseStream?.use { stream ->
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).readText()
            }.orEmpty()

            if (isSuccess) {
                if (deliveryId.isNotBlank()) {
                    ShadowRepository.recordForwardingDeliveryState(context, deliveryId, "DELIVERED")
                }
                OutboxExecutionResult.Success
            } else if (statusCode == 408 || statusCode == 429 || statusCode in 500..599) {
                // 可重试服务端错误或限流
                OutboxExecutionResult.Retry(
                    errorClass = "Http$statusCode",
                    errorMessage = "Server error or rate limit $statusCode: $responseBody"
                )
            } else {
                // 4xx 客户端硬错误 (如 401 密钥错、404 地址错)，不应盲目重试
                if (deliveryId.isNotBlank()) {
                    ShadowRepository.recordForwardingDeliveryState(context, deliveryId, "FAILED")
                }
                OutboxExecutionResult.FatalFailure(
                    errorClass = "Http$statusCode",
                    errorMessage = "Client error $statusCode: $responseBody"
                )
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network exception during HTTP forward for task ${task.taskId}: ${e.message}")
            OutboxExecutionResult.Retry(
                errorClass = e.javaClass.name,
                errorMessage = e.message
            )
        } catch (e: Exception) {
            Log.w(TAG, "Fatal exception during HTTP forward for task ${task.taskId}: ${e.message}")
            if (deliveryId.isNotBlank()) {
                ShadowRepository.recordForwardingDeliveryState(context, deliveryId, "FAILED")
            }
            OutboxExecutionResult.FatalFailure(
                errorClass = e.javaClass.name,
                errorMessage = e.message
            )
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val TAG = "ForwardHttpOutboxExecutor"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 15_000
    }
}
