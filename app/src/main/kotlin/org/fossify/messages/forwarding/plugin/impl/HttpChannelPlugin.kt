package org.fossify.messages.forwarding.plugin.impl

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.fossify.messages.forwarding.plugin.ForwardChannelPlugin
import org.fossify.messages.forwarding.plugin.model.ChannelResult
import org.fossify.messages.forwarding.plugin.model.ForwardPayload
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 通用 HTTP 类转发插件 (支持 PushPlus, 钉钉机器人, 飞书, 企业微信, Bark, 自定义 Webhook)
 */
class HttpChannelPlugin(
    override val pluginId: String = "custom_webhook",
    override val displayName: String = "HTTP Webhook"
) : ForwardChannelPlugin {

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    override fun validateConfig(config: Map<String, String>): Boolean {
        val url = config["url"] ?: config["webhook_url"] ?: config["endpoint"]
        return !url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))
    }

    override suspend fun send(context: Context, payload: ForwardPayload): ChannelResult = withContext(Dispatchers.IO) {
        val url = payload.targetConfig["url"] ?: payload.targetConfig["webhook_url"] ?: payload.targetConfig["endpoint"]
        if (url.isNullOrBlank()) {
            return@withContext ChannelResult.Failed(
                errorClass = "InvalidConfig",
                errorMessage = "Target URL is missing in targetConfig"
            )
        }

        val requestBodyText = payload.rawContentForTransmission ?: "{}"
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val body = requestBodyText.toRequestBody(mediaType)

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body)

        payload.targetConfig["headers"]?.let { customHeaders ->
            customHeaders.split(";").forEach { headerPair ->
                val parts = headerPair.split(":", limit = 2)
                if (parts.size == 2) {
                    requestBuilder.addHeader(parts[0].trim(), parts[1].trim())
                }
            }
        }

        try {
            val response = httpClient.newCall(requestBuilder.build()).execute()
            val code = response.code
            response.close()

            when (code) {
                in 200..299 -> ChannelResult.Success
                in 500..599, 408, 429 -> ChannelResult.Retry("HTTP $code Server Error / Rate Limit")
                in 400..499 -> ChannelResult.Failed("HttpError$code", "Client error $code (Fatal)")
                else -> ChannelResult.Retry("Unexpected HTTP $code")
            }
        } catch (e: IOException) {
            ChannelResult.Retry("Network IO Exception: ${e.message}")
        } catch (e: Exception) {
            ChannelResult.Failed(e.javaClass.simpleName, e.message ?: "Unknown HTTP exception")
        }
    }
}
