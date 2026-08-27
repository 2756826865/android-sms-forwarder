package org.fossify.messages.compatibility

import android.os.Build
import org.fossify.messages.compatibility.model.DeviceBrand
import org.fossify.messages.compatibility.model.DeviceCapability
import org.fossify.messages.compatibility.model.DeviceProfile

/**
 * 统一 OEM 兼容层门面
 *
 * 职责：
 * 1. 唯一设备画像与能力探测出口；
 * 2. 严禁业务层直接访问 Build.MANUFACTURER / Build.BRAND；
 * 3. 驱动后台保活、权限引导与短信 Provider 兼容。
 */
object CompatibilityManager {

    val deviceProfile: DeviceProfile by lazy {
        detectDeviceProfile()
    }

    val backgroundCompat: BackgroundExecutionCompat by lazy {
        BackgroundExecutionCompat(deviceProfile)
    }

    val smsProviderCompat: SmsProviderCompat by lazy {
        SmsProviderCompat(deviceProfile)
    }

    fun buildCustomProfile(
        brand: DeviceBrand,
        manufacturer: String = Build.MANUFACTURER,
        model: String = Build.MODEL,
        apiLevel: Int = Build.VERSION.SDK_INT,
        romName: String = "",
        romVersion: String = ""
    ): DeviceProfile {
        val capabilities = detectCapabilities(brand, manufacturer, model, apiLevel)
        return DeviceProfile(
            brand = brand,
            manufacturer = manufacturer,
            model = model,
            apiLevel = apiLevel,
            romName = romName,
            romVersion = romVersion,
            capabilities = capabilities
        )
    }

    private fun detectDeviceProfile(): DeviceProfile {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brandStr = Build.BRAND.lowercase()
        val model = Build.MODEL

        val brand = when {
            manufacturer.contains("huawei") || brandStr.contains("huawei") -> DeviceBrand.HUAWEI
            manufacturer.contains("honor") || brandStr.contains("honor") || model.equals("MAG-AN00", ignoreCase = true) -> DeviceBrand.HONOR
            manufacturer.contains("xiaomi") || brandStr.contains("xiaomi") -> DeviceBrand.XIAOMI
            brandStr.contains("redmi") -> DeviceBrand.REDMI
            brandStr.contains("poco") -> DeviceBrand.POCO
            manufacturer.contains("oppo") || brandStr.contains("oppo") -> DeviceBrand.OPPO
            manufacturer.contains("oneplus") || brandStr.contains("oneplus") -> DeviceBrand.ONEPLUS
            manufacturer.contains("realme") || brandStr.contains("realme") -> DeviceBrand.REALME
            brandStr.contains("iqoo") || model.lowercase().contains("iqoo") -> DeviceBrand.IQOO
            manufacturer.contains("vivo") || brandStr.contains("vivo") -> DeviceBrand.VIVO
            manufacturer.contains("samsung") || brandStr.contains("samsung") -> DeviceBrand.SAMSUNG
            manufacturer.contains("google") || brandStr.contains("google") || brandStr.contains("pixel") -> DeviceBrand.PIXEL
            else -> DeviceBrand.AOSP
        }

        val capabilities = detectCapabilities(brand, manufacturer, model, Build.VERSION.SDK_INT)
        return DeviceProfile(
            brand = brand,
            manufacturer = Build.MANUFACTURER,
            model = model,
            apiLevel = Build.VERSION.SDK_INT,
            romName = detectRomName(brand),
            romVersion = Build.VERSION.RELEASE,
            capabilities = capabilities
        )
    }

    private fun detectCapabilities(
        brand: DeviceBrand,
        manufacturer: String,
        model: String,
        apiLevel: Int
    ): Set<DeviceCapability> {
        val caps = mutableSetOf<DeviceCapability>()

        // 1. 自启动引导需求
        if (brand in setOf(
                DeviceBrand.HUAWEI,
                DeviceBrand.HONOR,
                DeviceBrand.XIAOMI,
                DeviceBrand.REDMI,
                DeviceBrand.POCO,
                DeviceBrand.OPPO,
                DeviceBrand.ONEPLUS,
                DeviceBrand.REALME,
                DeviceBrand.VIVO,
                DeviceBrand.IQOO
            )
        ) {
            caps.add(DeviceCapability.REQUIRES_AUTORUN_GUIDE)
        }

        // 2. 电池优化忽略需求
        caps.add(DeviceCapability.REQUIRES_BATTERY_WHITELIST)

        // 3. 荣耀特定机型 IMS 崩溃保护
        if (brand == DeviceBrand.HONOR || model.equals("MAG-AN00", ignoreCase = true) || manufacturer.contains("honor")) {
            caps.add(DeviceCapability.REQUIRES_HONOR_IMS_GUARD)
        }

        // 4. Android 13+ 运行时通知权限
        if (apiLevel >= 33) {
            caps.add(DeviceCapability.REQUIRES_POST_NOTIFICATIONS)
        }

        // 5. 双卡直发路由能力
        caps.add(DeviceCapability.SUPPORTS_DIRECT_SIM_ROUTING)

        return caps
    }

    private fun detectRomName(brand: DeviceBrand): String = when (brand) {
        DeviceBrand.XIAOMI, DeviceBrand.REDMI, DeviceBrand.POCO -> "HyperOS/MIUI"
        DeviceBrand.HUAWEI -> "HarmonyOS/EMUI"
        DeviceBrand.HONOR -> "MagicOS"
        DeviceBrand.OPPO, DeviceBrand.ONEPLUS, DeviceBrand.REALME -> "ColorOS"
        DeviceBrand.VIVO, DeviceBrand.IQOO -> "OriginOS/FuntouchOS"
        DeviceBrand.SAMSUNG -> "OneUI"
        DeviceBrand.PIXEL, DeviceBrand.AOSP -> "AOSP"
        else -> "Android"
    }
}
