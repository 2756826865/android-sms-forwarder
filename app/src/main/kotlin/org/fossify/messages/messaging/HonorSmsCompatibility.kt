package org.fossify.messages.messaging

import android.content.Context
import org.fossify.messages.compatibility.CompatibilityManager
import org.fossify.messages.compatibility.model.DeviceCapability

/**
 * 荣耀设备 IMS 发送安全守卫
 * 已委托给 CompatibilityManager.smsProviderCompat 统一管理
 */
object HonorSmsCompatibility {

    val isAffectedDevice: Boolean
        get() = CompatibilityManager.deviceProfile.hasCapability(DeviceCapability.REQUIRES_HONOR_IMS_GUARD)

    fun claim(context: Context, subId: Int, destination: String, body: String): String? {
        return CompatibilityManager.smsProviderCompat.claimHonorImsGuard(context, subId, destination, body)
    }

    fun complete(context: Context, guardKey: String?) {
        CompatibilityManager.smsProviderCompat.completeHonorImsGuard(context, guardKey)
    }
}
