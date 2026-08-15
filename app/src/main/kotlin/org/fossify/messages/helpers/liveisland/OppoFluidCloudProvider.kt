package org.fossify.messages.helpers.liveisland

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import org.fossify.messages.extensions.config
import org.json.JSONObject

/**
 * ColorOS 16+ uses [AndroidLiveUpdateProvider] (Live Updates API).
 * ColorOS 15 uses IntelligentIntent via system ContentProvider (requires OPPO-side approval for production).
 */
class OppoFluidCloudProvider : LiveIslandProvider {

    override val kind = LiveIslandKind.OPPO_FLUID_CLOUD

    override fun isSupported(context: Context): Boolean {
        if (!context.config.enableLiveIsland || !RomDetect.supportsOppoFluidCloud()) {
            return false
        }
        // ColorOS 16+ is handled by the standard Live Updates path.
        val colorOsMajor = RomDetect.getColorOsMajorVersion() ?: return false
        return colorOsMajor in COLOR_OS_15 until COLOR_OS_16
    }

    override fun applyToBuilder(
        context: Context,
        builder: NotificationCompat.Builder,
        message: LiveIslandMessage,
    ) {
        builder.setTimeoutAfter(ISLAND_DISMISS_MS)
    }

    override fun afterNotify(context: Context, message: LiveIslandMessage) {
        shareIntelligentIntent(context, message)
    }

    private fun shareIntelligentIntent(context: Context, message: LiveIslandMessage) {
        val payload = buildIntelligentIntentJson(message)
        val extras = Bundle().apply {
            putString("IntelligentIntent", payload)
        }
        try {
            val authority = RomDetect.getSystemProperty("intelligent_intent_authority")
                ?: DEFAULT_AUTHORITY
            val client = context.contentResolver.acquireUnstableContentProviderClient(
                Uri.parse("content://$authority"),
            ) ?: return
            client.use {
                it.call(METHOD_SHARE_INTENT, null, extras)
            }
        } catch (_: Exception) {
            // OPPO fluid cloud requires platform registration; silently degrade to normal notification.
        }
    }

    private fun buildIntelligentIntentJson(message: LiveIslandMessage): String {
        val title = message.title.take(MAX_FIELD)
        val body = message.body.take(MAX_FIELD)
        val capsule = JSONObject()
            .put("title", title)
            .put("content", body)
        val primary = JSONObject()
            .put("title", title)
            .put("content", body)
        val intentEntity = JSONObject()
            .put("entityName", "MESSAGE")
            .put("entityId", message.notificationId.toString())
            .put("capsule", capsule)
            .put("primary", primary)
        return JSONObject()
            .put("intentName", "sms.message.received")
            .put("identifier", "sms-${message.notificationId}")
            .put("timestamp", System.currentTimeMillis())
            .put(
                "serviceId",
                JSONObject()
                    .put("launcher", PLACEHOLDER_LAUNCHER_SERVICE_ID)
                    .put("fluidCloud", PLACEHOLDER_FLUID_SERVICE_ID),
            )
            .put("intentAction", JSONObject().put("actionStatus", ACTION_CREATE))
            .put("actionStatus", ACTION_CREATE)
            .put("intentEntity", intentEntity)
            .toString()
    }

    companion object {
        private const val COLOR_OS_15 = 15
        private const val COLOR_OS_16 = 16
        private const val DEFAULT_AUTHORITY = "IntelligentIntent"
        private const val METHOD_SHARE_INTENT = "shareIntent"
        private const val ACTION_CREATE = 0
        private const val ISLAND_DISMISS_MS = 8_000L
        private const val MAX_FIELD = 80
        // Placeholder IDs; replace after OPPO open-platform registration.
        private const val PLACEHOLDER_LAUNCHER_SERVICE_ID = "999800001"
        private const val PLACEHOLDER_FLUID_SERVICE_ID = "999900001"
    }
}
