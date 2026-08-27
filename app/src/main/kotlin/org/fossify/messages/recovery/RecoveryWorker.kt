package org.fossify.messages.recovery

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.fossify.messages.models.RecoveryTriggerSource
import java.util.concurrent.TimeUnit

/**
 * 周期性系统恢复与自愈 Worker
 *
 * 职责：
 * 1. 周期性扫描并修复死锁 Outbox 任务、推进到期退避任务；
 * 2. 低功耗、无网络依赖、异常隔离 (Fail-Open)。
 */
class RecoveryWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return try {
            val summary = RecoveryEngine.runRecoveryScan(
                context = applicationContext,
                triggerSource = RecoveryTriggerSource.PERIODIC_WORKER
            )
            Log.d(TAG, "RecoveryWorker scan finished: ${summary.totalRecovered} items recovered")
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "RecoveryWorker encountered exception during scan: ${e.message}")
            Result.success() // Fail-open: do not trigger aggressive WorkManager retries on recovery scan errors
        }
    }

    companion object {
        private const val TAG = "RecoveryWorker"
        private const val WORK_NAME = "periodic_recovery_worker"
        private const val REPEAT_INTERVAL_MINUTES = 15L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RecoveryWorker>(
                REPEAT_INTERVAL_MINUTES,
                TimeUnit.MINUTES
            )
                .setConstraints(Constraints.NONE)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
