package org.fossify.messages.messaging

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony.Sms
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.syncThreadToLocal
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Read-only UI repair. Never forwards, consumes commands, or creates system SMS/thread IDs. */
class IncomingSmsUiSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val receivedAt = inputData.getLong(KEY_RECEIVED_AT, 0L)
        val expectedHash = inputData.getString(KEY_CONTENT_HASH).orEmpty()
        if (receivedAt <= 0L || expectedHash.isBlank()) return@withContext Result.failure()
        if (!canRead()) return@withContext retryOrFail("read permission unavailable")

        // The native default app may insert just after SMS_RECEIVED. Wait briefly without
        // blocking the receiver; longer delays use a bounded WorkManager retry.
        repeat(4) { pass ->
            if (!canRead()) return@withContext retryOrFail("read permission unavailable")
            try {
                if (syncMatchingThread(receivedAt, expectedHash)) {
                    refreshMessages()
                    refreshConversations()
                    Log.i(TAG, "read-only local sync completed")
                    return@withContext Result.success()
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: SecurityException) {
                return@withContext retryOrFail("read permission unavailable")
            } catch (error: Exception) {
                Log.w(TAG, "local sync unavailable: ${error.javaClass.simpleName}")
            }
            if (pass < 3) delay(250L)
        }
        retryOrFail("provider record not synchronized")
    }

    // Keep read-only repair pending across short permission/Provider outages. WorkManager
    // persists retries across process death; no forwarding or sending is performed here.
    private fun retryOrFail(reason: String): Result {
        return if (runAttemptCount < 12) {
            Log.w(TAG, "$reason; local sync pending")
            Result.retry()
        } else {
            Log.w(TAG, "$reason; retry limit reached")
            Result.failure(workDataOf("reason" to reason))
        }
    }

    private fun canRead() = ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) ==
        PackageManager.PERMISSION_GRANTED

    private fun syncMatchingThread(receivedAt: Long, expectedHash: String): Boolean {
        val projection = arrayOf(Sms._ID, Sms.THREAD_ID, Sms.ADDRESS, Sms.BODY, Sms.SUBSCRIPTION_ID)
        val expectedSub = inputData.getInt(KEY_SUBSCRIPTION, -1)
        var matchingId = 0L
        var matchingThread = 0L
        applicationContext.contentResolver.query(
            Sms.CONTENT_URI, projection,
            "${Sms.TYPE}=? AND ${Sms.DATE}>=? AND ${Sms.DATE}<=?",
            arrayOf(Sms.MESSAGE_TYPE_INBOX.toString(), (receivedAt - 60_000L).coerceAtLeast(0L).toString(),
                (receivedAt + 120_000L).toString()), "${Sms.DATE} DESC"
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(Sms._ID)
            val threadColumn = cursor.getColumnIndexOrThrow(Sms.THREAD_ID)
            val addressColumn = cursor.getColumnIndexOrThrow(Sms.ADDRESS)
            val bodyColumn = cursor.getColumnIndexOrThrow(Sms.BODY)
            val subColumn = cursor.getColumnIndex(Sms.SUBSCRIPTION_ID)
            var inspected = 0
            while (inspected < 256 && cursor.moveToNext()) {
                inspected++
                val sub = if (subColumn >= 0 && !cursor.isNull(subColumn)) cursor.getInt(subColumn) else -1
                if (expectedSub >= 0 && sub >= 0 && sub != expectedSub) continue
                val id = cursor.getLong(idColumn)
                val thread = cursor.getLong(threadColumn)
                if (id <= 0L || thread <= 0L) continue
                if (contentHash(cursor.getString(addressColumn).orEmpty(), cursor.getString(bodyColumn).orEmpty()) == expectedHash) {
                    matchingId = id
                    matchingThread = thread
                    break
                }
            }
        }
        if (matchingId <= 0L) return false
        applicationContext.syncThreadToLocal(matchingThread)
        // syncThreadToLocal tolerates Provider failures; do not treat a no-op as a repair.
        return applicationContext.messagesDB.getMessageWithId(matchingId)?.threadId == matchingThread
    }

    companion object {
        private const val TAG = "IncomingSmsUiSync"
        internal const val KEY_RECEIVED_AT = "received_at"
        internal const val KEY_CONTENT_HASH = "content_hash"
        internal const val KEY_SUBSCRIPTION = "subscription_id"

        internal fun contentHash(address: String, body: String): String =
            MessageDigest.getInstance("SHA-256").digest("$address\u0000$body".toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 255) }

        fun enqueue(context: Context, address: String, body: String, receivedAt: Long,
                    subscriptionId: Int, fingerprint: String) {
            runCatching {
                val request = OneTimeWorkRequestBuilder<IncomingSmsUiSyncWorker>()
                    .setInputData(workDataOf(KEY_RECEIVED_AT to receivedAt,
                        KEY_CONTENT_HASH to contentHash(address, body), KEY_SUBSCRIPTION to subscriptionId))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
                WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                    "sms-ui-sync-$fingerprint", ExistingWorkPolicy.KEEP, request)
            }.onFailure { Log.w(TAG, "unable to schedule local UI sync: ${it.javaClass.simpleName}") }
        }
    }
}
