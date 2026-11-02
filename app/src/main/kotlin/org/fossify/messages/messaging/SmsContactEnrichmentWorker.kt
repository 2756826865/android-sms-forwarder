package org.fossify.messages.messaging

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.BackoffPolicy
import android.util.Log
import java.util.concurrent.TimeUnit
import androidx.work.workDataOf
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber
import org.fossify.messages.extensions.messagesDB

/** Only presentation columns may change; never replace receipt/read/SIM snapshots. */
class SmsContactEnrichmentWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    @Suppress("TooGenericExceptionCaught") // Contacts/Room failures retain this independent retry.
    override suspend fun doWork(): Result {
        val id = inputData.getLong("message_id", -1)
        return try {
            val message = applicationContext.messagesDB.getMessageWithId(id) ?: return Result.success()
            val contact =
                applicationContext.getNameAndPhotoFromPhoneNumber(message.senderPhoneNumber)
            applicationContext.getMessagesDB().runInTransaction {
                applicationContext.messagesDB.updateSenderDisplay(
                    id,
                    message.senderPhoneNumber,
                    contact.name,
                    contact.photoUri.orEmpty(),
                )
                applicationContext.conversationsDB.updateSenderDisplay(
                    message.threadId,
                    message.senderPhoneNumber,
                    contact.name,
                    contact.photoUri.orEmpty(),
                )
            }
            Result.success()
        } catch (error: java.util.concurrent.CancellationException) {
            throw error
        } catch (error: Exception) {
            if (SmsContactRetryPolicy.shouldRetry(runAttemptCount)) {
                Result.retry()
            } else {
                runCatching {
                    applicationContext.getSharedPreferences("sms_contact_enrichment_status", Context.MODE_PRIVATE)
                        .edit()
                        .putLong("last_failure_at", System.currentTimeMillis())
                        .putString("last_failure_reason", error.javaClass.simpleName)
                        .putLong("last_failure_message_id", id)
                        .putInt("last_failure_attempts", runAttemptCount + 1)
                        .apply()
                }
                Log.w("SmsContactEnrichment", "contact display enrichment exhausted bounded retries", error)
                Result.failure(workDataOf("reason" to error.javaClass.simpleName))
            }
        }
    }

    companion object {
        fun enqueue(context: Context, id: Long) {
            WorkManager.getInstance(context)
                .enqueueUniqueWork(
                    "sms-contact-$id",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<SmsContactEnrichmentWorker>()
                        .setInputData(workDataOf("message_id" to id))
                        .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                        .build(),
                )
        }
    }
}
