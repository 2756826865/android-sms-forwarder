package org.fossify.messages.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import org.fossify.messages.services.IncomingSmsService
import org.fossify.messages.services.SmsKeepAliveService

/**
 * Fallback receiver for standard SMS_RECEIVED broadcasts on OEM ROMs (ColorOS/HyperOS/HarmonyOS)
 * or when the app is running in non-default SMS mode.
 * Deduplication in IncomingSmsService ensures zero duplicate processing.
 */
class SmsFallbackReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            return
        }
        Log.i(TAG, "received ${intent.action} fallback broadcast; handing off to IncomingSmsService")
        val appContext = context.applicationContext
        // goAsync 必须最先拿到，且 ensureStarted / enqueue / processMinimal 全部放进 try：
        // 任何一步抛异常都不能阻止 finally 里的 pending.finish()，否则广播令牌泄漏。
        // 同时保证 goAsync 之前的语句抛异常时不会跳过降级路径（那会让兜底在最该生效时失效）。
        val pending = goAsync()
        try {
            // 保活服务只是锦上添花：它若抛异常绝不能连累降级路径，否则兜底在最该生效时失效。
            runCatching { SmsKeepAliveService.ensureStarted(appContext) }
                .onFailure { Log.w(TAG, "keep-alive service start failed", it) }
            if (!IncomingSmsService.enqueue(appContext, intent)) {
                Log.w(TAG, "foreground service rejected; running degraded minimal path")
                IncomingSmsService.processMinimal(appContext, intent)
            }
        } finally {
            pending.finish()
        }
    }

    private companion object {
        const val TAG = "SmsFallbackReceiver"
    }
}
