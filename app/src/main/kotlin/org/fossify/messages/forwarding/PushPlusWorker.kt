package org.fossify.messages.forwarding

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class PushPlusWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val config = PushPlusConfig(applicationContext)
        if (!config.enabled && !inputData.getBoolean(KEY_IS_TEST, false)) return Result.success()

        val token = config.getToken()
        if (token.isBlank()) {
            config.lastStatus = "发送失败：未配置 Token"
            return Result.failure()
        }

        val sender = inputData.getString(KEY_SENDER).orEmpty()
        val body = inputData.getString(KEY_BODY).orEmpty()
        val receivedAt = inputData.getLong(KEY_RECEIVED_AT, System.currentTimeMillis())
        val subscriptionId = inputData.getInt(KEY_SUBSCRIPTION_ID, -1)
        val payload = ForwardingMessageFormatter.format(
            context = applicationContext,
            sender = sender,
            body = body,
            receivedAt = receivedAt,
            subscriptionId = subscriptionId,
            titlePrefix = config.titlePrefix,
            includeSender = config.includeSender,
            includeSim = config.includeSim,
            includeTime = config.includeTime,
        )

        return try {
            val response = postMessage(token, payload.title, payload.content)
            val json = JSONObject(response)
            if (json.optInt("code") == 200) {
                config.lastStatus = "发送成功：${SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}"
                Result.success()
            } else {
                config.lastStatus = "发送失败：${json.optString("msg", "PushPlus 拒绝请求")}"
                Result.failure()
            }
        } catch (error: Exception) {
            config.lastStatus = "发送失败，等待重试：${error.message ?: error.javaClass.simpleName}"
            Result.retry()
        }
    }

    private fun postMessage(token: String, title: String, content: String): String {
        val payload = JSONObject()
            .put("token", token)
            .put("title", title)
            .put("content", content)
            .put("template", "txt")
            .put("channel", "wechat")
            .toString()

        val connection = URL(PUSHPLUS_URL).openConnection() as HttpURLConnection
        return connection.run {
            requestMethod = "POST"
            connectTimeout = 8_000
            readTimeout = 8_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(payload) }
            val stream = if (responseCode in 200..299) inputStream else errorStream
            stream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
    }

    companion object {
        private const val PUSHPLUS_URL = "https://www.pushplus.plus/send"
        private const val KEY_SENDER = "sender"
        private const val KEY_BODY = "body"
        private const val KEY_RECEIVED_AT = "received_at"
        private const val KEY_SUBSCRIPTION_ID = "subscription_id"
        private const val KEY_IS_TEST = "is_test"

        fun enqueue(
            context: Context,
            sender: String,
            body: String,
            receivedAt: Long,
            subscriptionId: Int,
            uniqueId: String
        ) {
            val request = OneTimeWorkRequestBuilder<PushPlusWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SENDER to sender,
                        KEY_BODY to body,
                        KEY_RECEIVED_AT to receivedAt,
                        KEY_SUBSCRIPTION_ID to subscriptionId
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("pushplus-$uniqueId", ExistingWorkPolicy.KEEP, request)
        }

        fun enqueueTest(context: Context, sender: String, body: String) {
            val request = OneTimeWorkRequestBuilder<PushPlusWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SENDER to sender,
                        KEY_BODY to body,
                        KEY_RECEIVED_AT to System.currentTimeMillis(),
                        KEY_IS_TEST to true
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
