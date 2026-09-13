package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.extensions.notificationManager
import org.fossify.messages.helpers.DatabaseHealth
import org.fossify.messages.helpers.DatabaseRecoveryNotifier

/** 用户点掉「本地数据库恢复模式」提示：撤下通知并清除恢复模式标志。 */
class DatabaseRecoveryDismissReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_DISMISS) return
        DatabaseHealth.clearRecoveryMode(context)
        context.notificationManager.cancel(DatabaseRecoveryNotifier.NOTIFICATION_ID)
    }

    companion object {
        const val ACTION_DISMISS = "org.fossify.messages.action.database_recovery_dismiss"
    }
}
