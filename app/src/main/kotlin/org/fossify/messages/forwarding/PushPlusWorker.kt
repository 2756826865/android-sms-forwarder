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

    override suspend fun getForegroundInfo() = ForwardingForegroundInfo.create(applicationContext)

    override suspend fun doWork(): Result {
        setForeground(getForegroundInfo())
        val config = PushPlusConfig(applicationContext)
        val isTest = inputData.getBoolean(KEY_IS_TEST, false)
        val history = ForwardingHistoryStore(applicationContext)
        val historyRecordId = inputData.getString(KEY_HISTORY_RECORD_ID).orEmpty()
        if (historyRecordId.isNotBlank()) history.markRunning(historyRecordId)
        if (!config.enabled && !isTest) {
            if (historyRecordId.isNotBlank()) history.markSkipped(historyRecordId, "PushPlus 已关闭")
            return Result.success()
        }

        val token = config.getToken()
        if (token.isBlank()) {
            config.lastStatus = "发送失败：未配置 Token"
            if (historyRecordId.isNotBlank()) history.markFailed(historyRecordId, "未配置 Token")
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
                if (historyRecordId.isNotBlank()) history.markSuccess(historyRecordId, "PushPlus 发送成功")
                Result.success()
            } else {
                val detail = json.optString("msg", "PushPlus 拒绝请求")
                config.lastStatus = "发送失败：$detail"
                if (historyRecordId.isNotBlank()) history.markFailed(historyRecordId, detail)
                Result.failure()
            }
        } catch (error: Exception) {
            val detail = error.message ?: error.javaClass.simpleName
            if (runAttemptCount < MAX_RETRY_INDEX) {
                config.lastStatus = "发送失败，等待重试：$detail"
                if (historyRecordId.isNotBlank()) history.markRetry(historyRecordId, detail)
                Result.retry()
            } else {
                config.lastStatus = "发送失败：$detail"
                if (historyRecordId.isNotBlank()) history.markFailed(historyRecordId, detail)
                Result.failure()
            }
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
        private const val KEY_HISTORY_RECORD_ID = "history_record_id"
        private const val MAX_RETRY_INDEX = 2

        fun enqueue(
            context: Context,
            sender: String,
            body: String,
            receivedAt: Long,
            subscriptionId: Int,
            uniqueId: String
        ) {
            val history = ForwardingHistoryStore(context)
            val historyRecordId = history.registerQueued(
                workId = uniqueId,
                channel = ForwardingChannels.PUSHPLUS,
                sender = sender,
                body = body,
                receivedAt = receivedAt,
                subscriptionId = subscriptionId,
                isTest = false,
            )
            val request = OneTimeWorkRequestBuilder<PushPlusWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SENDER to sender,
                        KEY_BODY to body,
                        KEY_RECEIVED_AT to receivedAt,
                        KEY_SUBSCRIPTION_ID to subscriptionId,
                        KEY_HISTORY_RECORD_ID to historyRecordId,
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork("pushplus-$uniqueId", ExistingWorkPolicy.KEEP, request)
            }.onFailure { error ->
                history.markFailed(historyRecordId, "发送任务入队失败：${error.message ?: error.javaClass.simpleName}")
            }
        }

        fun enqueueTest(context: Context, sender: String, body: String) {
            val now = System.currentTimeMillis()
            val workId = "test-pushplus-$now"
            val historyRecordId = ForwardingHistoryStore(context).registerQueued(
                workId = workId,
                channel = ForwardingChannels.PUSHPLUS,
                sender = sender,
                body = body,
                receivedAt = now,
                subscriptionId = -1,
                isTest = true,
            )
            val request = OneTimeWorkRequestBuilder<PushPlusWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SENDER to sender,
                        KEY_BODY to body,
                        KEY_RECEIVED_AT to now,
                        KEY_IS_TEST to true,
                        KEY_HISTORY_RECORD_ID to historyRecordId,
                    )
                )
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
