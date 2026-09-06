package org.fossify.messages.permissions

import android.app.Activity
import android.content.Context

interface PermissionGateway {
    suspend fun check(context: Context, capability: PermissionCapability): Boolean
    suspend fun request(activity: Activity, capability: PermissionCapability, rationale: PermissionRationale? = null): PermissionResult
    fun openAppPermissionSettings(activity: Activity)

    fun requestDefaultSmsRole(activity: Activity)
    fun requestIgnoreBatteryOptimization(context: Context)
    fun requestOverlayAccess(activity: Activity)
    fun openAccessibilitySettings(context: Context)
    fun openAutoStartSettings(context: Context)
}
