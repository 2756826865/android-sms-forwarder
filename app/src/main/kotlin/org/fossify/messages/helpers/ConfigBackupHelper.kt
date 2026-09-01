package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.BuildConfig
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
        root.put("version", BuildConfig.VERSION_NAME)
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
            .put("dingTalkWebhook", multiConfig.dingTalkWebhook())
            .put("dingTalkSecret", multiConfig.dingTalkSecret())
            .put("feishuWebhook", multiConfig.feishuWebhook())
            .put("feishuSecret", multiConfig.feishuSecret())
            .put("weComBotWebhook", multiConfig.weComBotWebhook())
            .put("emailHost", multiConfig.emailHost())
            .put("emailPort", multiConfig.emailPort)
            .put("emailUser", multiConfig.emailUser())
            .put("emailRecipients", multiConfig.emailRecipients())
            .put("barkServerUrl", multiConfig.barkServerUrl())
            .put("barkDeviceKey", multiConfig.barkDeviceKey())
            .put("gotifyServerUrl", multiConfig.gotifyServerUrl())
            .put("gotifyToken", multiConfig.gotifyToken())
            .put("smsDirectPhone", multiConfig.smsDirectPhone())
            .put("templateMode", multiConfig.templateMode)
            .put("customTemplate", multiConfig.customTemplate)
        root.put("forwardingChannels", channelsObj)

        // 3. PushPlus Config
        val pushPlusConfig = PushPlusConfig(context)
        val pushPlusObj = JSONObject()
            .put("enabled", pushPlusConfig.enabled)
            .put("token", pushPlusConfig.getToken())
            .put("titlePrefix", pushPlusConfig.titlePrefix)
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
            
            val dtUrl = obj.optString("dingTalkWebhook")
            val dtSecret = obj.optString("dingTalkSecret")
            if (dtUrl.isNotBlank()) multiConfig.saveDingTalk(dtUrl, dtSecret)

            val fsUrl = obj.optString("feishuWebhook")
            val fsSecret = obj.optString("feishuSecret")
            if (fsUrl.isNotBlank()) multiConfig.saveFeishu(fsUrl, fsSecret)

            val wcBotUrl = obj.optString("weComBotWebhook")
            if (wcBotUrl.isNotBlank()) multiConfig.saveWeComBot(wcBotUrl)

            val barkUrl = obj.optString("barkServerUrl")
            val barkKey = obj.optString("barkDeviceKey")
            if (barkUrl.isNotBlank() && barkKey.isNotBlank()) multiConfig.saveBark(barkUrl, barkKey)

            val gotifyUrl = obj.optString("gotifyServerUrl")
            val gotifyToken = obj.optString("gotifyToken")
            if (gotifyUrl.isNotBlank() && gotifyToken.isNotBlank()) multiConfig.saveGotify(gotifyUrl, gotifyToken)

            val smsPhone = obj.optString("smsDirectPhone")
            if (smsPhone.isNotBlank()) multiConfig.saveSmsDirect(smsPhone)

            obj.optString("customTemplate").takeIf { it.isNotBlank() }?.let { multiConfig.customTemplate = it }
            if (obj.has("templateMode")) multiConfig.templateMode = obj.getInt("templateMode")
        }

        if (root.has("pushPlus")) {
            val pushPlusConfig = PushPlusConfig(context)
            val obj = root.getJSONObject("pushPlus")
            obj.optString("token").takeIf { it.isNotBlank() }?.let { pushPlusConfig.saveToken(it) }
            obj.optString("titlePrefix").takeIf { it.isNotBlank() }?.let { pushPlusConfig.titlePrefix = it }
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
