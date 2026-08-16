package org.fossify.messages.helpers

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony

object DeviceCompatHelper {

    enum class DeviceBrand {
        HUAWEI, HONOR, XIAOMI, REDMI, POCO, OPPO, ONEPLUS, REALME,
        VIVO, IQOO, SAMSUNG, PIXEL, OTHER
    }

    data class BrandConfig(
        val brand: DeviceBrand,
        val displayName: String,
        val hasAutoStartManager: Boolean = true,
        val hasBatteryOptimization: Boolean = true,
        val needsAdbFix: Boolean = false,
        val autoStartComponents: List<ComponentName> = emptyList(),
        val tips: List<String> = emptyList()
    )

    private val brandConfigs = mapOf(
        DeviceBrand.HUAWEI to BrandConfig(
            brand = DeviceBrand.HUAWEI,
            displayName = "华为",
            hasAutoStartManager = true,
            hasBatteryOptimization = true,
            needsAdbFix = true,
            autoStartComponents = listOf(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            ),
            tips = listOf(
                "允许「短信转发」权限",
                "设置 → 应用启动管理 → 短信转发 → 关闭自动管理",
                "打开「允许自启动」「允许关联启动」「允许后台活动」",
                "电池优化设为「不允许优化」",
                "最近任务中锁定App"
            )
        ),
        DeviceBrand.HONOR to BrandConfig(
            brand = DeviceBrand.HONOR,
            displayName = "荣耀",
            hasAutoStartManager = true,
            hasBatteryOptimization = true,
            needsAdbFix = true,
            autoStartComponents = listOf(
                ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            ),
            tips = listOf(
                "允许「短信转发」权限",
                "设置 → 应用启动管理 → 短信转发 → 关闭自动管理",
                "打开「允许自启动」「允许关联启动」「允许后台活动」",
                "电池优化设为「不允许优化」"
            )
        ),
        DeviceBrand.XIAOMI to BrandConfig(
            brand = DeviceBrand.XIAOMI,
            displayName = "小米",
            hasAutoStartManager = true,
            hasBatteryOptimization = true,
            needsAdbFix = false,
            autoStartComponents = listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                ComponentName("com.miui.securitycenter", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
            ),
            tips = listOf(
                "允许「短信转发」权限",
                "设置 → 应用设置 → 应用管理 → 短信转发 → 自启动",
                "省电策略改为「无限制」",
                "允许后台弹出界面",
                "开启通知权限",
                "允许锁屏显示通知"
            )
        ),
        DeviceBrand.REDMI to BrandConfig(
            brand = DeviceBrand.REDMI,
            displayName = "Redmi",
            hasAutoStartManager = true,
            hasBatteryOptimization = true,
            needsAdbFix = false,
            autoStartComponents = listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            ),
            tips = listOf(
                "允许「短信转发」权限",
                "设置 → 应用设置 → 应用管理 → 短信转发 → 自启动",
                "省电策略改为「无限制」",
                "允许后台弹出界面"
            )
        ),
        DeviceBrand.POCO to BrandConfig(
            brand = DeviceBrand.POCO,
            displayName = "POCO",
            hasAutoStartManager = true,
            hasBatteryOptimization = true,
            needsAdbFix = false,
            autoStartComponents = listOf(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            ),
            tips = listOf(
                "允许「短信转发」权限",
                "设置 → 应用设置 → 应用管理 → 短信转发 → 自启动",
                "省电策略改为「无限制」"
            )
        ),
        DeviceBrand.OPPO to BrandConfig(
            brand = DeviceBrand.OPPO,
            displayName = "OPPO",
            hasAutoStartManager = true,
            hasBatteryOptimization = true,
            needsAdbFix = false,
            autoStartComponents = listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppManagerActivity"),
                ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppManagerActivity")
            ),
            tips = listOf(
                "允许「短信转发」权限",
                "设置 → 应用管理 → 启动管理 → 短信转发 → 允许自启动",
                "允许后台运行",
                "关闭省电限制",
                "允许锁屏通知"
            )
        ),
        DeviceBrand.ONEPLUS to BrandConfig(
            brand = DeviceBrand.ONEPLUS,
            displayName = "OnePlus",
            hasAutoStartManager = true,
            hasBatteryOptimization = true,
            needsAdbFix = false,
            autoStartComponents = listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppManagerActivity")
            ),
            tips = listOf(
                "允许「短信转发」权限",
                "设置 → 应用管理 → 启动管理 → 短信转发 → 允许自启动",
                "允许后台运行"
            )
        ),
        DeviceBrand.REALME to BrandConfig(
            brand = DeviceBrand.REALME,
            displayName = "Realme",
            hasAutoStartManager = true,
            hasBatteryOptimization = true,
            needsAdbFix = false,
            autoStartComponents = listOf(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppManagerActivity")
            ),
            tips = listOf(
                "允许「短信转发」权限",
                "设置 → 应用管理 → 启动管理 → 短信转发 → 允许自启动",
                "允许后台运行",
                "关闭省电限制"
            )
        ),
        DeviceBrand.VIVO to BrandConfig(
            brand = DeviceBrand.VIVO,
            displayName = "vivo",
            hasAutoStartManager = true,
            hasBatteryOptimization = true,
            needsAdbFix = false,
            autoStartComponents = listOf(
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            ),
            tips = listOf(
                "允许「短信转发」权限",
                "i管家 → 应用管理 → 权限管理 → 自启动 → 短信转发",
                "允许后台高耗电",
                "电池优化白名单",
                "开启通知权限"
            )
        ),
        DeviceBrand.IQOO to BrandConfig(
            brand = DeviceBrand.IQOO,
            displayName = "iQOO",
            hasAutoStartManager = true,
            hasBatteryOptimization = true,
            needsAdbFix = false,
            autoStartComponents = listOf(
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            ),
            tips = listOf(
                "允许「短信转发」权限",
                "i管家 → 应用管理 → 权限管理 → 自启动 → 短信转发",
                "允许后台高耗电"
            )
        ),
        DeviceBrand.SAMSUNG to BrandConfig(
            brand = DeviceBrand.SAMSUNG,
            displayName = "三星",
            hasAutoStartManager = false,
            hasBatteryOptimization = true,
            needsAdbFix = false,
            tips = listOf(
                "允许「短信转发」权限",
                "设置 → 电池和设备维护 → 电池 → 后台使用限制 → 短信转发 → 不受限",
                "开启通知权限"
            )
        ),
        DeviceBrand.PIXEL to BrandConfig(
            brand = DeviceBrand.PIXEL,
            displayName = "Pixel",
            hasAutoStartManager = false,
            hasBatteryOptimization = true,
            needsAdbFix = false,
            tips = listOf(
                "允许「短信转发」权限",
                "设置 → 电池 → 电池优化 → 短信转发 → 不受限",
                "开启通知权限"
            )
        )
    )

    fun detectBrand(): DeviceBrand {
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        val model = Build.MODEL.lowercase()
        return when {
            manufacturer.contains("huawei") || brand.contains("huawei") -> DeviceBrand.HUAWEI
            manufacturer.contains("honor") || brand.contains("honor") -> DeviceBrand.HONOR
            manufacturer.contains("xiaomi") || brand.contains("xiaomi") -> DeviceBrand.XIAOMI
            brand.contains("redmi") -> DeviceBrand.REDMI
            brand.contains("poco") -> DeviceBrand.POCO
            manufacturer.contains("oppo") || brand.contains("oppo") -> DeviceBrand.OPPO
            manufacturer.contains("oneplus") || brand.contains("oneplus") -> DeviceBrand.ONEPLUS
            manufacturer.contains("realme") || brand.contains("realme") -> DeviceBrand.REALME
            brand.contains("iqoo") || model.contains("iqoo") -> DeviceBrand.IQOO
            manufacturer.contains("vivo") || brand.contains("vivo") -> DeviceBrand.VIVO
            manufacturer.contains("samsung") || brand.contains("samsung") -> DeviceBrand.SAMSUNG
            manufacturer.contains("google") || brand.contains("google") || brand.contains("pixel") -> DeviceBrand.PIXEL
            else -> DeviceBrand.OTHER
        }
    }

    fun getBrandConfig(brand: DeviceBrand = detectBrand()): BrandConfig {
        return brandConfigs[brand] ?: BrandConfig(
            brand = DeviceBrand.OTHER,
            displayName = Build.MANUFACTURER,
            hasAutoStartManager = false,
            hasBatteryOptimization = true,
            needsAdbFix = false,
            tips = listOf(
                "允许「短信转发」权限",
                "在应用设置中允许自启动和后台运行",
                "电池优化设为「不优化」或「无限制」",
                "开启通知权限"
            )
        )
    }

    fun getDeviceDescription(): String {
        return "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}"
    }

    fun isDefaultSmsApp(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = context.getSystemService(android.app.role.RoleManager::class.java)
            roleManager?.isRoleHeld(android.app.role.RoleManager.ROLE_SMS) == true
        } else {
            Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
        }
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isNotificationEnabled(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        return notificationManager.areNotificationsEnabled()
    }

    fun openAutoStartSettings(context: Context, brand: DeviceBrand = detectBrand()): Boolean {
        val config = getBrandConfig(brand)
        if (!config.hasAutoStartManager) return false

        return config.autoStartComponents.any { component ->
            runCatching {
                context.startActivity(Intent().apply { this.component = component })
                true
            }.getOrDefault(false)
        }
    }

    fun openBatterySettings(context: Context, brand: DeviceBrand = detectBrand()): Boolean {
        val config = getBrandConfig(brand)
        if (!config.hasBatteryOptimization) return false

        return runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            true
        }.getOrDefault(false)
    }

    fun openAppDetails(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
        )
    }

    fun getAdbFixCommand(packageName: String): String {
        val brand = detectBrand()
        return if (brand == DeviceBrand.HUAWEI || brand == DeviceBrand.HONOR) {
            "adb shell settings --user 0 put secure sms_default_application $packageName"
        } else {
            "adb shell pm set-home-activity $packageName/.activities.MainActivity"
        }
    }
}
