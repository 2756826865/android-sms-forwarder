package org.fossify.messages.helpers.liveisland

import android.content.Context
import androidx.core.app.NotificationCompat
import org.fossify.messages.extensions.config

object LiveIslandCoordinator {

    private val providers: List<LiveIslandProvider> = listOf(
        XiaomiHyperIslandProvider(),
        OppoFluidCloudProvider(),
        AndroidLiveUpdateProvider(),
        VivoAtomicIslandProvider(),
    )

    fun resolveActiveKind(context: Context): LiveIslandKind? {
        return providers.firstOrNull { it.isSupported(context) }?.kind
    }

    fun isAnySupported(context: Context): Boolean {
        if (!context.config.enableLiveIsland) {
            return false
        }
        return providers.any { it.isSupported(context) } || isVivoReserved(context)
    }

    fun isVivoReserved(context: Context): Boolean {
        return RomDetect.isVivoFamily() && RomDetect.getOriginOsMajorVersion()?.let { it >= 5 } == true
    }

    fun getStatusLabel(context: Context): String {
        if (!context.config.enableLiveIsland) {
            return context.getString(org.fossify.messages.R.string.live_island_status_disabled)
        }
        return when (resolveActiveKind(context)) {
            LiveIslandKind.XIAOMI_HYPER_ISLAND ->
                context.getString(org.fossify.messages.R.string.live_island_status_xiaomi)
            LiveIslandKind.OPPO_FLUID_CLOUD ->
                context.getString(org.fossify.messages.R.string.live_island_status_oppo)
            LiveIslandKind.ANDROID_LIVE_UPDATE ->
                context.getString(org.fossify.messages.R.string.live_island_status_android)
            LiveIslandKind.VIVO_ATOMIC_ISLAND ->
                context.getString(org.fossify.messages.R.string.live_island_status_vivo)
            null -> when {
                isVivoReserved(context) ->
                    context.getString(org.fossify.messages.R.string.live_island_status_vivo_reserved)
                else ->
                    context.getString(org.fossify.messages.R.string.live_island_status_unsupported)
            }
        }
    }

    fun applyToBuilder(
        context: Context,
        builder: NotificationCompat.Builder,
        message: LiveIslandMessage,
    ): LiveIslandKind? {
        if (!context.config.enableLiveIsland) {
            return null
        }
        val provider = providers.firstOrNull { it.isSupported(context) } ?: return null
        provider.applyToBuilder(context, builder, message)
        return provider.kind
    }

    fun afterNotify(context: Context, message: LiveIslandMessage, kind: LiveIslandKind?) {
        if (kind == null || !context.config.enableLiveIsland) {
            return
        }
        providers.firstOrNull { it.kind == kind }?.afterNotify(context, message)
    }
}
