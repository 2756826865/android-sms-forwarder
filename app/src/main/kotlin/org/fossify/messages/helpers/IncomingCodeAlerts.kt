package org.fossify.messages.helpers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.fossify.messages.R
import org.fossify.messages.activities.MainActivity
import org.fossify.messages.autofill.VerificationCodeExtractor
import org.fossify.messages.extensions.config
import org.fossify.messages.helpers.liveisland.LiveIslandCoordinator
import org.fossify.messages.helpers.liveisland.LiveIslandMessage
import org.fossify.messages.models.Events
import org.fossify.messages.receivers.CopyVerificationCodeReceiver
import org.greenrobot.eventbus.EventBus

/** Alerts never depend on forwarding success, and never create or delete system SMS rows. */
object IncomingCodeAlerts {
    private const val POPUP_DEDUP_WINDOW_MS = 120_000L
    private const val RECENT_POPUP_CAPACITY = 64
    private val recent = LinkedHashMap<String, Long>()

    fun popup(context: Context, address: String, body: String, eventId: String = "") {
        if (!context.config.showVerificationCodePopup) return
        val code = VerificationCodeExtractor.extractCode(body) ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        val key = eventId.ifBlank {
            org.fossify.messages.messaging.IncomingSmsUiSyncWorker.contentHash(address, body)
        }
        synchronized(recent) {
            recent.entries.removeAll { now - it.value > POPUP_DEDUP_WINDOW_MS }
            if (recent.containsKey(key)) return
            if (recent.size >= RECENT_POPUP_CAPACITY) recent.remove(recent.keys.first())
            recent[key] = now
        }
        Handler(Looper.getMainLooper()).post {
            EventBus.getDefault().post(Events.VerificationCodeReceived(code))
        }
    }

    fun showUnstored(context: Context, address: String, body: String, fingerprint: String) {
        runCatching {
            org.fossify.messages.autofill.SmsAutofillAccessibilityService.onNewVerificationSms(
                context,
                body,
            )
        }
        popup(context, address, body, fingerprint)
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = "incoming_sms_pending"
        manager.createNotificationChannel(
            NotificationChannel(channel, "待同步短信", NotificationManager.IMPORTANCE_HIGH)
        )
        val notificationId = fingerprint.hashCode()
        val open =
            PendingIntent.getActivity(
                context,
                notificationId,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val canShowBody = context.config.lockScreenVisibilitySetting == LOCK_SCREEN_SENDER_MESSAGE
        val code = VerificationCodeExtractor.extractCode(body)
        val title = if (canShowBody && code != null) "验证码 $code" else "收到短信"
        val visibleBody = if (canShowBody) body else "打开应用查看"
        val builder =
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_messenger)
                .setContentTitle(title)
                .setContentText(visibleBody)
                .setStyle(NotificationCompat.BigTextStyle().bigText(visibleBody))
                .setSubText("原短信正在同步；转发不代表本地已入库")
                .setContentIntent(open)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setVisibility(
                    if (canShowBody) NotificationCompat.VISIBILITY_PRIVATE
                    else NotificationCompat.VISIBILITY_SECRET
                )
        if (canShowBody && code != null) {
            val copy =
                Intent(context, CopyVerificationCodeReceiver::class.java)
                    .setAction(CopyVerificationCodeReceiver.ACTION_COPY_CODE)
                    .putExtra(CopyVerificationCodeReceiver.EXTRA_CODE, code)
            builder.addAction(
                org.fossify.commons.R.drawable.ic_copy_vector,
                "复制验证码",
                PendingIntent.getBroadcast(
                    context,
                    notificationId,
                    copy,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        val island = LiveIslandMessage(notificationId, title, visibleBody, title, open)
        val kind = runCatching {
            LiveIslandCoordinator.applyToBuilder(context, builder, island)
        }
            .onFailure {
                Log.w("IncomingCodeAlerts", "island unavailable: ${it.javaClass.simpleName}")
            }
        manager.notify(notificationId, builder.build())
        runCatching { LiveIslandCoordinator.afterNotify(context, island, kind.getOrNull()) }
    }
}
