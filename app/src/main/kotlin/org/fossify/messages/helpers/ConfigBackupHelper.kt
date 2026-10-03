package org.fossify.messages.helpers

import android.content.Context
import org.fossify.messages.BuildConfig
import org.fossify.messages.autoreply.AutoReplyConfig
import org.fossify.messages.forwarding.ForwardingRulesConfig
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.PushPlusConfig
import org.fossify.messages.remote.RemoteSmsCommandConfig
import org.json.JSONObject
import org.json.JSONArray
import org.fossify.messages.forwarding.ForwardingChannelInstance
import org.fossify.messages.forwarding.repository.ChannelRepository
import org.fossify.messages.security.crypto.CredentialHealth
import org.fossify.messages.remote.repository.RemoteSourceRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ConfigBackupHelper {

    /** A read-only structural preview; import performs the full semantic validation. */
    fun preview(json: String): String {
        val root = JSONObject(json)
        val version = root.optInt("backupSchemaVersion", 1)
        require(version in 1..2) { "Unsupported backup version" }
        require(listOf("forwardingRules", "forwardingChannels", "channelInstances", "remoteSources", "autoReply", "pushPlus", "remoteCommand").any(root::has))
        fun count(section: String, nested: String? = null): String {
            val array = if (nested == null) root.optJSONArray(section) else root.optJSONObject(section)?.optJSONArray(nested)
            return array?.length()?.toString() ?: "未包含，保留当前配置"
        }
        return "备份格式：$version\n通道实例：${count("channelInstances")}\n转发规则：${count("forwardingRules", "rules")}\n远程来源：${count("remoteSources")}\n自动回复规则：${count("autoReply", "rules")}\n" +
            "同 ID 实例/来源会更新，规则列表会替换；未包含的部分保留。\n不包含短信正文、历史流水及全部界面设置。\n备份包含凭据；导入还需校验内容，预览不保证导入成功。"
    }

    fun exportToJson(context: Context): String {
        val root = JSONObject()
        root.put("backupSchemaVersion", 2)
        root.put("version", BuildConfig.VERSION_NAME)
        root.put("exportTime", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))

        // 1. Forwarding Rules
        val rulesConfig = ForwardingRulesConfig(context)
        val rulesObj = JSONObject()
            .put("enabled", rulesConfig.enabled)
            .put("scope", rulesConfig.scope)
            .put("rulesSummary", rulesConfig.summary())
            .put("rules", JSONArray(rulesConfig.encodeRules(rulesConfig.rules)))
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
            .put("emailPassword", multiConfig.emailPassword())
            .put("emailSecurity", multiConfig.emailSecurity)
            .put("emailEnabled", multiConfig.emailEnabled)
            .put("barkServerUrl", multiConfig.barkServerUrl())
            .put("barkDeviceKey", multiConfig.barkDeviceKey())
            .put("gotifyServerUrl", multiConfig.gotifyServerUrl())
            .put("gotifyToken", multiConfig.gotifyToken())
            .put("smsDirectPhone", multiConfig.smsDirectPhone())
            .put("templateMode", multiConfig.templateMode)
            .put("customTemplate", multiConfig.customTemplate)
        root.put("forwardingChannels", channelsObj)
        root.put("remoteSources", RemoteSourceRepository.getInstance(context).exportBackup())
        root.put("channelInstances", JSONArray().apply {
            multiConfig.channelInstances().forEach { put(it.toJson()) }
        })

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
            .put("rules", JSONArray(autoReplyConfig.encodeRules(autoReplyConfig.rules)))
        root.put("autoReply", autoReplyObj)

        // 5. Remote SMS Command Config
        val remoteConfig = RemoteSmsCommandConfig(context)
        val remoteObj = JSONObject()
            .put("enabled", remoteConfig.enabled)
            .put("authorizedNumbers", remoteConfig.authorizedNumbers)
            .put("customPrefix", remoteConfig.customPrefix)
        root.put("remoteCommand", remoteObj)

        check(!CredentialHealth.hasFailures()) { "凭据不可解密，无法导出完整备份" }
        return root.toString(2)
    }

    fun importFromJson(context: Context, jsonStr: String): Boolean = runCatching {
        val root = JSONObject(jsonStr)
        require(root.optInt("backupSchemaVersion", 1) in 1..2)
        require(listOf("forwardingChannels", "forwardingRules", "channelInstances", "remoteSources", "pushPlus", "autoReply", "remoteCommand").any(root::has))
        // Validate known sections before any import writes. Missing sections remain unchanged.
        listOf("forwardingChannels", "forwardingRules", "pushPlus", "autoReply", "remoteCommand").forEach { section ->
            if (root.has(section)) root.getJSONObject(section)
        }
        listOf("channelInstances", "remoteSources").forEach { section ->
            if (root.has(section)) root.getJSONArray(section)
        }
        listOf("forwardingRules", "autoReply").forEach { section ->
            root.optJSONObject(section)?.let { obj ->
                if (obj.has("rules")) obj.getJSONArray("rules")
            }
        }
        mapOf(
            "forwardingChannels" to listOf("emailPort", "emailSecurity", "templateMode"),
            "forwardingRules" to listOf("scope"), "autoReply" to listOf("dailyLimit")
        ).forEach { (section, fields) ->
            root.optJSONObject(section)?.let { obj -> fields.filter(obj::has).forEach { obj.getInt(it) } }
        }
        mapOf(
            "forwardingChannels" to listOf("emailEnabled"),
            "forwardingRules" to listOf("enabled"), "pushPlus" to listOf("enabled"),
            "autoReply" to listOf("enabled"), "remoteCommand" to listOf("enabled")
        ).forEach { (section, fields) ->
            root.optJSONObject(section)?.let { obj -> fields.filter(obj::has).forEach { obj.getBoolean(it) } }
        }
        root.optJSONObject("forwardingChannels")?.let { obj ->
            if (obj.has("emailPassword")) {
                listOf("emailHost", "emailUser", "emailPassword", "emailRecipients").forEach { obj.getString(it) }
                obj.getInt("emailPort")
                obj.getInt("emailSecurity")
            }
        }
        val remoteSources = RemoteSourceRepository.getInstance(context)
        val importedSources = root.optJSONArray("remoteSources")?.let(remoteSources::parseBackup)
        // Parse new sections before changing existing configuration.
        val rulesConfig = ForwardingRulesConfig(context)
        val rulesSection = root.optJSONObject("forwardingRules")
        val importedRules = rulesSection?.optJSONArray("rules")?.let {
            for (index in 0 until it.length()) it.getJSONObject(index)
            rulesConfig.decodeRules(it.toString()).also { decoded -> require(decoded.size == it.length()) }
        }
        val autoReplyConfig = AutoReplyConfig(context)
        val importedAutoReplyRules = root.optJSONObject("autoReply")?.optJSONArray("rules")?.let { array ->
            for (index in 0 until array.length()) array.getJSONObject(index)
            autoReplyConfig.decodeRules(array.toString()).also { require(it.size == array.length()) }
        }
        val importedInstances = root.optJSONArray("channelInstances")?.let { array ->
            List(array.length()) { index ->
                val item = array.getJSONObject(index)
                require(item.getString("id").isNotBlank())
                require(item.getString("channelType").isNotBlank())
                JSONObject(item.getString("configJson"))
                ForwardingChannelInstance.fromJson(item)
            }.also { require(it.map { instance -> instance.id }.distinct().size == it.size) }
        }
        if (importedSources != null) check(remoteSources.restoreBackup(importedSources))
        if (importedInstances != null) {
            val config = MultiForwardConfig(context)
            val merged = config.channelInstances().associateBy { it.id }.toMutableMap()
            importedInstances.forEach { merged[it.id] = it }
            check(config.saveChannelInstances(merged.values.toList()))
            ChannelRepository.getInstance(context).refresh()
        }
        importedRules?.let {
            rulesConfig.rules = it
            org.fossify.messages.forwarding.repository.RuleRepository.getInstance(context).refresh()
        }
        rulesSection?.let {
            if (it.has("enabled")) rulesConfig.enabled = it.getBoolean("enabled")
            if (it.has("scope")) rulesConfig.scope = it.getInt("scope")
        }

        if (root.has("forwardingChannels")) {
            val multiConfig = MultiForwardConfig(context)
            val obj = root.getJSONObject("forwardingChannels")
            
            if (obj.has("emailPassword")) {
                multiConfig.saveEmail(obj.getString("emailHost"), obj.getInt("emailPort"),
                    obj.getString("emailUser"), obj.getString("emailPassword"),
                    obj.getString("emailRecipients"), obj.getInt("emailSecurity"))
                if (obj.has("emailEnabled")) multiConfig.emailEnabled = obj.getBoolean("emailEnabled")
            }
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
            importedAutoReplyRules?.let { autoReplyConfig.rules = it }
        }

        if (root.has("remoteCommand")) {
            val remoteConfig = RemoteSmsCommandConfig(context)
            val obj = root.getJSONObject("remoteCommand")
            if (obj.has("enabled")) remoteConfig.enabled = obj.getBoolean("enabled")
            obj.optString("authorizedNumbers").takeIf { it.isNotBlank() }?.let { remoteConfig.authorizedNumbers = it }
            obj.optString("customPrefix").takeIf { it.isNotBlank() }?.let { remoteConfig.customPrefix = it }
        }

        if (importedSources != null) remoteSources.reconnectAfterRestore()
        true
    }.getOrDefault(false)
}
