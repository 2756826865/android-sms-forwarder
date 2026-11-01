package org.fossify.messages.messaging

import android.content.Context
import androidx.work.*
import java.util.concurrent.TimeUnit

/** Notification backoff never becomes a prerequisite for SMS recovery. */
class SmsAlertRecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val alerts = SmsIngressAlerts(applicationContext)
        alerts.drain()
        return if (alerts.hasPending()) Result.retry() else Result.success()
    }
    companion object {
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork("sms-alert-publication", ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<SmsAlertRecoveryWorker>()
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build())
        }
    }
}
