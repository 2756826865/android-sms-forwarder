package org.fossify.messages.messaging

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.delay
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.extensions.config
import org.json.JSONArray

class BulkSendWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val body = inputData.getString(KEY_BODY).orEmpty()
        val subId = inputData.getInt(KEY_SUB_ID, -1)
        val numbers = inputData.getString(KEY_NUMBERS)?.let(::decodeNumbers).orEmpty()
        if (body.isBlank() || numbers.isEmpty()) return Result.failure()

        numbers.forEachIndexed { index, number ->
            applicationContext.sendMessageCompat(
                text = body,
                addresses = listOf(number),
                subId = subId,
                attachments = emptyList()
            )
            if (index < numbers.lastIndex) {
                delay(applicationContext.config.bulkSendDelaySeconds * 1_000L)
            }
        }
        refreshConversations()
        return Result.success()
    }

    private fun decodeNumbers(value: String): List<String> {
        val array = JSONArray(value)
        return buildList {
            for (index in 0 until array.length()) add(array.getString(index))
        }
    }

    companion object {
        private const val KEY_BODY = "body"
        private const val KEY_SUB_ID = "sub_id"
        private const val KEY_NUMBERS = "numbers"
        fun enqueue(context: Context, body: String, subId: Int, numbers: List<String>) {
            val request = OneTimeWorkRequestBuilder<BulkSendWorker>()
                .setInputData(
                    workDataOf(
                        KEY_BODY to body,
                        KEY_SUB_ID to subId,
                        KEY_NUMBERS to JSONArray(numbers).toString()
                    )
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
