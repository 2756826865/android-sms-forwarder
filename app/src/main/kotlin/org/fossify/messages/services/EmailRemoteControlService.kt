package org.fossify.messages.services

import android.content.Context

/** Compatibility entry point; all steady-state foreground ownership is unified. */
class EmailRemoteControlService : LegacyRemoteControlService(LEGACY_NOTIFICATION_ID) {
    companion object {
        private const val LEGACY_NOTIFICATION_ID = 19090
        fun ensureStarted(context: Context) {
            SmsKeepAliveService.ensureStarted(context)
        }

        fun stop(context: Context) {
            SmsKeepAliveService.ensureStarted(context)
        }
    }
}
