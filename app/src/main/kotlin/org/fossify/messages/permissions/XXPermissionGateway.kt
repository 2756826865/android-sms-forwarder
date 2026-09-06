package org.fossify.messages.permissions

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.XXPermissions
import com.hjq.permissions.permission.PermissionLists
import com.hjq.permissions.permission.base.IPermission
import kotlinx.coroutines.suspendCancellableCoroutine
import org.fossify.messages.helpers.DeviceCompatHelper
import kotlin.coroutines.resume

object XXPermissionGateway : PermissionGateway {

    private fun getIPermissions(capability: PermissionCapability): List<IPermission> = when (capability) {
        PermissionCapability.RECEIVE_SMS -> listOf(PermissionLists.getReceiveSmsPermission())
        PermissionCapability.READ_SMS -> listOf(PermissionLists.getReadSmsPermission())
        PermissionCapability.SEND_SMS -> listOf(PermissionLists.getSendSmsPermission())
        PermissionCapability.READ_CONTACTS -> listOf(PermissionLists.getReadContactsPermission())
        PermissionCapability.READ_SIM_INFO -> listOf(PermissionLists.getReadPhoneStatePermission())
        PermissionCapability.MAKE_CALL -> listOf(PermissionLists.getCallPhonePermission())
        PermissionCapability.SHOW_NOTIFICATIONS -> listOf(PermissionLists.getPostNotificationsPermission())
    }

    override suspend fun check(context: Context, capability: PermissionCapability): Boolean {
        val permissions = getIPermissions(capability)
        if (permissions.isEmpty()) return true
        return XXPermissions.isGrantedPermissions(context, permissions)
    }

    override suspend fun request(
        activity: Activity,
        capability: PermissionCapability,
        rationale: PermissionRationale?
    ): PermissionResult {
        if (activity.isFinishing || activity.isDestroyed) {
            return PermissionResult.Unsupported("Activity is finishing or destroyed")
        }

        val perms = getIPermissions(capability)
        if (perms.isEmpty()) return PermissionResult.Granted
        if (check(activity, capability)) return PermissionResult.Granted

        return suspendCancellableCoroutine { continuation ->
            XXPermissions.with(activity)
                .permissions(perms)
                .request(object : OnPermissionCallback {
                    override fun onPermissionResult(
                        grantedList: MutableList<IPermission>,
                        deniedList: MutableList<IPermission>
                    ) {
                        val allGranted = deniedList.isEmpty()
                        if (allGranted) {
                            if (continuation.isActive) continuation.resume(PermissionResult.Granted)
                        } else {
                            val deniedNames = deniedList.map { it.permissionName }
                            val grantedNames = grantedList.map { it.permissionName }
                            val isDoNotAskAgain = XXPermissions.isDoNotAskAgainPermissions(activity, deniedList)
                            if (continuation.isActive) {
                                if (grantedList.isNotEmpty()) {
                                    continuation.resume(PermissionResult.PartiallyGranted(grantedNames, deniedNames))
                                } else {
                                    continuation.resume(PermissionResult.Denied(deniedNames, isDoNotAskAgain))
                                }
                            }
                        }
                    }
                })
        }
    }

    override fun openAppPermissionSettings(activity: Activity) {
        runCatching {
            XXPermissions.startPermissionActivity(activity)
        }.onFailure {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", activity.packageName, null)
            }
            activity.startActivity(intent)
        }
    }

    override fun requestDefaultSmsRole(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = activity.getSystemService(RoleManager::class.java)
            if (roleManager?.isRoleAvailable(RoleManager.ROLE_SMS) == true && !roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                activity.startActivity(intent)
            }
        } else {
            if (Telephony.Sms.getDefaultSmsPackage(activity) != activity.packageName) {
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
                    putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, activity.packageName)
                }
                activity.startActivity(intent)
            }
        }
    }

    override fun requestIgnoreBatteryOptimization(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun requestOverlayAccess(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${activity.packageName}")
                )
                activity.startActivity(intent)
            }
        }
    }

    override fun openAccessibilitySettings(context: Context) {
        runCatching {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }

    override fun openAutoStartSettings(context: Context) {
        DeviceCompatHelper.openAutoStartSettings(context)
    }
}
