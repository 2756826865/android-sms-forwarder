package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.fossify.commons.extensions.notificationManager
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.markThreadMessagesRead
import org.fossify.messages.helpers.MARK_AS_READ
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.refreshConversations

class MarkAsReadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            MARK_AS_READ -> {
                val threadId = intent.getLongExtra(THREAD_ID, 0L)
                val pendingResult = goAsync()
                val appContext = context.applicationContext
                appContext.notificationManager.cancel(threadId.hashCode())
                ensureBackgroundThread {
                    try {
                        appContext.markThreadMessagesRead(threadId)
                        appContext.conversationsDB.markRead(threadId)
                        refreshConversations()
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
