package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat

class CopyVerificationCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getStringExtra(EXTRA_CODE) ?: return
        val notificationId = intent.getIntExtra(EXTRA_NOTIFICATION_ID, -1)

        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboard != null) {
            val clip = ClipData.newPlainText("VerificationCode", code)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "验证码 $code 已复制", Toast.LENGTH_SHORT).show()
        }

        if (notificationId != -1) {
            NotificationManagerCompat.from(context).cancel(notificationId)
        }
    }

    companion object {
        const val ACTION_COPY_CODE = "org.fossify.messages.action.copy_code"
        const val EXTRA_CODE = "extra_code"
        const val EXTRA_NOTIFICATION_ID = "extra_notification_id"
    }
}
