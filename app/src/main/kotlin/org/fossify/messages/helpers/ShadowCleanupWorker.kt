package org.fossify.messages.helpers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.fossify.messages.extensions.getMessagesDB
import java.util.concurrent.TimeUnit

class ShadowCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val db = applicationContext.getMessagesDB()
            val now = System.currentTimeMillis()
            
            // Retention policy:
            // message_operations / steps: 14 days
            // forwarding deliveries / attempts: 14 days
            val shadowRetentionMs = TimeUnit.DAYS.toMillis(14)
            val shadowCutoff = now - shadowRetentionMs
            
            // diagnostic counters: 30 days
            val counterRetentionMs = TimeUnit.DAYS.toMillis(30)
            val counterCutoff = now - counterRetentionMs
            
            db.runInTransaction {
                db.openHelper.writableDatabase.apply {
                    execSQL("DELETE FROM message_operation_steps WHERE timestamp < $shadowCutoff")
                    execSQL("DELETE FROM forwarding_attempts WHERE created_at < $shadowCutoff")
                    execSQL("DELETE FROM forwarding_deliveries WHERE created_at < $shadowCutoff")
                    execSQL("DELETE FROM message_operations WHERE created_at < $shadowCutoff")
                    execSQL("DELETE FROM operation_diagnostic_counters WHERE updated_at < $counterCutoff")
                    // sms_send operations/parts: 14 days (parts cascade on operation delete)
                    execSQL("DELETE FROM sms_send_operations WHERE created_at < $shadowCutoff")
                }
            }
            Result.success()
        } catch (e: Exception) {
            Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "shadow_cleanup_periodic"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ShadowCleanupWorker>(24, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
