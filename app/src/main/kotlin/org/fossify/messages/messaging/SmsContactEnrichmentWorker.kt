package org.fossify.messages.messaging

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
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
        val message = applicationContext.messagesDB.getMessageWithId(id) ?: return Result.success()
        return try {
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
        } catch (_: Exception) {
            Result.retry()
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
                        .build(),
                )
        }
    }
}
