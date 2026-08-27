package org.fossify.messages.compatibility

import android.content.Context
import android.database.Cursor
import android.provider.Settings
import org.fossify.messages.compatibility.model.DeviceCapability
import org.fossify.messages.compatibility.model.DeviceProfile
import java.security.MessageDigest

class SmsProviderCompat(private val profile: DeviceProfile) {
    private val PREFS_NAME = "honor_sms_send_guard"
    private val KEY_PREFIX = "pending_"
    private val ENTRY_SEPARATOR = '|'
    private val SAME_BOOT_GUARD_MS = 2 * 60 * 1000L
    private val REBOOT_GUARD_MS = 30 * 60 * 1000L

    /**
     * 针对不同 OEM ROM 规范化 SIM 卡列名索引
     */
    fun normalizeSimColumnIndex(cursor: Cursor): Int {
        val candidates = listOf("sub_id", "subscription_id", "sim_id", "slot_id", "sim_slot")
        for (col in candidates) {
            val idx = cursor.getColumnIndex(col)
            if (idx >= 0) return idx
        }
        return -1
    }

    /**
     * 荣耀特定机型开机 IMS 重启保护
     */
    @Synchronized
    fun claimHonorImsGuard(context: Context, subId: Int, destination: String, body: String): String? {
        if (!profile.hasCapability(DeviceCapability.REQUIRES_HONOR_IMS_GUARD)) return ""

        val now = System.currentTimeMillis()
        val bootCount = readBootCount(context)
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        removeExpiredEntries(prefs.all, prefs.edit(), now)

        val key = KEY_PREFIX + digest("$subId\u0000$destination\u0000$body")
        val existing = prefs.getString(key, null)?.split(ENTRY_SEPARATOR)
        val previousTime = existing?.getOrNull(0)?.toLongOrNull()
        val previousBootCount = existing?.getOrNull(1)?.toIntOrNull()
        if (previousTime != null) {
            val age = now - previousTime
            val guardWindow = if (previousBootCount != null && previousBootCount != bootCount) {
                REBOOT_GUARD_MS
            } else {
                SAME_BOOT_GUARD_MS
            }
            if (age in 0 until guardWindow) return null
        }

        prefs.edit().putString(key, "$now$ENTRY_SEPARATOR$bootCount").apply()
        return key
    }

    @Synchronized
    fun completeHonorImsGuard(context: Context, guardKey: String?) {
        if (guardKey.isNullOrBlank()) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(guardKey).apply()
    }

    private fun readBootCount(context: Context): Int {
        return runCatching {
            Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, 0)
        }.getOrDefault(0)
    }

    private fun removeExpiredEntries(
        all: Map<String, *>,
        editor: android.content.SharedPreferences.Editor,
        now: Long
    ) {
        for ((key, value) in all) {
            if (!key.startsWith(KEY_PREFIX)) continue
            val parts = (value as? String)?.split(ENTRY_SEPARATOR)
            val time = parts?.getOrNull(0)?.toLongOrNull() ?: 0L
            if (now - time > REBOOT_GUARD_MS) {
                editor.remove(key)
            }
        }
        editor.apply()
    }

    private fun digest(input: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
