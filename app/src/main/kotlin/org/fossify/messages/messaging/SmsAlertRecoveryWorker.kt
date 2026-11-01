package org.fossify.messages.messaging

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.BackoffPolicy
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
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("sms-alert-publication", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SmsAlertRecoveryWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETRY_SECONDS, TimeUnit.SECONDS).build())
        }
    }
}
