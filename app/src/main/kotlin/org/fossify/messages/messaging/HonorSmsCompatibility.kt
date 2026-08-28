package org.fossify.messages.messaging

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.security.MessageDigest

/** Conservative SMS safeguards for Honor devices that reboot inside the vendor IMS stack. */
object HonorSmsCompatibility {
    private const val PREFS_NAME = "honor_sms_send_guard"
    private const val KEY_PREFIX = "pending_"
    private const val ENTRY_SEPARATOR = '|'
    private const val SAME_BOOT_GUARD_MS = 2 * 60 * 1000L
    private const val REBOOT_GUARD_MS = 30 * 60 * 1000L

    val isAffectedDevice: Boolean
        get() {
            val manufacturer = Build.MANUFACTURER.lowercase()
            val brand = Build.BRAND.lowercase()
            return manufacturer.contains("honor") ||
                manufacturer.contains("hihonor") ||
                brand.contains("honor") ||
                brand.contains("hihonor") ||
                Build.MODEL.equals("MAG-AN00", ignoreCase = true)
        }

    /**
     * Returns a guard key when the send may proceed, or null when an unfinished identical send
     * must not be submitted again. The entry survives a device reboot and expires automatically.
     */
    @Synchronized
    fun claim(context: Context, subId: Int, destination: String, body: String): String? {
        if (!isAffectedDevice) return ""

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
    fun complete(context: Context, guardKey: String?) {
        if (guardKey.isNullOrBlank()) return
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(guardKey)
            .apply()
    }

    private fun readBootCount(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT)
    }.getOrDefault(-1)

    private fun removeExpiredEntries(
        entries: Map<String, *>,
        editor: android.content.SharedPreferences.Editor,
        now: Long,
    ) {
        var changed = false
        entries.forEach { (key, value) ->
            if (!key.startsWith(KEY_PREFIX)) return@forEach
            val timestamp = (value as? String)?.substringBefore(ENTRY_SEPARATOR)?.toLongOrNull()
            if (timestamp == null || now - timestamp !in 0 until REBOOT_GUARD_MS) {
                editor.remove(key)
                changed = true
            }
        }
        if (changed) editor.apply()
    }

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
