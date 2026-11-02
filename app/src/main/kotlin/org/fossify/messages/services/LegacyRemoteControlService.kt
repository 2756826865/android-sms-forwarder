package org.fossify.messages.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import org.fossify.messages.R

/** Handles a framework restart of an old component during an application upgrade. */
abstract class LegacyRemoteControlService(private val legacyId: Int) : Service() {
    private val timeout = android.os.Handler(android.os.Looper.getMainLooper())
    private val finishHandoff = Runnable {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // Framework boundary: persist the unified failure status and retire the old owner.
    @Suppress("TooGenericExceptionCaught")
    override fun onCreate() {
        super.onCreate()
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                        "background_migration",
                        getString(R.string.background_service_title),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                    .apply {
                        setSound(null, null)
                        enableVibration(false)
                        setShowBadge(false)
                    }
            )
            val notification =
                NotificationCompat.Builder(this, "background_migration")
                    .setSmallIcon(R.drawable.ic_messenger)
                    .setContentTitle(getString(R.string.background_service_title))
                    .setContentText(getString(R.string.background_service_starting))
                    .setSilent(true)
                    .setOngoing(true)
                    .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
                startForeground(
                    legacyId,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            else startForeground(legacyId, notification)
            if (
                SmsKeepAliveService.ensureStarted(this) != SmsKeepAliveService.Companion.StartResult.REQUESTED
            ) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            timeout.postDelayed(finishHandoff, HANDOFF_TIMEOUT_MS)
            // Successful unified foreground startup retires all old components and notification
            // IDs.
        } catch (error: Exception) {
            android.util.Log.w("BackgroundMigration", "legacy handoff failed", error)
            stopSelf()
        }
    }

    private companion object { const val HANDOFF_TIMEOUT_MS = 10_000L }

    override fun onDestroy() {
        timeout.removeCallbacks(finishHandoff)
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onBind(intent: Intent?): IBinder? = null
}
