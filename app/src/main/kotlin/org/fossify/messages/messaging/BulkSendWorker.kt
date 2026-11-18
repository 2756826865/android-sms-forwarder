package org.fossify.messages.messaging

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.io.File
import java.util.UUID
import kotlinx.coroutines.delay
import org.fossify.messages.extensions.config
import org.fossify.messages.forwarding.ForwardingForegroundInfo
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.models.SmsSendTriggerType
import org.json.JSONArray
import org.json.JSONObject

class BulkSendWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo() = ForwardingForegroundInfo.create(applicationContext)

    override suspend fun doWork(): Result {
        runCatching { setForeground(getForegroundInfo()) }
        val batchId = inputData.getString(KEY_BATCH_ID).orEmpty()
        val batchFile = if (batchId.isNotBlank()) batchFile(applicationContext, batchId) else null
        val stateJson = batchFile?.takeIf { it.exists() }?.let {
            runCatching { JSONObject(it.readText(Charsets.UTF_8)) }.getOrNull()
        }

        val body = stateJson?.optString("body").orEmpty().ifBlank { inputData.getString(KEY_BODY).orEmpty() }
        val subId = stateJson?.optInt("subId", -1) ?: inputData.getInt(KEY_SUB_ID, -1)
        val numbers = stateJson?.optJSONArray("numbers")?.let(::decodeNumbersArray)
            ?: inputData.getString(KEY_NUMBERS)?.let(::decodeNumbers).orEmpty()
        if (body.isBlank() || numbers.isEmpty()) {
            batchFile?.delete()
            return Result.failure()
        }

        // Legacy in-Data jobs lacked durable per-recipient progress; block blind re-runs on retry
        // so already-sent recipients are never billed twice.
        if (batchFile == null && runAttemptCount > 0) {
            Log.w(TAG, "Aborting legacy bulk-send retry without batch state file to prevent duplicate SMS")
            return Result.failure()
        }

        val startIndex = stateJson?.optInt("nextIndex", 0)?.coerceAtLeast(0) ?: 0
        var failedCount = stateJson?.optInt("failedCount", 0) ?: 0
        for (index in startIndex..numbers.lastIndex) {
            val number = numbers[index]
            // Advance checkpoint BEFORE carrier submission so an OS kill mid-flight never re-bills
            // the in-flight recipient on restart (fail-safe against duplicate carrier charges).
            persistProgress(batchFile, body, subId, numbers, nextIndex = index + 1, failedCount = failedCount)
            try {
                applicationContext.sendMessageCompat(
                    text = body,
                    addresses = listOf(number),
                    subId = subId,
                    attachments = emptyList(),
                    propagateErrors = true,
                    triggerType = SmsSendTriggerType.BULK
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failedCount++
                persistProgress(batchFile, body, subId, numbers, nextIndex = index + 1, failedCount = failedCount)
                Log.e(TAG, "Bulk send submission failed for recipient index $index: ${e.javaClass.simpleName}")
            }
            if (index < numbers.lastIndex) {
                delay(applicationContext.config.bulkSendDelaySeconds * 1_000L)
            }
        }
        batchFile?.delete()
        refreshConversations()
        return if (failedCount == 0) Result.success() else Result.failure()
    }

    private fun persistProgress(
        file: File?,
        body: String,
        subId: Int,
        numbers: List<String>,
        nextIndex: Int,
        failedCount: Int,
    ) {
        if (file == null) return
        runCatching {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            val json = JSONObject()
                .put("body", body)
                .put("subId", subId)
                .put("numbers", JSONArray(numbers))
                .put("nextIndex", nextIndex)
                .put("failedCount", failedCount)
            tmp.writeText(json.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(json.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    private fun decodeNumbers(value: String): List<String> = decodeNumbersArray(JSONArray(value))

    private fun decodeNumbersArray(array: JSONArray): List<String> {
        return buildList {
            for (index in 0 until array.length()) add(array.getString(index))
        }
    }

    companion object {
        private const val TAG = "BulkSendWorker"
        private const val KEY_BATCH_ID = "batch_id"
        private const val KEY_BODY = "body"
        private const val KEY_SUB_ID = "sub_id"
        private const val KEY_NUMBERS = "numbers"

        private fun batchFile(context: Context, batchId: String): File {
            val dir = File(context.noBackupFilesDir, "bulk_send_batches").apply { mkdirs() }
            return File(dir, "batch_$batchId.json")
        }

        fun enqueue(context: Context, body: String, subId: Int, numbers: List<String>) {
            if (body.isBlank() || numbers.isEmpty()) return
            val appCtx = context.applicationContext
            val batchId = UUID.randomUUID().toString()
            val file = batchFile(appCtx, batchId)
            val payload = JSONObject()
                .put("body", body)
                .put("subId", subId)
                .put("numbers", JSONArray(numbers))
                .put("nextIndex", 0)
                .put("failedCount", 0)
            file.writeText(payload.toString(), Charsets.UTF_8)

            val request = OneTimeWorkRequestBuilder<BulkSendWorker>()
                .setInputData(
                    workDataOf(
                        KEY_BATCH_ID to batchId,
                        KEY_SUB_ID to subId,
                    )
                )
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(appCtx).enqueue(request)
        }
    }
}
