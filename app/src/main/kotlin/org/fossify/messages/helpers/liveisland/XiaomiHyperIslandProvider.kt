package org.fossify.messages.helpers.liveisland

import android.content.Context
import android.os.Bundle
import androidx.core.app.NotificationCompat
import org.fossify.messages.extensions.config
import org.json.JSONObject

class XiaomiHyperIslandProvider : LiveIslandProvider {

    override val kind = LiveIslandKind.XIAOMI_HYPER_ISLAND

    override fun isSupported(context: Context): Boolean {
        return context.config.enableLiveIsland && RomDetect.supportsXiaomiHyperIsland()
    }

    override fun applyToBuilder(
        context: Context,
        builder: NotificationCompat.Builder,
        message: LiveIslandMessage,
    ) {
        val islandParams = buildIslandParams(message)
        builder.addExtras(
            Bundle().apply {
                putString(MIUI_FOCUS_PARAM, islandParams)
            },
        )
        builder.setTimeoutAfter(ISLAND_DISMISS_MS)
    }

    private fun buildIslandParams(message: LiveIslandMessage): String {
        val title = message.title.take(MAX_FIELD)
        val body = message.body.take(MAX_FIELD)
        val ticker = message.ticker.take(MAX_TICKER)

        val textInfo = JSONObject()
            .put("frontTitle", "短信")
            .put("title", title)
            .put("content", body)

        val bigIslandArea = JSONObject()
            .put("textInfo", textInfo)

        val smallIslandArea = JSONObject()
            .put("textInfo", JSONObject().put("title", title))

        val paramIsland = JSONObject()
            .put("islandProperty", 1)
            .put("islandTimeout", ISLAND_TIMEOUT_SECONDS)
            .put("bigIslandArea", bigIslandArea)
            .put("smallIslandArea", smallIslandArea)

        val baseInfo = JSONObject()
            .put("title", title)
            .put("content", body)
            .put("type", 2)

        val paramV2 = JSONObject()
            .put("business", "sms_forwarder")
            .put("timeout", -1)
            .put("filterWhenNoPermission", false)
            .put("aodTitle", title)
            .put("ticker", ticker)
            .put("param_island", paramIsland)
            .put("baseInfo", baseInfo)

        return JSONObject().put("param_v2", paramV2).toString()
    }

    companion object {
        private const val MIUI_FOCUS_PARAM = "miui.focus.param"
        private const val ISLAND_DISMISS_MS = 8_000L
        private const val ISLAND_TIMEOUT_SECONDS = 8
        private const val MAX_FIELD = 80
        private const val MAX_TICKER = 24
    }
}
