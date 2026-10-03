package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.extensions.config
import org.json.JSONArray
import org.json.JSONObject

internal object BackupUiSettings {
    fun export(context: Context): JSONObject {
        val config = context.config
        return JSONObject()
            .put("showHomeBottomNavigation", config.showHomeBottomNavigation)
            .put("enableLiveIsland", config.enableLiveIsland)
            .put("showCharacterCounter", config.showCharacterCounter)
            .put("useSimpleCharacters", config.useSimpleCharacters)
            .put("sendOnEnter", config.sendOnEnter)
            .put("enableDeliveryReports", config.enableDeliveryReports)
            .put("sendLongMessageMMS", config.sendLongMessageMMS)
            .put("sendGroupMessageMMS", config.sendGroupMessageMMS)
            .put("showListAvatars", config.showListAvatars)
            .put("showLetterAvatars", config.showLetterAvatars)
            .put("useRecycleBin", config.useRecycleBin)
            .put("enableLowBatteryReminder", config.enableLowBatteryReminder)
            .put("shadowOperationTrackingEnabled", config.shadowOperationTrackingEnabled)
            .put("smsSendOperationShadowEnabled", config.smsSendOperationShadowEnabled)
            .put("homeListDensity", config.homeListDensity)
            .put("lockScreenVisibilitySetting", config.lockScreenVisibilitySetting)
            .put("bulkSendDelaySeconds", config.bulkSendDelaySeconds)
            .put("lowBatteryThreshold", config.lowBatteryThreshold)
            .put("mmsFileSizeLimit", config.mmsFileSizeLimit)
            .put("blockedKeywords", JSONArray(config.blockedKeywords.toList()))
            .put("whitelistedNumbers", JSONArray(config.whitelistedNumbers.toList()))
            .put("blacklistedNumbers", JSONArray(config.blacklistedNumbers.toList()))
            .put("lowBatteryChannels", JSONArray(config.lowBatteryChannels.toList()))
            .apply { if (config.hasLowBatteryInstanceSelection) put("lowBatteryChannelInstanceIds", JSONArray(config.lowBatteryChannelInstanceIds.toList())) }
    }
    fun validate(json: JSONObject) {
        if (json.has("showHomeBottomNavigation")) require(json.get("showHomeBottomNavigation") is Boolean)
        if (json.has("enableLiveIsland")) require(json.get("enableLiveIsland") is Boolean)
        if (json.has("showCharacterCounter")) require(json.get("showCharacterCounter") is Boolean)
        if (json.has("useSimpleCharacters")) require(json.get("useSimpleCharacters") is Boolean)
        if (json.has("sendOnEnter")) require(json.get("sendOnEnter") is Boolean)
        if (json.has("enableDeliveryReports")) require(json.get("enableDeliveryReports") is Boolean)
        if (json.has("sendLongMessageMMS")) require(json.get("sendLongMessageMMS") is Boolean)
        if (json.has("sendGroupMessageMMS")) require(json.get("sendGroupMessageMMS") is Boolean)
        if (json.has("showListAvatars")) require(json.get("showListAvatars") is Boolean)
        if (json.has("showLetterAvatars")) require(json.get("showLetterAvatars") is Boolean)
        if (json.has("useRecycleBin")) require(json.get("useRecycleBin") is Boolean)
        if (json.has("enableLowBatteryReminder")) require(json.get("enableLowBatteryReminder") is Boolean)
        if (json.has("shadowOperationTrackingEnabled")) require(json.get("shadowOperationTrackingEnabled") is Boolean)
        if (json.has("smsSendOperationShadowEnabled")) require(json.get("smsSendOperationShadowEnabled") is Boolean)
        if (json.has("homeListDensity")) require(json.get("homeListDensity") is Int)
        if (json.has("lockScreenVisibilitySetting")) require(json.get("lockScreenVisibilitySetting") is Int)
        if (json.has("bulkSendDelaySeconds")) require(json.get("bulkSendDelaySeconds") is Int)
        if (json.has("lowBatteryThreshold")) require(json.get("lowBatteryThreshold") is Int)
        if (json.has("mmsFileSizeLimit")) require(json.get("mmsFileSizeLimit") is Number)
        if (json.has("homeListDensity")) require(json.getInt("homeListDensity") in listOf(4, 6, 8, 10))
        if (json.has("lowBatteryThreshold")) require(json.getInt("lowBatteryThreshold") in 5..50)
        if (json.has("blockedKeywords")) json.getJSONArray("blockedKeywords").let { a -> for (i in 0 until a.length()) require(a.get(i) is String) }
        if (json.has("whitelistedNumbers")) json.getJSONArray("whitelistedNumbers").let { a -> for (i in 0 until a.length()) require(a.get(i) is String) }
        if (json.has("blacklistedNumbers")) json.getJSONArray("blacklistedNumbers").let { a -> for (i in 0 until a.length()) require(a.get(i) is String) }
        if (json.has("lowBatteryChannels")) json.getJSONArray("lowBatteryChannels").let { a -> for (i in 0 until a.length()) require(a.get(i) is String) }
        if (json.has("lowBatteryChannelInstanceIds")) json.getJSONArray("lowBatteryChannelInstanceIds").let { a -> for (i in 0 until a.length()) require(a.get(i) is String) }
    }
    fun restore(context: Context, json: JSONObject) {
        validate(json)
        val config = context.config
        if (json.has("showHomeBottomNavigation")) config.showHomeBottomNavigation = json.getBoolean("showHomeBottomNavigation")
        if (json.has("enableLiveIsland")) config.enableLiveIsland = json.getBoolean("enableLiveIsland")
        if (json.has("showCharacterCounter")) config.showCharacterCounter = json.getBoolean("showCharacterCounter")
        if (json.has("useSimpleCharacters")) config.useSimpleCharacters = json.getBoolean("useSimpleCharacters")
        if (json.has("sendOnEnter")) config.sendOnEnter = json.getBoolean("sendOnEnter")
        if (json.has("enableDeliveryReports")) config.enableDeliveryReports = json.getBoolean("enableDeliveryReports")
        if (json.has("sendLongMessageMMS")) config.sendLongMessageMMS = json.getBoolean("sendLongMessageMMS")
        if (json.has("sendGroupMessageMMS")) config.sendGroupMessageMMS = json.getBoolean("sendGroupMessageMMS")
        if (json.has("showListAvatars")) config.showListAvatars = json.getBoolean("showListAvatars")
        if (json.has("showLetterAvatars")) config.showLetterAvatars = json.getBoolean("showLetterAvatars")
        if (json.has("useRecycleBin")) config.useRecycleBin = json.getBoolean("useRecycleBin")
        if (json.has("enableLowBatteryReminder")) config.enableLowBatteryReminder = json.getBoolean("enableLowBatteryReminder")
        if (json.has("shadowOperationTrackingEnabled")) config.shadowOperationTrackingEnabled = json.getBoolean("shadowOperationTrackingEnabled")
        if (json.has("smsSendOperationShadowEnabled")) config.smsSendOperationShadowEnabled = json.getBoolean("smsSendOperationShadowEnabled")
        if (json.has("homeListDensity")) config.homeListDensity = json.getInt("homeListDensity")
        if (json.has("lockScreenVisibilitySetting")) config.lockScreenVisibilitySetting = json.getInt("lockScreenVisibilitySetting")
        if (json.has("bulkSendDelaySeconds")) config.bulkSendDelaySeconds = json.getInt("bulkSendDelaySeconds")
        if (json.has("lowBatteryThreshold")) config.lowBatteryThreshold = json.getInt("lowBatteryThreshold")
        if (json.has("mmsFileSizeLimit")) config.mmsFileSizeLimit = json.getLong("mmsFileSizeLimit")
        if (json.has("blockedKeywords")) config.blockedKeywords = json.getJSONArray("blockedKeywords").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        if (json.has("whitelistedNumbers")) config.whitelistedNumbers = json.getJSONArray("whitelistedNumbers").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        if (json.has("blacklistedNumbers")) config.blacklistedNumbers = json.getJSONArray("blacklistedNumbers").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        if (json.has("lowBatteryChannels")) config.lowBatteryChannels = json.getJSONArray("lowBatteryChannels").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
        if (json.has("lowBatteryChannelInstanceIds")) config.lowBatteryChannelInstanceIds = json.getJSONArray("lowBatteryChannelInstanceIds").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    }
}
