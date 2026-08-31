package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.autoreply.AutoReplyConfig
import org.fossify.messages.forwarding.ForwardingRulesConfig
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.PushPlusConfig
import org.fossify.messages.remote.RemoteSmsCommandConfig
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConfigBackupHelper {

    fun exportToJson(context: Context): String {
        val root = JSONObject()
        root.put("version", "1.1.3")
        root.put("exportTime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))

        // 1. Forwarding Rules
        val rulesConfig = ForwardingRulesConfig(context)
        val rulesObj = JSONObject()
            .put("enabled", rulesConfig.enabled)
            .put("scope", rulesConfig.scope)
            .put("rulesSummary", rulesConfig.summary())
        root.put("forwardingRules", rulesObj)

        // 2. Multi-channel Forwarding Config
        val multiConfig = MultiForwardConfig(context)
        val channelsObj = JSONObject()
            .put("dingTalkWebhook", multiConfig.dingTalkWebhook)
            .put("feishuWebhook", multiConfig.feishuWebhook)
            .put("weComWebhook", multiConfig.weComWebhook)
            .put("emailHost", multiConfig.emailHost)
            .put("emailPort", multiConfig.emailPort)
            .put("emailUsername", multiConfig.emailUsername)
            .put("emailSender", multiConfig.emailSender)
            .put("emailRecipient", multiConfig.emailRecipient)
            .put("barkServerUrl", multiConfig.barkServerUrl)
            .put("gotifyServerUrl", multiConfig.gotifyServerUrl)
            .put("smsDirectRecipient", multiConfig.smsDirectRecipient)
            .put("templateMode", multiConfig.templateMode)
            .put("customTemplate", multiConfig.customTemplate)
        root.put("forwardingChannels", channelsObj)

        // 3. PushPlus Config
        val pushPlusConfig = PushPlusConfig(context)
        val pushPlusObj = JSONObject()
            .put("enabled", pushPlusConfig.enabled)
            .put("token", pushPlusConfig.token)
            .put("template", pushPlusConfig.template)
            .put("channel", pushPlusConfig.channel)
        root.put("pushPlus", pushPlusObj)

        // 4. Auto Reply Config
        val autoReplyConfig = AutoReplyConfig(context)
        val autoReplyObj = JSONObject()
            .put("enabled", autoReplyConfig.enabled)
            .put("dailyLimit", autoReplyConfig.dailyLimit)
        root.put("autoReply", autoReplyObj)

        // 5. Remote SMS Command Config
        val remoteConfig = RemoteSmsCommandConfig(context)
        val remoteObj = JSONObject()
            .put("enabled", remoteConfig.enabled)
            .put("authorizedNumbers", remoteConfig.authorizedNumbers)
            .put("customPrefix", remoteConfig.customPrefix)
        root.put("remoteCommand", remoteObj)

        return root.toString(2)
    }

    fun importFromJson(context: Context, jsonStr: String): Boolean = runCatching {
        val root = JSONObject(jsonStr)

        if (root.has("forwardingChannels")) {
            val multiConfig = MultiForwardConfig(context)
            val obj = root.getJSONObject("forwardingChannels")
            obj.optString("dingTalkWebhook").takeIf { it.isNotBlank() }?.let { multiConfig.dingTalkWebhook = it }
            obj.optString("feishuWebhook").takeIf { it.isNotBlank() }?.let { multiConfig.feishuWebhook = it }
            obj.optString("weComWebhook").takeIf { it.isNotBlank() }?.let { multiConfig.weComWebhook = it }
            obj.optString("emailHost").takeIf { it.isNotBlank() }?.let { multiConfig.emailHost = it }
            obj.optString("emailUsername").takeIf { it.isNotBlank() }?.let { multiConfig.emailUsername = it }
            obj.optString("emailRecipient").takeIf { it.isNotBlank() }?.let { multiConfig.emailRecipient = it }
            obj.optString("barkServerUrl").takeIf { it.isNotBlank() }?.let { multiConfig.barkServerUrl = it }
            obj.optString("gotifyServerUrl").takeIf { it.isNotBlank() }?.let { multiConfig.gotifyServerUrl = it }
            obj.optString("smsDirectRecipient").takeIf { it.isNotBlank() }?.let { multiConfig.smsDirectRecipient = it }
            obj.optString("customTemplate").takeIf { it.isNotBlank() }?.let { multiConfig.customTemplate = it }
            if (obj.has("templateMode")) multiConfig.templateMode = obj.getInt("templateMode")
        }

        if (root.has("pushPlus")) {
            val pushPlusConfig = PushPlusConfig(context)
            val obj = root.getJSONObject("pushPlus")
            obj.optString("token").takeIf { it.isNotBlank() }?.let { pushPlusConfig.token = it }
            if (obj.has("enabled")) pushPlusConfig.enabled = obj.getBoolean("enabled")
        }

        if (root.has("autoReply")) {
            val autoReplyConfig = AutoReplyConfig(context)
            val obj = root.getJSONObject("autoReply")
            if (obj.has("enabled")) autoReplyConfig.enabled = obj.getBoolean("enabled")
            if (obj.has("dailyLimit")) autoReplyConfig.dailyLimit = obj.getInt("dailyLimit")
        }

        if (root.has("remoteCommand")) {
            val remoteConfig = RemoteSmsCommandConfig(context)
            val obj = root.getJSONObject("remoteCommand")
            if (obj.has("enabled")) remoteConfig.enabled = obj.getBoolean("enabled")
            obj.optString("authorizedNumbers").takeIf { it.isNotBlank() }?.let { remoteConfig.authorizedNumbers = it }
            obj.optString("customPrefix").takeIf { it.isNotBlank() }?.let { remoteConfig.customPrefix = it }
        }

        true
    }.getOrDefault(false)
}
