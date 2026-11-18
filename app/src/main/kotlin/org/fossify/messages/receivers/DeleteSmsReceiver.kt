package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.deleteMessage
import org.fossify.messages.extensions.updateLastConversationMessage
import org.fossify.messages.helpers.IS_MMS
import org.fossify.messages.helpers.MESSAGE_ID
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.refreshMessages

class DeleteSmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val threadId = intent.getLongExtra(THREAD_ID, 0L)
        val messageId = intent.getLongExtra(MESSAGE_ID, 0L)
        val isMms = intent.getBooleanExtra(IS_MMS, false)
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        appContext.notificationManager.cancel(threadId.hashCode())
        ensureBackgroundThread {
            try {
                appContext.deleteMessage(messageId, isMms)
                appContext.updateLastConversationMessage(threadId)
                refreshMessages()
                refreshConversations()
            } finally {
                pendingResult.finish()
            }
        }
    }
}
