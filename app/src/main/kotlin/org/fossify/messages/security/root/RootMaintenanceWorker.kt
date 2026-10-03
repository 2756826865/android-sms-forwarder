package org.fossify.messages.security.root

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import org.fossify.messages.extensions.syncThreadToLocal
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages
import java.util.concurrent.TimeUnit

/** Best-effort scheduled diagnostics, never a root daemon or a message replay executor. */
class RootMaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (!MultiForwardConfig(applicationContext).rootEnhancementEnabled) return Result.success()
        val prefs = applicationContext.getSharedPreferences("root_maintenance", Context.MODE_PRIVATE)
        try {
            val report = RootEnhancementManager.collectReadOnlyDiagnostics(applicationContext)
            prefs.edit().putLong("checkedAt", System.currentTimeMillis())
                .putBoolean("granted", report.rootStatus.granted)
                .putString("diagnostics", report.lines.joinToString("\n")).apply()
            // Sync only: never enqueue forwarding, notifications or remote commands for old messages.
            // Root may restore READ_SMS through the framework; never read the provider database file.
            if (!MultiForwardConfig(applicationContext).rootEnhancementEnabled) return Result.success()
            if (report.rootStatus.granted) {
                if (MultiForwardConfig(applicationContext).keepAliveServiceEnabled) RootWatchdog.start(applicationContext)
                else RootWatchdog.stop(applicationContext)
                if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
                    RootEnhancementManager.restoreSmsReadAccess(applicationContext)
                }
            }
            if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED) {
                val threads = linkedSetOf<Long>()
                applicationContext.contentResolver.query(
                    Telephony.Sms.CONTENT_URI, arrayOf(Telephony.Sms.THREAD_ID),
                    "${Telephony.Sms.DATE} >= ?", arrayOf((System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1)).toString()),
                    null
                )?.use { cursor -> while (cursor.moveToNext()) threads.add(cursor.getLong(0)) }
                threads.forEach { applicationContext.syncThreadToLocal(it) }
                if (threads.isNotEmpty()) { refreshMessages(); refreshConversations() }
                prefs.edit().putString("syncStatus", "provider sync completed").apply()
            } else {
                prefs.edit().putString("syncStatus", "READ_SMS permission unavailable").apply()
            }
            return Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            prefs.edit().putString("syncStatus", e.javaClass.simpleName).apply()
            return Result.failure()
        }
    }

    companion object {
        private const val NAME = "root-maintenance"
        fun sync(context: Context) {
            val manager = WorkManager.getInstance(context)
            if (!MultiForwardConfig(context).rootEnhancementEnabled) {
                RootWatchdog.stop(context)
                manager.cancelUniqueWork(NAME)
                return
            }
            manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<RootMaintenanceWorker>(15, TimeUnit.MINUTES).build())
        }
    }
}
