package org.fossify.messages.helpers.liveisland

import android.content.Context
import androidx.core.app.NotificationCompat

interface LiveIslandProvider {
    val kind: LiveIslandKind

    fun isSupported(context: Context): Boolean

    /** Enhance the notification builder before [android.app.NotificationManager.notify]. */
    fun applyToBuilder(context: Context, builder: NotificationCompat.Builder, message: LiveIslandMessage)

    /** Optional hook after notify (e.g. OPPO IntelligentIntent on ColorOS 15). */
    fun afterNotify(context: Context, message: LiveIslandMessage) {}
}
