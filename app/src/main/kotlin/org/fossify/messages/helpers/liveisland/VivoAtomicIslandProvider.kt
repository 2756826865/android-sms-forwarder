package org.fossify.messages.helpers.liveisland

import android.content.Context
import androidx.core.app.NotificationCompat

/**
 * Reserved for vivo OriginOS 5+ Atomic Island.
 * Requires vivo open-platform permission (公测) before enabling.
 */
class VivoAtomicIslandProvider : LiveIslandProvider {

    override val kind = LiveIslandKind.VIVO_ATOMIC_ISLAND

    override fun isSupported(context: Context): Boolean {
        // Reserved: enable when vivo atomic notification permission is granted.
        return false
    }

    override fun applyToBuilder(
        context: Context,
        builder: NotificationCompat.Builder,
        message: LiveIslandMessage,
    ) {
        // notification.superx.* extras will be added here after vivo platform approval.
    }
}
