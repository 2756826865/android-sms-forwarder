package org.fossify.messages.helpers.liveisland

import android.app.PendingIntent

data class LiveIslandMessage(
    val notificationId: Int,
    val title: String,
    val body: String,
    val ticker: String,
    val contentPendingIntent: PendingIntent?,
)
