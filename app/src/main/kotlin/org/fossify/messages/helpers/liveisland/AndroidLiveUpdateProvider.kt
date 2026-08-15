package org.fossify.messages.helpers.liveisland

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import org.fossify.messages.extensions.config

class AndroidLiveUpdateProvider : LiveIslandProvider {

    override val kind = LiveIslandKind.ANDROID_LIVE_UPDATE

    override fun isSupported(context: Context): Boolean {
        if (!context.config.enableLiveIsland) {
            return false
        }
        if (!RomDetect.supportsAndroidLiveUpdate()) {
            return false
        }
        val notificationManager = context.getSystemService(NotificationManager::class.java)
        return notificationManager?.canPostPromotedNotifications() == true
    }

    override fun applyToBuilder(
        context: Context,
        builder: NotificationCompat.Builder,
        message: LiveIslandMessage,
    ) {
        builder.setOngoing(true)
        builder.setOnlyAlertOnce(true)
        builder.setTimeoutAfter(ISLAND_DISMISS_MS)
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setRequestPromotedOngoing(true)
            builder.setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(message.body)
                    .setSummaryText(message.title),
            )
        }
    }

    companion object {
        private const val ISLAND_DISMISS_MS = 8_000L
    }
}
