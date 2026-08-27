package org.fossify.messages.compatibility.model

enum class DeviceBrand {
    HUAWEI,
    HONOR,
    XIAOMI,
    REDMI,
    POCO,
    OPPO,
    ONEPLUS,
    REALME,
    VIVO,
    IQOO,
    SAMSUNG,
    PIXEL,
    AOSP,
    OTHER
}

enum class DeviceCapability {
    REQUIRES_AUTORUN_GUIDE,
    REQUIRES_BATTERY_WHITELIST,
    REQUIRES_HONOR_IMS_GUARD,
    SUPPORTS_DIRECT_SIM_ROUTING,
    REQUIRES_POST_NOTIFICATIONS
}

data class DeviceProfile(
    val brand: DeviceBrand,
    val manufacturer: String,
    val model: String,
    val apiLevel: Int,
    val romName: String,
    val romVersion: String,
    val capabilities: Set<DeviceCapability>
) {
    fun hasCapability(capability: DeviceCapability): Boolean = capabilities.contains(capability)
}
