package org.fossify.messages.compatibility

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import org.fossify.messages.compatibility.model.DeviceBrand
import org.fossify.messages.compatibility.model.DeviceCapability
import org.fossify.messages.compatibility.model.DeviceProfile

class BackgroundExecutionCompat(private val profile: DeviceProfile) {

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isNotificationEnabled(context: Context): Boolean {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return false
        return notificationManager.areNotificationsEnabled()
    }

    fun openAutoStartSettings(context: Context): Boolean {
        if (!profile.hasCapability(DeviceCapability.REQUIRES_AUTORUN_GUIDE)) return false
        val components = getAutoStartComponents(profile.brand)
        return components.any { component ->
            runCatching {
                context.startActivity(Intent().apply {
                    this.component = component
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                true
            }.getOrDefault(false)
        }
    }

    fun openBatterySettings(context: Context): Boolean {
        return runCatching {
            context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            true
        }.getOrDefault(false)
    }

    fun openAppDetails(context: Context) {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }

    fun getBrandTips(): List<String> = when (profile.brand) {
        DeviceBrand.HUAWEI -> listOf(
            "允许「短信转发」权限",
            "设置 → 应用启动管理 → 短信转发 → 关闭自动管理",
            "打开「允许自启动」「允许关联启动」「允许后台活动」",
            "电池优化设为「不允许优化」",
            "最近任务中锁定App"
        )
        DeviceBrand.HONOR -> listOf(
            "允许「短信转发」权限",
            "设置 → 应用启动管理 → 短信转发 → 关闭自动管理",
            "打开「允许自启动」「允许关联启动」「允许后台活动」",
            "电池优化设为「不允许优化」"
        )
        DeviceBrand.XIAOMI, DeviceBrand.REDMI, DeviceBrand.POCO -> listOf(
            "允许「短信转发」权限",
            "设置 → 应用设置 → 应用管理 → 短信转发 → 自启动",
            "省电策略改为「无限制」",
            "允许后台弹出界面",
            "开启通知权限",
            "允许锁屏显示通知"
        )
        DeviceBrand.OPPO, DeviceBrand.ONEPLUS, DeviceBrand.REALME -> listOf(
            "允许「短信转发」权限",
            "设置 → 应用管理 → 启动管理 → 短信转发 → 允许自启动",
            "允许后台运行",
            "关闭省电限制",
            "允许锁屏通知"
        )
        DeviceBrand.VIVO, DeviceBrand.IQOO -> listOf(
            "允许「短信转发」权限",
            "设置 → 应用与权限 → 权限管理 → 自启动 → 短信转发 → 开启",
            "电池 → 后台高耗电 → 允许短信转发高耗电运行",
            "允许锁屏显示通知"
        )
        DeviceBrand.SAMSUNG -> listOf(
            "允许「短信转发」权限",
            "设置 → 电池和设备维护 → 电池 → 后台使用限制 → 短信转发 → 不受限",
            "开启通知权限"
        )
        DeviceBrand.PIXEL, DeviceBrand.AOSP -> listOf(
            "允许「短信转发」权限",
            "设置 → 电池 → 电池优化 → 短信转发 → 不受限",
            "开启通知权限"
        )
        DeviceBrand.OTHER -> listOf(
            "允许「短信转发」权限",
            "在应用设置中允许自启动和后台运行",
            "电池优化设为「不优化」或「无限制」",
            "开启通知权限"
        )
    }

    private fun getAutoStartComponents(brand: DeviceBrand): List<ComponentName> = when (brand) {
        DeviceBrand.HUAWEI -> listOf(
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
        )
        DeviceBrand.HONOR -> listOf(
            ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
        )
        DeviceBrand.XIAOMI, DeviceBrand.REDMI, DeviceBrand.POCO -> listOf(
            ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            ComponentName("com.miui.securitycenter", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
        )
        DeviceBrand.OPPO, DeviceBrand.ONEPLUS, DeviceBrand.REALME -> listOf(
            ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppManagerActivity"),
            ComponentName("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppManagerActivity")
        )
        DeviceBrand.VIVO, DeviceBrand.IQOO -> listOf(
            ComponentName("com.iqoo.secure", "com.iqoo.secure.MainGuideActivity"),
            ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
        )
        else -> emptyList()
    }
}
