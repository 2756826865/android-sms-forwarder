package org.fossify.messages.helpers.liveisland

import android.os.Build
import org.fossify.messages.helpers.DeviceCompatHelper
import org.fossify.messages.helpers.DeviceCompatHelper.DeviceBrand

object RomDetect {

    fun getSystemProperty(key: String): String? {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            val value = method.invoke(null, key, "") as String
            value.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    fun isXiaomiFamily(): Boolean {
        return DeviceCompatHelper.detectBrand() in setOf(
            DeviceBrand.XIAOMI,
            DeviceBrand.REDMI,
            DeviceBrand.POCO,
        )
    }

    fun isOppoFamily(): Boolean {
        return DeviceCompatHelper.detectBrand() in setOf(
            DeviceBrand.OPPO,
            DeviceBrand.ONEPLUS,
            DeviceBrand.REALME,
        )
    }

    fun isVivoFamily(): Boolean {
        return DeviceCompatHelper.detectBrand() in setOf(
            DeviceBrand.VIVO,
            DeviceBrand.IQOO,
        )
    }

    /** HyperOS 2+ uses focus notifications; HyperOS 3 adds Super Island UI. */
    fun supportsXiaomiHyperIsland(): Boolean {
        if (!isXiaomiFamily()) {
            return false
        }
        val versionCode = getSystemProperty("ro.mi.os.version.code")?.toIntOrNull()
        if (versionCode != null) {
            return versionCode >= 2
        }
        val versionName = getSystemProperty("ro.mi.os.version.name").orEmpty().lowercase()
        if (versionName.contains("os3") || versionName.contains("os2")) {
            return true
        }
        // MIUI/HyperOS devices without readable properties may still accept miui.focus.param.
        return getSystemProperty("ro.miui.ui.version.name") != null
    }

    fun getColorOsMajorVersion(): Int? {
        val raw = getSystemProperty("ro.build.version.opporom")
            ?: getSystemProperty("ro.oplus.version")
            ?: return null
        val match = Regex("""V(\d+)""", RegexOption.IGNORE_CASE).find(raw)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    fun supportsOppoFluidCloud(): Boolean {
        if (!isOppoFamily()) {
            return false
        }
        val major = getColorOsMajorVersion() ?: return false
        return major >= 15
    }

    fun supportsAndroidLiveUpdate(): Boolean {
        return Build.VERSION.SDK_INT >= 36
    }

    fun getOriginOsMajorVersion(): Int? {
        val raw = getSystemProperty("ro.vivo.os.version")
            ?: getSystemProperty("ro.vivo.os.build.display.id")
            ?: return null
        val match = Regex("""(?:originos|os)[^\d]*(\d+)""", RegexOption.IGNORE_CASE).find(raw)
            ?: Regex("""V(\d+)""", RegexOption.IGNORE_CASE).find(raw)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    /** Reserved for future vivo Atomic Island integration. */
    fun supportsVivoAtomicIsland(): Boolean = false
}
