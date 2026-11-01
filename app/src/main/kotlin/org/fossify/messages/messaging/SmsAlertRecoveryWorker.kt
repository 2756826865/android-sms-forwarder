package org.fossify.messages.messaging

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Notification backoff never becomes a prerequisite for SMS recovery. */
class SmsAlertRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val alerts = SmsIngressAlerts(applicationContext)
        alerts.drain()
        return if (alerts.hasPending()) Result.retry() else Result.success()
    }

    companion object {
        private const val RETRY_SECONDS = 30L
        private const val FOLLOW_UP_SECONDS = 2L

        fun enqueue(context: Context) {
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniqueWork("sms-alert-publication", ExistingWorkPolicy.KEEP, request())
            // A new receipt racing the primary worker's last query always leaves a later wakeup.
            // Replace one debounce slot; never append a chain or cancel the primary retry worker.
            manager.enqueueUniqueWork("sms-alert-publication-follow-up", ExistingWorkPolicy.REPLACE,
                request(FOLLOW_UP_SECONDS))
        }

        private fun request(delaySeconds: Long = 0L) =
            OneTimeWorkRequestBuilder<SmsAlertRecoveryWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_SECONDS, TimeUnit.SECONDS).build()
    }
}
