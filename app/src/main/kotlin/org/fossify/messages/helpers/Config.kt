package org.fossify.messages.helpers

import android.content.Context
import org.fossify.commons.helpers.BaseConfig
import org.fossify.messages.extensions.getDefaultKeyboardHeight
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.models.Conversation

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    fun saveUseSIMIdAtNumber(number: String, SIMId: Int) {
        prefs.edit().putInt(USE_SIM_ID_PREFIX + number, SIMId).apply()
    }

    fun getUseSIMIdAtNumber(number: String) = prefs.getInt(USE_SIM_ID_PREFIX + number, 0)

    var showHomeBottomNavigation: Boolean
        get() = prefs.getBoolean(SHOW_HOME_BOTTOM_NAVIGATION, true)
        set(value) = prefs.edit().putBoolean(SHOW_HOME_BOTTOM_NAVIGATION, value).apply()

    /** Approximate visible conversation rows on the home list: 4 / 6 / 8 / 10. Default 6. */
    var homeListDensity: Int
        get() = prefs.getInt(HOME_LIST_DENSITY, HOME_LIST_DENSITY_6).let { value ->
            when (value) {
                HOME_LIST_DENSITY_4,
                HOME_LIST_DENSITY_6,
                HOME_LIST_DENSITY_8,
                HOME_LIST_DENSITY_10,
                -> value
                else -> HOME_LIST_DENSITY_6
            }
        }
        set(value) = prefs.edit().putInt(
            HOME_LIST_DENSITY,
            when (value) {
                HOME_LIST_DENSITY_4,
                HOME_LIST_DENSITY_6,
                HOME_LIST_DENSITY_8,
                HOME_LIST_DENSITY_10,
                -> value
                else -> HOME_LIST_DENSITY_6
            },
        ).apply()

    var firstUseNoticeAccepted: Boolean
        get() = prefs.getBoolean(FIRST_USE_NOTICE_ACCEPTED, false)
        set(value) = prefs.edit().putBoolean(FIRST_USE_NOTICE_ACCEPTED, value).apply()

    var enableLiveIsland: Boolean
        get() = prefs.getBoolean(ENABLE_LIVE_ISLAND, false)
        set(value) = prefs.edit().putBoolean(ENABLE_LIVE_ISLAND, value).apply()

    var showCharacterCounter: Boolean
        get() = prefs.getBoolean(SHOW_CHARACTER_COUNTER, false)
        set(showCharacterCounter) = prefs.edit()
            .putBoolean(SHOW_CHARACTER_COUNTER, showCharacterCounter).apply()

    var useSimpleCharacters: Boolean
        get() = prefs.getBoolean(USE_SIMPLE_CHARACTERS, false)
        set(useSimpleCharacters) = prefs.edit()
            .putBoolean(USE_SIMPLE_CHARACTERS, useSimpleCharacters).apply()

    var sendOnEnter: Boolean
        get() = prefs.getBoolean(SEND_ON_ENTER, false)
        set(sendOnEnter) = prefs.edit().putBoolean(SEND_ON_ENTER, sendOnEnter).apply()

    var enableDeliveryReports: Boolean
        get() = prefs.getBoolean(ENABLE_DELIVERY_REPORTS, false)
        set(enableDeliveryReports) = prefs.edit()
            .putBoolean(ENABLE_DELIVERY_REPORTS, enableDeliveryReports).apply()

    var sendLongMessageMMS: Boolean
        get() = prefs.getBoolean(SEND_LONG_MESSAGE_MMS, false)
        set(sendLongMessageMMS) = prefs.edit().putBoolean(SEND_LONG_MESSAGE_MMS, sendLongMessageMMS)
            .apply()

    var sendGroupMessageMMS: Boolean
        get() = prefs.getBoolean(SEND_GROUP_MESSAGE_MMS, false)
        set(sendGroupMessageMMS) = prefs.edit()
            .putBoolean(SEND_GROUP_MESSAGE_MMS, sendGroupMessageMMS).apply()

    val isGroupMessageMmsPreferenceResolved: Boolean
        get() = prefs.contains(SEND_GROUP_MESSAGE_MMS)

    var lockScreenVisibilitySetting: Int
        get() = prefs.getInt(LOCK_SCREEN_VISIBILITY, LOCK_SCREEN_SENDER_MESSAGE)
        set(lockScreenVisibilitySetting) = prefs.edit()
            .putInt(LOCK_SCREEN_VISIBILITY, lockScreenVisibilitySetting).apply()

    var mmsFileSizeLimit: Long
        get() = prefs.getLong(MMS_FILE_SIZE_LIMIT, FILE_SIZE_200_KB)
        set(mmsFileSizeLimit) = prefs.edit().putLong(MMS_FILE_SIZE_LIMIT, mmsFileSizeLimit).apply()

    var pinnedConversations: Set<String>
        get() = prefs.getStringSet(PINNED_CONVERSATIONS, HashSet<String>())!!
        set(pinnedConversations) = prefs.edit()
            .putStringSet(PINNED_CONVERSATIONS, pinnedConversations).apply()

    fun addPinnedConversationByThreadId(threadId: Long) {
        pinnedConversations = pinnedConversations.plus(threadId.toString())
    }

    fun addPinnedConversations(conversations: List<Conversation>) {
        pinnedConversations = pinnedConversations.plus(conversations.map { it.threadId.toString() })
    }

    fun removePinnedConversationByThreadId(threadId: Long) {
        pinnedConversations = pinnedConversations.minus(threadId.toString())
    }

    fun removePinnedConversations(conversations: List<Conversation>) {
        pinnedConversations =
            pinnedConversations.minus(conversations.map { it.threadId.toString() })
    }

    var blockedKeywords: Set<String>
        get() = prefs.getStringSet(BLOCKED_KEYWORDS, HashSet<String>())!!
        set(blockedKeywords) = prefs.edit().putStringSet(BLOCKED_KEYWORDS, blockedKeywords).apply()

    fun addBlockedKeyword(keyword: String) {
        blockedKeywords = blockedKeywords.plus(keyword)
    }

    fun removeBlockedKeyword(keyword: String) {
        blockedKeywords = blockedKeywords.minus(keyword)
    }

    var exportSms: Boolean
        get() = prefs.getBoolean(EXPORT_SMS, true)
        set(exportSms) = prefs.edit().putBoolean(EXPORT_SMS, exportSms).apply()

    var exportMms: Boolean
        get() = prefs.getBoolean(EXPORT_MMS, true)
        set(exportMms) = prefs.edit().putBoolean(EXPORT_MMS, exportMms).apply()

    var importSms: Boolean
        get() = prefs.getBoolean(IMPORT_SMS, true)
        set(importSms) = prefs.edit().putBoolean(IMPORT_SMS, importSms).apply()

    var importMms: Boolean
        get() = prefs.getBoolean(IMPORT_MMS, true)
        set(importMms) = prefs.edit().putBoolean(IMPORT_MMS, importMms).apply()

    var wasDbCleared: Boolean
        get() = prefs.getBoolean(WAS_DB_CLEARED, false)
        set(wasDbCleared) = prefs.edit().putBoolean(WAS_DB_CLEARED, wasDbCleared).apply()

    var fullHistorySyncedV2: Boolean
        get() = prefs.getBoolean("full_history_synced_v2", false)
        set(value) = prefs.edit().putBoolean("full_history_synced_v2", value).apply()

    var showListAvatars: Boolean
        get() = prefs.getBoolean("show_list_avatars", true)
        set(value) = prefs.edit().putBoolean("show_list_avatars", value).apply()

    var showLetterAvatars: Boolean
        get() = prefs.getBoolean("show_letter_avatars", false)
        set(value) = prefs.edit().putBoolean("show_letter_avatars", value).apply()

    var bulkSendDelaySeconds: Int
        get() = prefs.getInt("bulk_send_delay_seconds", 1).coerceIn(0, 5)
        set(value) = prefs.edit().putInt("bulk_send_delay_seconds", value.coerceIn(0, 5)).apply()

    var whitelistedNumbers: Set<String>
        get() = prefs.getStringSet("whitelisted_numbers", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("whitelisted_numbers", value).apply()

    fun addWhitelistedNumber(number: String) {
        whitelistedNumbers = whitelistedNumbers + number
    }

    fun removeWhitelistedNumber(number: String) {
        whitelistedNumbers = whitelistedNumbers - number
    }

    fun isNumberWhitelisted(number: String): Boolean {
        val normalized = number.filter { it.isDigit() || it == '+' }
        return whitelistedNumbers.any { allowed ->
            val normalizedAllowed = allowed.filter { it.isDigit() || it == '+' }
            normalizedAllowed.isNotBlank() &&
                (normalized == normalizedAllowed || normalized.startsWith(normalizedAllowed))
        }
    }

    var blacklistedNumbers: Set<String>
        get() = prefs.getStringSet("blacklisted_numbers", emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet("blacklisted_numbers", value).apply()

    fun addBlacklistedNumber(number: String) {
        blacklistedNumbers = blacklistedNumbers + number
    }

    fun removeBlacklistedNumber(number: String) {
        blacklistedNumbers = blacklistedNumbers - number
    }

    fun isNumberBlacklisted(number: String): Boolean {
        val normalized = number.filter { it.isDigit() || it == '+' }
        return blacklistedNumbers.any { blocked ->
            val normalizedBlocked = blocked.filter { it.isDigit() || it == '+' }
            normalizedBlocked.isNotBlank() &&
                (normalized == normalizedBlocked || normalized.startsWith(normalizedBlocked))
        }
    }

    var keyboardHeight: Int
        get() = prefs.getInt(SOFT_KEYBOARD_HEIGHT, context.getDefaultKeyboardHeight())
        set(keyboardHeight) = prefs.edit().putInt(SOFT_KEYBOARD_HEIGHT, keyboardHeight).apply()

    var useRecycleBin: Boolean
        get() = prefs.getBoolean(USE_RECYCLE_BIN, true)
        set(useRecycleBin) = prefs.edit().putBoolean(USE_RECYCLE_BIN, useRecycleBin).apply()

    var lastRecycleBinCheck: Long
        get() = prefs.getLong(LAST_RECYCLE_BIN_CHECK, 0L)
        set(lastRecycleBinCheck) = prefs.edit().putLong(LAST_RECYCLE_BIN_CHECK, lastRecycleBinCheck)
            .apply()

    var customNotifications: Set<String>
        get() = prefs.getStringSet(CUSTOM_NOTIFICATIONS, HashSet<String>())!!
        set(customNotifications) = prefs.edit()
            .putStringSet(CUSTOM_NOTIFICATIONS, customNotifications).apply()

    fun addCustomNotificationsByThreadId(threadId: Long) {
        customNotifications = customNotifications.plus(threadId.toString())
    }

    fun removeCustomNotificationsByThreadId(threadId: Long) {
        customNotifications = customNotifications.minus(threadId.toString())
    }

    var lastBlockedKeywordExportPath: String
        get() = prefs.getString(LAST_BLOCKED_KEYWORD_EXPORT_PATH, "")!!
        set(lastBlockedNumbersExportPath) = prefs.edit()
            .putString(LAST_BLOCKED_KEYWORD_EXPORT_PATH, lastBlockedNumbersExportPath).apply()

    var enableLowBatteryReminder: Boolean
        get() = prefs.getBoolean(ENABLE_LOW_BATTERY_REMINDER, false)
        set(value) = prefs.edit().putBoolean(ENABLE_LOW_BATTERY_REMINDER, value).apply()

    var lowBatteryThreshold: Int
        get() = prefs.getInt(LOW_BATTERY_THRESHOLD, 15).coerceIn(5, 50)
        set(value) = prefs.edit().putInt(LOW_BATTERY_THRESHOLD, value.coerceIn(5, 50)).apply()

    var lowBatteryLastNotifiedLevel: Int
        get() = prefs.getInt(LOW_BATTERY_LAST_NOTIFIED_LEVEL, -1)
        set(value) = prefs.edit().putInt(LOW_BATTERY_LAST_NOTIFIED_LEVEL, value).apply()

    var lowBatteryChannels: Set<String>
        get() = prefs.getStringSet(
            LOW_BATTERY_CHANNELS,
            emptySet(),
        )?.intersect(ForwardingChannels.lowBatteryChannels.toSet())
            ?: emptySet()
        set(value) = prefs.edit().putStringSet(
            LOW_BATTERY_CHANNELS,
            value.intersect(ForwardingChannels.lowBatteryChannels.toSet()),
        ).apply()
}
