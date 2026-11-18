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
        require(version in 1..3) { "Unsupported backup version" }
        require(listOf("forwardingRules", "forwardingChannels", "channelInstances", "remoteSources", "autoReply", "pushPlus", "remoteCommand").any(root::has))
        fun count(section: String, nested: String? = null): String {
            val array = if (nested == null) root.optJSONArray(section) else root.optJSONObject(section)?.optJSONArray(nested)
            return array?.length()?.toString() ?: "未包含，保留当前配置"
        }
        return "备份格式：$version\n通道实例：${count("channelInstances")}\n转发规则：${count("forwardingRules", "rules")}\n远程来源：${count("remoteSources")}\n自动回复规则：${count("autoReply", "rules")}\n" +
            "同 ID 实例/来源会更新，规则列表会替换；未包含的部分保留。\n包含SIM、备用通道及常用设置（v3）；不包含短信正文、历史流水、系统授权。\n备份包含凭据；导入还需校验内容，预览不保证导入成功。"
    }

    fun exportToJson(context: Context): String {
        val root = JSONObject()
        root.put("backupSchemaVersion", 3)
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

        // 2. Multi-channel Forwarding Config (including all legacy channels & Classic remote settings)
        val multiConfig = MultiForwardConfig(context)
        val channelsObj = JSONObject()
            .put("dingTalkEnabled", multiConfig.dingTalkEnabled)
            .put("dingTalkWebhook", multiConfig.dingTalkWebhook())
            .put("dingTalkSecret", multiConfig.dingTalkSecret())
            .put("feishuEnabled", multiConfig.feishuEnabled)
            .put("feishuWebhook", multiConfig.feishuWebhook())
            .put("feishuSecret", multiConfig.feishuSecret())
            .put("feishuAppEnabled", multiConfig.feishuAppEnabled)
            .put("feishuAppId", multiConfig.feishuAppId())
            .put("feishuAppSecret", multiConfig.feishuAppSecret())
            .put("feishuReceiveId", multiConfig.feishuReceiveId())
            .put("weComBotEnabled", multiConfig.weComBotEnabled)
            .put("weComBotWebhook", multiConfig.weComBotWebhook())
            .put("weComEnabled", multiConfig.weComEnabled)
            .put("weComCorpId", multiConfig.weComCorpId())
            .put("weComAgentId", multiConfig.weComAgentId())
            .put("weComSecret", multiConfig.weComSecret())
            .put("weComToUser", multiConfig.weComToUser())
            .put("wechatTestEnabled", multiConfig.wechatTestEnabled)
            .put("wechatTestAppId", multiConfig.wechatTestAppId())
            .put("wechatTestAppSecret", multiConfig.wechatTestAppSecret())
            .put("wechatTestTemplateId", multiConfig.wechatTestTemplateId())
            .put("wechatTestOpenId", multiConfig.wechatTestOpenId())
            .put("qqEnabled", multiConfig.qqEnabled)
            .put("qqWebhook", multiConfig.qqWebhook())
            .put("qqType", multiConfig.qqType())
            .put("telegramEnabled", multiConfig.telegramEnabled)
            .put("telegramBotToken", multiConfig.telegramBotToken())
            .put("telegramChatId", multiConfig.telegramChatId())
            .put("discordEnabled", multiConfig.discordEnabled)
            .put("discordWebhook", multiConfig.discordWebhook())
            .put("tencentCloudEnabled", multiConfig.tencentCloudEnabled)
            .put("tencentCloudWebhook", multiConfig.tencentCloudWebhook())
            .put("tencentCloudSecret", multiConfig.tencentCloudSecret())
            .put("websocketEnabled", multiConfig.websocketEnabled)
            .put("websocketUrl", multiConfig.websocketUrl())
            .put("websocketToken", multiConfig.websocketToken())
            .put("customWebhookEnabled", multiConfig.customWebhookEnabled)
            .put("customWebhookUrl", multiConfig.customWebhookUrl())
            .put("customWebhookHeaders", multiConfig.customWebhookHeaders())
            .put("customWebhookMethod", multiConfig.customWebhookMethod())
            .put("customWebhookContentType", multiConfig.customWebhookContentType())
            .put("customWebhookBodyTemplate", multiConfig.customWebhookBodyTemplate())
            .put("channelGroupEnabled", multiConfig.channelGroupEnabled)
            .put("channelGroupMembers", JSONArray(multiConfig.channelGroupMembers().toList()))
            .put("emailHost", multiConfig.emailHost())
            .put("emailPort", multiConfig.emailPort)
            .put("emailUser", multiConfig.emailUser())
            .put("emailRecipients", multiConfig.emailRecipients())
            .put("emailPassword", multiConfig.emailPassword())
            .put("emailSecurity", multiConfig.emailSecurity)
            .put("emailEnabled", multiConfig.emailEnabled)
            .put("barkEnabled", multiConfig.barkEnabled)
            .put("barkServerUrl", multiConfig.barkServerUrl())
            .put("barkDeviceKey", multiConfig.barkDeviceKey())
            .put("barkLevel", multiConfig.barkLevel)
            .put("barkGroup", multiConfig.barkGroup)
            .put("barkAutoCopyCode", multiConfig.barkAutoCopyCode)
            .put("gotifyEnabled", multiConfig.gotifyEnabled)
            .put("gotifyServerUrl", multiConfig.gotifyServerUrl())
            .put("gotifyToken", multiConfig.gotifyToken())
            .put("smsDirectEnabled", multiConfig.smsDirectEnabled)
            .put("smsDirectPhone", multiConfig.smsDirectPhone())
            .put("dingTalkRemoteControlEnabled", multiConfig.dingTalkRemoteControlEnabled)
            .put("dingTalkRemoteClientId", multiConfig.dingTalkRemoteClientId())
            .put("dingTalkRemoteClientSecret", multiConfig.dingTalkRemoteClientSecret())
            .put("dingTalkRemoteCustomPrefix", multiConfig.dingTalkRemoteCustomPrefix())
            .put("dingTalkRemoteSendSimMode", multiConfig.dingTalkRemoteSendSimMode)
            .put("feishuRemoteControlEnabled", multiConfig.feishuRemoteControlEnabled)
            .put("feishuRemoteAppId", multiConfig.feishuRemoteAppId())
            .put("feishuRemoteAppSecret", multiConfig.feishuRemoteAppSecret())
            .put("feishuRemoteCustomPrefix", multiConfig.feishuRemoteCustomPrefix())
            .put("feishuRemoteSendSimMode", multiConfig.feishuRemoteSendSimMode)
            .put("weComRemoteControlEnabled", multiConfig.weComRemoteControlEnabled)
            .put("weComRemoteBotId", multiConfig.weComRemoteBotId())
            .put("weComRemoteSecret", multiConfig.weComRemoteSecret())
            .put("weComRemoteChatId", multiConfig.weComRemoteChatId())
            .put("weComRemoteCustomPrefix", multiConfig.weComRemoteCustomPrefix())
            .put("weComRemoteSendSimMode", multiConfig.weComRemoteSendSimMode)
            .put("telegramRemoteControlEnabled", multiConfig.telegramRemoteControlEnabled)
            .put("telegramRemoteBotToken", multiConfig.telegramRemoteBotToken())
            .put("telegramRemoteChatId", multiConfig.telegramRemoteChatId())
            .put("telegramRemoteCustomHost", multiConfig.telegramRemoteCustomHost())
            .put("telegramRemoteAuthorizedUsers", multiConfig.telegramRemoteAuthorizedUsers())
            .put("telegramRemoteCustomPrefix", multiConfig.telegramRemoteCustomPrefix())
            .put("telegramRemoteSendSimMode", multiConfig.telegramRemoteSendSimMode)
            .put("emailRemoteControlEnabled", multiConfig.emailRemoteControlEnabled)
            .put("emailRemoteHost", multiConfig.emailRemoteHost())
            .put("emailRemotePort", multiConfig.emailRemotePort())
            .put("emailRemoteUser", multiConfig.emailRemoteUser())
            .put("emailRemotePassword", multiConfig.emailRemotePassword())
            .put("emailRemoteAuthorizedSenders", multiConfig.emailRemoteAuthorizedSenders())
            .put("emailRemoteSecurity", multiConfig.emailRemoteSecurity)
            .put("emailRemoteCustomPrefix", multiConfig.emailRemoteCustomPrefix())
            .put("emailRemoteSendSimMode", multiConfig.emailRemoteSendSimMode)
            .put("websocketRemoteControlEnabled", multiConfig.websocketRemoteControlEnabled)
            .put("websocketRemoteUrl", multiConfig.websocketRemoteUrl())
            .put("websocketRemoteToken", multiConfig.websocketRemoteToken())
            .put("websocketRemoteCustomPrefix", multiConfig.websocketRemoteCustomPrefix())
            .put("websocketRemoteSendSimMode", multiConfig.websocketRemoteSendSimMode)
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

        root.put("featureSettings", BackupPreferences.export(context))
        root.put("uiSettings", BackupUiSettings.export(context))
        check(!CredentialHealth.hasFailures()) { "凭据不可解密，无法导出完整备份" }
        return root.toString(2)
    }

    @Synchronized
    fun importFromJson(context: Context, jsonStr: String): Boolean {
        // Do not overwrite a journal from an earlier interrupted restore unless its key is permanently lost.
        if (!ConfigRestoreGuard.rollback(context) &&
            !ConfigRestoreGuard.clearUnrecoverablePendingIfKeystoreHealthy(context)
        ) {
            return false
        }
        RemoteSourceRepository.getInstance(context).reloadConfigurationAfterRestore()
        val success = RestoreTransaction.run(
            apply = { applyImport(context, jsonStr) },
            commit = { ConfigRestoreGuard.commit(context) },
            rollback = { ConfigRestoreGuard.rollback(context) })
        // If rollback is incomplete, do not reconnect runtimes using partially restored settings.
        if (!ConfigRestoreGuard.hasPending(context)) runCatching { refreshAfterRestore(context) }
        return success
    }

    private fun refreshAfterRestore(context: Context) {
        RemoteSourceRepository.getInstance(context).reloadAfterRestore()
        ChannelRepository.getInstance(context).refresh()
        org.fossify.messages.forwarding.repository.RuleRepository.getInstance(context).refresh()
        org.fossify.messages.helpers.HeartbeatWorker.sync(context, forceUpdate = true)
        org.fossify.messages.helpers.LowBatteryCheckWorker.sync(context)
        org.fossify.messages.security.root.RootMaintenanceWorker.sync(context)
        org.fossify.messages.services.SmsKeepAliveService.ensureStarted(context)
        org.fossify.messages.services.NotificationForwardListenerService.rebindService(context)
    }

    private fun applyImport(context: Context, jsonStr: String): Boolean = runCatching {
        val root = JSONObject(jsonStr)
        require(root.optInt("backupSchemaVersion", 1) in 1..3)
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
        val featureSettings = root.optJSONObject("featureSettings")?.let(BackupPreferences::parse)
        val uiSettings = if (root.has("uiSettings")) root.getJSONObject("uiSettings").also(BackupUiSettings::validate) else null
        if (root.has("featureSettings")) root.getJSONObject("featureSettings")
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
        ConfigRestoreGuard.begin(context)
        featureSettings?.forEach { (name, values) -> BackupPreferences.write(context.getSharedPreferences(name, Context.MODE_PRIVATE), values, replace = name == "forwarding_fallback") }
        uiSettings?.let { BackupUiSettings.restore(context, it) }
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
            if (obj.has("dingTalkWebhook")) multiConfig.saveDingTalk(obj.optString("dingTalkWebhook"), obj.optString("dingTalkSecret"))
            if (obj.has("dingTalkEnabled")) multiConfig.dingTalkEnabled = obj.getBoolean("dingTalkEnabled")

            if (obj.has("feishuWebhook")) multiConfig.saveFeishu(obj.optString("feishuWebhook"), obj.optString("feishuSecret"))
            if (obj.has("feishuEnabled")) multiConfig.feishuEnabled = obj.getBoolean("feishuEnabled")

            if (obj.has("feishuAppId")) multiConfig.saveFeishuApp(obj.optString("feishuAppId"), obj.optString("feishuAppSecret"), obj.optString("feishuReceiveId"))
            if (obj.has("feishuAppEnabled")) multiConfig.feishuAppEnabled = obj.getBoolean("feishuAppEnabled")

            if (obj.has("weComBotWebhook")) multiConfig.saveWeComBot(obj.optString("weComBotWebhook"))
            if (obj.has("weComBotEnabled")) multiConfig.weComBotEnabled = obj.getBoolean("weComBotEnabled")

            if (obj.has("weComCorpId")) multiConfig.saveWeCom(obj.optString("weComCorpId"), obj.optString("weComAgentId"), obj.optString("weComSecret"), obj.optString("weComToUser"))
            if (obj.has("weComEnabled")) multiConfig.weComEnabled = obj.getBoolean("weComEnabled")

            if (obj.has("wechatTestAppId")) multiConfig.saveWechatTest(obj.optString("wechatTestAppId"), obj.optString("wechatTestAppSecret"), obj.optString("wechatTestTemplateId"), obj.optString("wechatTestOpenId"))
            if (obj.has("wechatTestEnabled")) multiConfig.wechatTestEnabled = obj.getBoolean("wechatTestEnabled")

            if (obj.has("qqWebhook")) multiConfig.saveQq(obj.optString("qqWebhook"), obj.optString("qqType", "qmsg"))
            if (obj.has("qqEnabled")) multiConfig.qqEnabled = obj.getBoolean("qqEnabled")

            if (obj.has("telegramBotToken")) multiConfig.saveTelegram(obj.optString("telegramBotToken"), obj.optString("telegramChatId"))
            if (obj.has("telegramEnabled")) multiConfig.telegramEnabled = obj.getBoolean("telegramEnabled")

            if (obj.has("discordWebhook")) multiConfig.saveDiscord(obj.optString("discordWebhook"))
            if (obj.has("discordEnabled")) multiConfig.discordEnabled = obj.getBoolean("discordEnabled")

            if (obj.has("tencentCloudWebhook")) multiConfig.saveTencentCloud(obj.optString("tencentCloudWebhook"), obj.optString("tencentCloudSecret"))
            if (obj.has("tencentCloudEnabled")) multiConfig.tencentCloudEnabled = obj.getBoolean("tencentCloudEnabled")

            if (obj.has("websocketUrl")) multiConfig.saveWebsocket(obj.optString("websocketUrl"), obj.optString("websocketToken"))
            if (obj.has("websocketEnabled")) multiConfig.websocketEnabled = obj.getBoolean("websocketEnabled")

            if (obj.has("customWebhookUrl")) {
                multiConfig.saveCustomWebhook(
                    url = obj.optString("customWebhookUrl"),
                    headers = obj.optString("customWebhookHeaders"),
                    method = obj.optString("customWebhookMethod", "POST"),
                    contentType = obj.optString("customWebhookContentType", "application/json"),
                    bodyTemplate = obj.optString("customWebhookBodyTemplate", MultiForwardConfig.DEFAULT_CUSTOM_WEBHOOK_BODY),
                )
            }
            if (obj.has("customWebhookEnabled")) multiConfig.customWebhookEnabled = obj.getBoolean("customWebhookEnabled")

            if (obj.has("channelGroupMembers")) {
                val arr = obj.optJSONArray("channelGroupMembers")
                val set = buildSet {
                    if (arr != null) for (i in 0 until arr.length()) arr.optString(i).trim().takeIf(String::isNotBlank)?.let(::add)
                }
                multiConfig.saveChannelGroupMembers(set)
            }
            if (obj.has("channelGroupEnabled")) multiConfig.channelGroupEnabled = obj.getBoolean("channelGroupEnabled")

            if (obj.has("barkDeviceKey") || obj.has("barkServerUrl")) {
                multiConfig.saveBark(
                    serverUrl = obj.optString("barkServerUrl", multiConfig.barkServerUrl()),
                    deviceKey = obj.optString("barkDeviceKey"),
                    level = obj.optString("barkLevel", multiConfig.barkLevel),
                    group = obj.optString("barkGroup", multiConfig.barkGroup),
                    autoCopyCode = obj.optBoolean("barkAutoCopyCode", multiConfig.barkAutoCopyCode),
                )
            }
            if (obj.has("barkEnabled")) multiConfig.barkEnabled = obj.getBoolean("barkEnabled")

            if (obj.has("gotifyServerUrl") || obj.has("gotifyToken")) {
                multiConfig.saveGotify(obj.optString("gotifyServerUrl"), obj.optString("gotifyToken"))
            }
            if (obj.has("gotifyEnabled")) multiConfig.gotifyEnabled = obj.getBoolean("gotifyEnabled")

            if (obj.has("smsDirectPhone")) multiConfig.saveSmsDirect(obj.optString("smsDirectPhone"))
            if (obj.has("smsDirectEnabled")) multiConfig.smsDirectEnabled = obj.getBoolean("smsDirectEnabled")

            if (obj.has("dingTalkRemoteClientId")) {
                multiConfig.saveDingTalkRemoteControl(
                    obj.optString("dingTalkRemoteClientId"),
                    obj.optString("dingTalkRemoteClientSecret"),
                    obj.optString("dingTalkRemoteCustomPrefix"),
                )
            }
            if (obj.has("dingTalkRemoteControlEnabled")) multiConfig.dingTalkRemoteControlEnabled = obj.getBoolean("dingTalkRemoteControlEnabled")
            if (obj.has("dingTalkRemoteSendSimMode")) multiConfig.dingTalkRemoteSendSimMode = obj.getInt("dingTalkRemoteSendSimMode")

            if (obj.has("feishuRemoteAppId")) {
                multiConfig.saveFeishuRemoteControl(
                    obj.optString("feishuRemoteAppId"),
                    obj.optString("feishuRemoteAppSecret"),
                    obj.optString("feishuRemoteCustomPrefix"),
                )
            }
            if (obj.has("feishuRemoteControlEnabled")) multiConfig.feishuRemoteControlEnabled = obj.getBoolean("feishuRemoteControlEnabled")
            if (obj.has("feishuRemoteSendSimMode")) multiConfig.feishuRemoteSendSimMode = obj.getInt("feishuRemoteSendSimMode")

            if (obj.has("weComRemoteBotId")) {
                multiConfig.saveWeComRemoteControl(
                    obj.optString("weComRemoteBotId"),
                    obj.optString("weComRemoteSecret"),
                    obj.optString("weComRemoteChatId"),
                    obj.optString("weComRemoteCustomPrefix"),
                )
            }
            if (obj.has("weComRemoteControlEnabled")) multiConfig.weComRemoteControlEnabled = obj.getBoolean("weComRemoteControlEnabled")
            if (obj.has("weComRemoteSendSimMode")) multiConfig.weComRemoteSendSimMode = obj.getInt("weComRemoteSendSimMode")

            if (obj.has("telegramRemoteBotToken")) {
                multiConfig.saveTelegramRemoteControl(
                    obj.optString("telegramRemoteBotToken"),
                    obj.optString("telegramRemoteChatId"),
                    obj.optString("telegramRemoteCustomHost"),
                    obj.optString("telegramRemoteAuthorizedUsers"),
                    obj.optString("telegramRemoteCustomPrefix"),
                )
            }
            if (obj.has("telegramRemoteControlEnabled")) multiConfig.telegramRemoteControlEnabled = obj.getBoolean("telegramRemoteControlEnabled")
            if (obj.has("telegramRemoteSendSimMode")) multiConfig.telegramRemoteSendSimMode = obj.getInt("telegramRemoteSendSimMode")

            if (obj.has("emailRemoteHost")) {
                multiConfig.saveEmailRemoteControl(
                    obj.optString("emailRemoteHost"),
                    obj.optInt("emailRemotePort", 993),
                    obj.optString("emailRemoteUser"),
                    obj.optString("emailRemotePassword"),
                    obj.optString("emailRemoteAuthorizedSenders"),
                    obj.optInt("emailRemoteSecurity", MultiForwardConfig.EMAIL_SECURITY_SSL),
                    obj.optString("emailRemoteCustomPrefix"),
                )
            }
            if (obj.has("emailRemoteControlEnabled")) multiConfig.emailRemoteControlEnabled = obj.getBoolean("emailRemoteControlEnabled")
            if (obj.has("emailRemoteSendSimMode")) multiConfig.emailRemoteSendSimMode = obj.getInt("emailRemoteSendSimMode")

            if (obj.has("websocketRemoteUrl")) {
                multiConfig.saveWebSocketRemoteControl(
                    obj.optString("websocketRemoteUrl"),
                    obj.optString("websocketRemoteToken"),
                    obj.optString("websocketRemoteCustomPrefix"),
                )
            }
            if (obj.has("websocketRemoteControlEnabled")) multiConfig.websocketRemoteControlEnabled = obj.getBoolean("websocketRemoteControlEnabled")
            if (obj.has("websocketRemoteSendSimMode")) multiConfig.websocketRemoteSendSimMode = obj.getInt("websocketRemoteSendSimMode")

            if (obj.has("customTemplate")) multiConfig.customTemplate = obj.optString("customTemplate")
            if (obj.has("templateMode")) multiConfig.templateMode = obj.getInt("templateMode")
        }

        if (root.has("pushPlus")) {
            val pushPlusConfig = PushPlusConfig(context)
            val obj = root.getJSONObject("pushPlus")
            if (obj.has("token")) pushPlusConfig.saveToken(obj.optString("token"))
            if (obj.has("titlePrefix")) pushPlusConfig.titlePrefix = obj.optString("titlePrefix")
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
            if (obj.has("authorizedNumbers")) remoteConfig.authorizedNumbers = obj.optString("authorizedNumbers")
            if (obj.has("customPrefix")) remoteConfig.customPrefix = obj.optString("customPrefix")
        }

        // Runtime reconnect occurs only after the journal is committed or rolled back.
        true
    }.getOrDefault(false)
}
