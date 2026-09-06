package org.fossify.messages.remote.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.messaging.SubscriptionResolver
import org.fossify.messages.remote.RemoteSmsCommandConfig
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 远程指令来源类型
 */
enum class RemoteSourceType(val label: String, val emoji: String) {
    SMS("短信指令", "📱"),
    TELEGRAM("Telegram Bot", "✈️"),
    DINGTALK("钉钉 Stream", "🤖"),
    FEISHU("飞书长连接", "🕊️"),
    EMAIL("邮箱 IMAP", "📧"),
    WEBSOCKET("WebSocket 客户端", "🔌");

    companion object {
        fun fromString(value: String): RemoteSourceType? = entries.firstOrNull {
            it.name.equals(value, ignoreCase = true)
        }
    }
}

/**
 * 远程来源连接/就绪状态（真实状态，拒绝伪装）
 */
enum class RemoteSourceConnectionState(val label: String) {
    DISABLED("未启用"),
    CONFIG_REQUIRED("待配置凭据"),
    CONNECTING("连接/认证中"),
    AUTHENTICATED("凭证有效"),
    READY("就绪 · 监听中"),
    DEGRADED("降级服务"),
    ERROR("连接异常")
}

/**
 * 远程指令来源实例
 */
data class RemoteSourceInstance(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val type: RemoteSourceType,
    val enabled: Boolean = true,
    val connectionState: RemoteSourceConnectionState = RemoteSourceConnectionState.CONFIG_REQUIRED,
    val lastConnectedAt: Long = 0L,
    val lastMessageAt: Long = 0L,
    val lastErrorCode: Int = 0,
    val lastErrorMessage: String = "",
    val customCommandPrefix: String = "",
    val whitelistEnabled: Boolean = false,
    val authorizedUsers: Set<String> = emptySet(),
    val authorizedGroups: Set<String> = emptySet(),
    val requireMention: Boolean = false,
    val defaultSimMode: Int = SubscriptionResolver.MODE_FOLLOW_RECEIVE,
    val quietHoursEnabled: Boolean = false,
    val quietHoursStart: Int = 23,
    val quietHoursEnd: Int = 7,
    val hourlyLimit: Int = 10,
    val dailyLimit: Int = 50,
    val configJson: String = "{}"
) {
    fun optString(key: String, default: String = ""): String = runCatching {
        JSONObject(configJson).optString(key, default)
    }.getOrDefault(default)

    fun optInt(key: String, default: Int = 0): Int = runCatching {
        JSONObject(configJson).optInt(key, default)
    }.getOrDefault(default)

    fun optBoolean(key: String, default: Boolean = false): Boolean = runCatching {
        JSONObject(configJson).optBoolean(key, default)
    }.getOrDefault(default)

    fun copyWithConfig(modifier: JSONObject.() -> Unit): RemoteSourceInstance {
        val json = runCatching { JSONObject(configJson) }.getOrDefault(JSONObject())
        json.modifier()
        return copy(configJson = json.toString())
    }

    fun hasValidCredentials(): Boolean {
        val json = runCatching { JSONObject(configJson) }.getOrDefault(JSONObject())
        fun credential(key: String): String = json.optString(key).takeUnless { it.startsWith("ENC:") }.orEmpty()
        return when (type) {
            RemoteSourceType.SMS -> true // 短信指令无需外部网络凭证
            RemoteSourceType.TELEGRAM -> credential("botToken").isNotBlank()
            RemoteSourceType.DINGTALK -> credential("clientId").isNotBlank() && credential("clientSecret").isNotBlank()
            RemoteSourceType.FEISHU -> credential("appId").isNotBlank() && credential("appSecret").isNotBlank()
            RemoteSourceType.EMAIL -> credential("host").isNotBlank() && credential("user").isNotBlank() && credential("pass").isNotBlank()
            // 自建服务可以不启用鉴权；Token 与编辑页面保持为可选。
            RemoteSourceType.WEBSOCKET -> credential("url").isNotBlank()
        }
    }
}

/**
 * 远程来源独立仓储
 */
class RemoteSourceRepository internal constructor(
    context: Context? = null,
    customPrefs: SharedPreferences? = null
) {
    private val appContext: Context? = context?.applicationContext
    private val prefs: SharedPreferences = customPrefs 
        ?: appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?: error("Context or customPrefs must be provided")

    private val _sourcesFlow = MutableStateFlow<List<RemoteSourceInstance>>(emptyList())
    val sourcesFlow: StateFlow<List<RemoteSourceInstance>> = _sourcesFlow.asStateFlow()

    init {
        loadFromPrefs()
        // 升级后立即把旧版远程来源中的敏感字段重存为 Keystore 密文。
        if (_sourcesFlow.value.isNotEmpty()) {
            persist(_sourcesFlow.value)
        }
    }

    @Synchronized
    private fun loadFromPrefs() {
        val raw = prefs.getString(KEY_SOURCES, "[]").orEmpty()
        val list = parseJson(raw)
        _sourcesFlow.value = list
        val storedCount = runCatching { JSONArray(raw).length() }.getOrDefault(list.size)
        if (storedCount != list.size) {
            // 清除已弃用或未知类型的持久化实例，避免旧凭据继续滞留或被误识别。
            persist(list)
        }
    }

    @Synchronized
    private fun persist(list: List<RemoteSourceInstance>) {
        val array = JSONArray()
        list.forEach { item ->
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("type", item.type.name)
                put("enabled", item.enabled)
                put("connectionState", item.connectionState.name)
                put("lastConnectedAt", item.lastConnectedAt)
                put("lastMessageAt", item.lastMessageAt)
                put("lastErrorCode", item.lastErrorCode)
                put("lastErrorMessage", item.lastErrorMessage)
                put("customCommandPrefix", item.customCommandPrefix)
                put("whitelistEnabled", item.whitelistEnabled)
                put("authorizedUsers", JSONArray(item.authorizedUsers.toList()))
                put("authorizedGroups", JSONArray(item.authorizedGroups.toList()))
                put("requireMention", item.requireMention)
                put("defaultSimMode", item.defaultSimMode)
                put("quietHoursEnabled", item.quietHoursEnabled)
                put("quietHoursStart", item.quietHoursStart)
                put("quietHoursEnd", item.quietHoursEnd)
                put("hourlyLimit", item.hourlyLimit)
                put("dailyLimit", item.dailyLimit)
                put("configJson", encryptSensitiveConfig(item.configJson))
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_SOURCES, array.toString()).apply()
        _sourcesFlow.value = list
    }

    fun getAllSources(): List<RemoteSourceInstance> = _sourcesFlow.value

    fun getEnabledSources(): List<RemoteSourceInstance> = _sourcesFlow.value.filter { it.enabled }

    fun getSourceById(id: String): RemoteSourceInstance? = _sourcesFlow.value.firstOrNull { it.id == id }

    fun getSourcesByType(type: RemoteSourceType): List<RemoteSourceInstance> =
        _sourcesFlow.value.filter { it.type == type }

    @Synchronized
    fun saveSource(instance: RemoteSourceInstance) {
        val current = _sourcesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == instance.id }
        val updatedInstance = if (!instance.hasValidCredentials() && instance.type != RemoteSourceType.SMS) {
            instance.copy(connectionState = RemoteSourceConnectionState.CONFIG_REQUIRED)
        } else {
            instance
        }
        if (index >= 0) {
            current[index] = updatedInstance
        } else {
            current.add(updatedInstance)
        }
        persist(current)
        appContext?.let { org.fossify.messages.remote.runtime.RemoteSourceRuntimeManager.getInstance(it).sync() }
    }

    @Synchronized
    fun deleteSource(id: String) {
        val updated = _sourcesFlow.value.filterNot { it.id == id }
        persist(updated)
        appContext?.let { org.fossify.messages.remote.runtime.RemoteSourceRuntimeManager.getInstance(it).sync() }
    }

    @Synchronized
    fun toggleEnabled(id: String, enabled: Boolean) {
        val updated = _sourcesFlow.value.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }
        persist(updated)
        appContext?.let { org.fossify.messages.remote.runtime.RemoteSourceRuntimeManager.getInstance(it).sync() }
    }

    @Synchronized
    fun updateConnectionState(
        id: String,
        state: RemoteSourceConnectionState,
        errorCode: Int = 0,
        errorMessage: String = ""
    ) {
        val updated = _sourcesFlow.value.map {
            if (it.id == id) {
                it.copy(
                    connectionState = state,
                    lastErrorCode = errorCode,
                    lastErrorMessage = errorMessage,
                    lastConnectedAt = if (state == RemoteSourceConnectionState.READY || state == RemoteSourceConnectionState.AUTHENTICATED) {
                        System.currentTimeMillis()
                    } else it.lastConnectedAt
                )
            } else it
        }
        persist(updated)
    }

    @Synchronized
    fun recordMessageReceived(id: String) {
        val now = System.currentTimeMillis()
        val recent = readRateTimestamps(id).filter { now - it < DAY_MS } + now
        prefs.edit().putString(rateKey(id), JSONArray(recent).toString()).apply()
        val updated = _sourcesFlow.value.map {
            if (it.id == id) it.copy(lastMessageAt = now) else it
        }
        persist(updated)
    }

    fun isRateLimited(id: String): Boolean {
        val instance = getSourceById(id) ?: return true
        val now = System.currentTimeMillis()
        val recent = readRateTimestamps(id).filter { now - it < DAY_MS }
        val hourlyCount = recent.count { now - it < HOUR_MS }
        val dailyCount = recent.size
        val hourlyBlocked = instance.hourlyLimit > 0 && hourlyCount >= instance.hourlyLimit
        val dailyBlocked = instance.dailyLimit > 0 && dailyCount >= instance.dailyLimit
        return hourlyBlocked || dailyBlocked
    }

    /** Atomically checks the current limits and reserves one accepted command slot. */
    @Synchronized
    fun tryRecordMessageReceived(id: String): Boolean {
        val instance = getSourceById(id) ?: return false
        val now = System.currentTimeMillis()
        val recent = readRateTimestamps(id).filter { now - it < DAY_MS }
        val hourlyBlocked = instance.hourlyLimit > 0 && recent.count { now - it < HOUR_MS } >= instance.hourlyLimit
        val dailyBlocked = instance.dailyLimit > 0 && recent.size >= instance.dailyLimit
        if (hourlyBlocked || dailyBlocked) return false

        prefs.edit().putString(rateKey(id), JSONArray(recent + now).toString()).apply()
        persist(_sourcesFlow.value.map {
            if (it.id == id) it.copy(lastMessageAt = now) else it
        })
        return true
    }

    private fun rateKey(id: String) = "remote_rate_$id"

    private fun readRateTimestamps(id: String): List<Long> = runCatching {
        val array = JSONArray(prefs.getString(rateKey(id), "[]"))
        buildList {
            for (index in 0 until array.length()) add(array.optLong(index))
        }.filter { it > 0L }
    }.getOrDefault(emptyList())

    // ==========================================
    // 旧版扁平配置检测与一键幂等迁移
    // ==========================================

    data class LegacySourceCandidate(
        val type: RemoteSourceType,
        val defaultName: String,
        val credentialsSummary: String,
        val createInstance: () -> RemoteSourceInstance
    )

    fun detectLegacyConfiguredSources(
        customMultiConfig: MultiForwardConfig? = null,
        customSmsConfig: RemoteSmsCommandConfig? = null
    ): List<LegacySourceCandidate> {
        val multiConfig = customMultiConfig ?: appContext?.let { MultiForwardConfig(it) } ?: return emptyList()
        val smsConfig = customSmsConfig ?: appContext?.let { RemoteSmsCommandConfig(it) } ?: return emptyList()
        val candidates = mutableListOf<LegacySourceCandidate>()

        // 1. 短信远程指令
        if (smsConfig.enabled || smsConfig.authorizedList().isNotEmpty() || smsConfig.customPrefix.isNotBlank()) {
            candidates.add(
                LegacySourceCandidate(
                    type = RemoteSourceType.SMS,
                    defaultName = "短信远程指令",
                    credentialsSummary = "授权号码 ${smsConfig.authorizedList().size} 个",
                    createInstance = {
                        RemoteSourceInstance(
                            id = "legacy_remote_sms",
                            name = "短信远程指令",
                            type = RemoteSourceType.SMS,
                            enabled = smsConfig.enabled,
                            connectionState = if (smsConfig.enabled) RemoteSourceConnectionState.READY else RemoteSourceConnectionState.DISABLED,
                            customCommandPrefix = smsConfig.customPrefix,
                            whitelistEnabled = smsConfig.authorizedList().isNotEmpty(),
                            authorizedUsers = smsConfig.authorizedList().toSet(),
                            defaultSimMode = SubscriptionResolver.MODE_FOLLOW_RECEIVE
                        )
                    }
                )
            )
        }

        // 2. Telegram Bot
        val tgToken = multiConfig.telegramRemoteBotToken().trim()
        if (tgToken.isNotBlank()) {
            candidates.add(
                LegacySourceCandidate(
                    type = RemoteSourceType.TELEGRAM,
                    defaultName = "Telegram 远程指令",
                    credentialsSummary = "Token: ${tgToken.take(6)}***",
                    createInstance = {
                        val authUsers = multiConfig.telegramRemoteAuthorizedUsers().split('\n', ',', ';', '，', '；')
                            .map(String::trim).filter(String::isNotBlank).toSet()
                        val json = JSONObject().apply {
                            put("botToken", tgToken)
                            put("chatId", multiConfig.telegramRemoteChatId().trim())
                            put("customHost", multiConfig.telegramRemoteCustomHost().trim())
                        }
                        RemoteSourceInstance(
                            id = "legacy_remote_telegram",
                            name = "Telegram 远程指令",
                            type = RemoteSourceType.TELEGRAM,
                            enabled = multiConfig.telegramRemoteControlEnabled,
                            connectionState = if (multiConfig.telegramRemoteControlEnabled) RemoteSourceConnectionState.CONNECTING else RemoteSourceConnectionState.DISABLED,
                            customCommandPrefix = multiConfig.telegramRemoteCustomPrefix(),
                            whitelistEnabled = authUsers.isNotEmpty(),
                            authorizedUsers = authUsers,
                            defaultSimMode = multiConfig.telegramRemoteSendSimMode,
                            configJson = json.toString()
                        )
                    }
                )
            )
        }

        // 3. 钉钉 Stream
        val dingClientId = multiConfig.dingTalkRemoteClientId().trim()
        val dingSecret = multiConfig.dingTalkRemoteClientSecret().trim()
        if (dingClientId.isNotBlank() && dingSecret.isNotBlank()) {
            candidates.add(
                LegacySourceCandidate(
                    type = RemoteSourceType.DINGTALK,
                    defaultName = "钉钉 Stream 指令",
                    credentialsSummary = "Client ID: ${dingClientId.take(8)}***",
                    createInstance = {
                        val json = JSONObject().apply {
                            put("clientId", dingClientId)
                            put("clientSecret", dingSecret)
                        }
                        RemoteSourceInstance(
                            id = "legacy_remote_dingtalk",
                            name = "钉钉 Stream 指令",
                            type = RemoteSourceType.DINGTALK,
                            enabled = multiConfig.dingTalkRemoteControlEnabled,
                            connectionState = if (multiConfig.dingTalkRemoteControlEnabled) RemoteSourceConnectionState.CONNECTING else RemoteSourceConnectionState.DISABLED,
                            customCommandPrefix = multiConfig.dingTalkRemoteCustomPrefix(),
                            defaultSimMode = multiConfig.dingTalkRemoteSendSimMode,
                            configJson = json.toString()
                        )
                    }
                )
            )
        }

        // 4. 飞书 Stream
        val feishuAppId = multiConfig.feishuRemoteAppId().trim()
        val feishuSecret = multiConfig.feishuRemoteAppSecret().trim()
        if (feishuAppId.isNotBlank() && feishuSecret.isNotBlank()) {
            candidates.add(
                LegacySourceCandidate(
                    type = RemoteSourceType.FEISHU,
                    defaultName = "飞书 Stream 指令",
                    credentialsSummary = "App ID: $feishuAppId",
                    createInstance = {
                        val json = JSONObject().apply {
                            put("appId", feishuAppId)
                            put("appSecret", feishuSecret)
                        }
                        RemoteSourceInstance(
                            id = "legacy_remote_feishu",
                            name = "飞书 Stream 指令",
                            type = RemoteSourceType.FEISHU,
                            enabled = multiConfig.feishuRemoteControlEnabled,
                            connectionState = if (multiConfig.feishuRemoteControlEnabled) RemoteSourceConnectionState.CONNECTING else RemoteSourceConnectionState.DISABLED,
                            customCommandPrefix = multiConfig.feishuRemoteCustomPrefix(),
                            defaultSimMode = multiConfig.feishuRemoteSendSimMode,
                            configJson = json.toString()
                        )
                    }
                )
            )
        }

        // 5. 邮箱 IMAP
        val emailHost = multiConfig.emailRemoteHost().trim()
        val emailUser = multiConfig.emailRemoteUser().trim()
        val emailPass = multiConfig.emailRemotePassword().trim()
        if (emailHost.isNotBlank() && emailUser.isNotBlank() && emailPass.isNotBlank()) {
            candidates.add(
                LegacySourceCandidate(
                    type = RemoteSourceType.EMAIL,
                    defaultName = "邮箱 IMAP 远程指令",
                    credentialsSummary = "$emailUser @ $emailHost",
                    createInstance = {
                        val authSenders = multiConfig.emailRemoteAuthorizedSenders().split('\n', ',', ';', '，', '；')
                            .map(String::trim).filter(String::isNotBlank).toSet()
                        val json = JSONObject().apply {
                            put("host", emailHost)
                            put("port", multiConfig.emailRemotePort())
                            put("user", emailUser)
                            put("pass", emailPass)
                            put("security", multiConfig.emailRemoteSecurity)
                        }
                        RemoteSourceInstance(
                            id = "legacy_remote_email",
                            name = "邮箱 IMAP 远程指令",
                            type = RemoteSourceType.EMAIL,
                            enabled = multiConfig.emailRemoteControlEnabled,
                            connectionState = if (multiConfig.emailRemoteControlEnabled) RemoteSourceConnectionState.CONNECTING else RemoteSourceConnectionState.DISABLED,
                            customCommandPrefix = multiConfig.emailRemoteCustomPrefix(),
                            whitelistEnabled = authSenders.isNotEmpty(),
                            authorizedUsers = authSenders,
                            defaultSimMode = multiConfig.emailRemoteSendSimMode,
                            configJson = json.toString()
                        )
                    }
                )
            )
        }

        // 7. WebSocket
        val wsUrl = multiConfig.websocketRemoteUrl().trim()
        if (wsUrl.isNotBlank()) {
            candidates.add(
                LegacySourceCandidate(
                    type = RemoteSourceType.WEBSOCKET,
                    defaultName = "WebSocket 远程客户端",
                    credentialsSummary = "URL: ${wsUrl.take(24)}...",
                    createInstance = {
                        val json = JSONObject().apply {
                            put("url", wsUrl)
                            put("token", multiConfig.websocketRemoteToken().trim())
                        }
                        RemoteSourceInstance(
                            id = "legacy_remote_websocket",
                            name = "WebSocket 远程客户端",
                            type = RemoteSourceType.WEBSOCKET,
                            enabled = multiConfig.websocketRemoteControlEnabled,
                            connectionState = if (multiConfig.websocketRemoteControlEnabled) RemoteSourceConnectionState.CONNECTING else RemoteSourceConnectionState.DISABLED,
                            defaultSimMode = multiConfig.websocketRemoteSendSimMode,
                            configJson = json.toString()
                        )
                    }
                )
            )
        }

        return candidates
    }

    fun hasLegacyConfigToMigrate(
        customMultiConfig: MultiForwardConfig? = null,
        customSmsConfig: RemoteSmsCommandConfig? = null
    ): Boolean {
        val candidates = detectLegacyConfiguredSources(customMultiConfig, customSmsConfig)
        if (candidates.isEmpty()) return false
        val existingIds = _sourcesFlow.value.map { it.id }.toSet()
        return candidates.any { candidate ->
            val instance = candidate.createInstance()
            !existingIds.contains(instance.id)
        }
    }

    @Synchronized
    fun importLegacySources(
        customMultiConfig: MultiForwardConfig? = null,
        customSmsConfig: RemoteSmsCommandConfig? = null
    ): Int {
        val candidates = detectLegacyConfiguredSources(customMultiConfig, customSmsConfig)
        if (candidates.isEmpty()) return 0

        val current = _sourcesFlow.value.toMutableList()
        var importedCount = 0

        for (candidate in candidates) {
            val instance = candidate.createInstance()
            val exists = current.any { it.id == instance.id || (it.type == instance.type && it.configJson == instance.configJson) }
            if (!exists) {
                current.add(instance)
                importedCount++
            }
        }

        if (importedCount > 0) {
            persist(current)
            appContext?.let { org.fossify.messages.remote.runtime.RemoteSourceRuntimeManager.getInstance(it).sync() }
        }
        return importedCount
    }

    /** 将经典版单实例配置同步为开发版 legacy_* 实例，不覆盖开发版创建的其他实例。 */
    @Synchronized
    fun syncLegacySourcesFromClassic() {
        val incoming = detectLegacyConfiguredSources()
            .map { it.createInstance() }
            .associateBy { it.id }
        val legacyIds = setOf(
            "legacy_remote_sms",
            "legacy_remote_telegram",
            "legacy_remote_dingtalk",
            "legacy_remote_feishu",
            "legacy_remote_email",
            "legacy_remote_websocket"
        )
        val updated = _sourcesFlow.value
            .filterNot { it.id in legacyIds && it.id !in incoming }
            .toMutableList()

        incoming.values.forEach { incomingInstance ->
            val index = updated.indexOfFirst { it.id == incomingInstance.id }
            if (index >= 0) {
                val existing = updated[index]
                val runtimeConfigChanged = existing.enabled != incomingInstance.enabled ||
                    existing.configJson != incomingInstance.configJson ||
                    existing.customCommandPrefix != incomingInstance.customCommandPrefix ||
                    existing.defaultSimMode != incomingInstance.defaultSimMode
                val classicOwnsUserList = incomingInstance.type in setOf(
                    RemoteSourceType.SMS,
                    RemoteSourceType.TELEGRAM,
                    RemoteSourceType.EMAIL
                )
                updated[index] = incomingInstance.copy(
                    connectionState = when {
                        !incomingInstance.enabled -> RemoteSourceConnectionState.DISABLED
                        runtimeConfigChanged && incomingInstance.type == RemoteSourceType.SMS -> RemoteSourceConnectionState.READY
                        runtimeConfigChanged -> RemoteSourceConnectionState.CONNECTING
                        else -> existing.connectionState
                    },
                    lastConnectedAt = existing.lastConnectedAt,
                    lastMessageAt = existing.lastMessageAt,
                    lastErrorCode = if (runtimeConfigChanged) 0 else existing.lastErrorCode,
                    lastErrorMessage = if (runtimeConfigChanged) "" else existing.lastErrorMessage,
                    whitelistEnabled = existing.whitelistEnabled,
                    authorizedUsers = if (classicOwnsUserList) incomingInstance.authorizedUsers else existing.authorizedUsers,
                    authorizedGroups = existing.authorizedGroups,
                    requireMention = existing.requireMention,
                    quietHoursEnabled = existing.quietHoursEnabled,
                    quietHoursStart = existing.quietHoursStart,
                    quietHoursEnd = existing.quietHoursEnd,
                    hourlyLimit = existing.hourlyLimit,
                    dailyLimit = existing.dailyLimit
                )
            } else {
                updated.add(incomingInstance)
            }
        }

        if (updated != _sourcesFlow.value) {
            persist(updated)
            appContext?.let {
                org.fossify.messages.remote.runtime.RemoteSourceRuntimeManager.getInstance(it).sync()
            }
        }
    }

    private fun parseJson(raw: String): List<RemoteSourceInstance> = runCatching {
        val array = JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id").ifBlank { UUID.randomUUID().toString() }
                val name = obj.optString("name", "未命名来源")
                // 旧版已弃用类型直接跳过，严禁默认转换成短信来源。
                val type = RemoteSourceType.fromString(obj.optString("type")) ?: continue
                val enabled = obj.optBoolean("enabled", true)
                val state = runCatching {
                    RemoteSourceConnectionState.valueOf(obj.optString("connectionState"))
                }.getOrDefault(RemoteSourceConnectionState.CONFIG_REQUIRED)
                val lastConnectedAt = obj.optLong("lastConnectedAt", 0L)
                val lastMessageAt = obj.optLong("lastMessageAt", 0L)
                val lastErrorCode = obj.optInt("lastErrorCode", 0)
                val lastErrorMessage = obj.optString("lastErrorMessage", "")
                val customPrefix = obj.optString("customCommandPrefix", "")
                // 旧数据沿用安全默认：未存储此字段时继续要求白名单。
                val whitelistEnabled = obj.optBoolean("whitelistEnabled", true)
                val authUsers = parseStringSet(obj.optJSONArray("authorizedUsers"))
                val authGroups = parseStringSet(obj.optJSONArray("authorizedGroups"))
                val requireMention = obj.optBoolean("requireMention", false)
                val defaultSim = obj.optInt("defaultSimMode", SubscriptionResolver.MODE_FOLLOW_RECEIVE)
                val quietEnabled = obj.optBoolean("quietHoursEnabled", false)
                val quietStart = obj.optInt("quietHoursStart", 23)
                val quietEnd = obj.optInt("quietHoursEnd", 7)
                val hourlyLimit = obj.optInt("hourlyLimit", 10)
                val dailyLimit = obj.optInt("dailyLimit", 50)
                val rawConfigJson = obj.optString("configJson", "{}")
                val configJson = decryptSensitiveConfig(rawConfigJson)

                add(
                    RemoteSourceInstance(
                        id = id,
                        name = name,
                        type = type,
                        enabled = enabled,
                        connectionState = state,
                        lastConnectedAt = lastConnectedAt,
                        lastMessageAt = lastMessageAt,
                        lastErrorCode = lastErrorCode,
                        lastErrorMessage = lastErrorMessage,
                        customCommandPrefix = customPrefix,
                        whitelistEnabled = whitelistEnabled,
                        authorizedUsers = authUsers,
                        authorizedGroups = authGroups,
                        requireMention = requireMention,
                        defaultSimMode = defaultSim,
                        quietHoursEnabled = quietEnabled,
                        quietHoursStart = quietStart,
                        quietHoursEnd = quietEnd,
                        hourlyLimit = hourlyLimit,
                        dailyLimit = dailyLimit,
                        configJson = configJson
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun parseStringSet(arr: JSONArray?): Set<String> {
        if (arr == null) return emptySet()
        return buildSet {
            for (i in 0 until arr.length()) {
                val s = arr.optString(i).trim()
                if (s.isNotBlank()) add(s)
            }
        }
    }

    /**
     * 对 configJson 中的敏感字段（如 secret, relaySharedSecret, botToken, pass, token 等）
     * 自动通过 AndroidKeyStore AES-GCM 硬件加密保护，杜绝明文写入 SharedPreferences
     */
    private fun encryptSensitiveConfig(rawConfig: String): String = runCatching {
        if (rawConfig.isBlank() || rawConfig == "{}") return rawConfig
        val json = JSONObject(rawConfig)
        val sensitiveKeys = listOf(
            "secret", "relaySharedSecret", "botToken", "clientSecret",
            "appSecret", "pass", "token"
        )
        sensitiveKeys.forEach { key ->
            if (json.has(key)) {
                val value = json.optString(key)
                if (value.isNotBlank() && !value.startsWith("ENC:")) {
                    val encrypted = org.fossify.messages.forwarding.ForwardingCipher.encrypt(value)
                    if (encrypted.isNotBlank()) {
                        json.put(key, "ENC:$encrypted")
                    }
                }
            }
        }
        json.toString()
    }.getOrDefault(rawConfig)

    private fun decryptSensitiveConfig(rawConfig: String): String = runCatching {
        if (rawConfig.isBlank() || rawConfig == "{}") return rawConfig
        val json = JSONObject(rawConfig)
        val sensitiveKeys = listOf(
            "secret", "relaySharedSecret", "botToken", "clientSecret",
            "appSecret", "pass", "token"
        )
        sensitiveKeys.forEach { key ->
            if (json.has(key)) {
                val value = json.optString(key)
                if (value.startsWith("ENC:")) {
                    val cipherText = value.removePrefix("ENC:")
                    val decrypted = org.fossify.messages.forwarding.ForwardingCipher.decrypt(cipherText)
                    // 解密失败时保留 ENC 标记，防止下次持久化把密文本身再次加密。
                    json.put(key, if (decrypted.isNotBlank()) decrypted else value)
                }
            }
        }
        json.toString()
    }.getOrDefault(rawConfig)

    companion object {
        private const val PREFS_NAME = "remote_source_repository_prefs"
        private const val KEY_SOURCES = "remote_sources"
        private const val HOUR_MS = 60 * 60 * 1000L
        private const val DAY_MS = 24 * HOUR_MS

        @Volatile
        private var INSTANCE: RemoteSourceRepository? = null

        fun getInstance(context: Context): RemoteSourceRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: RemoteSourceRepository(context).also { INSTANCE = it }
            }
        }

        fun resetForTesting() {
            synchronized(this) {
                INSTANCE = null
            }
        }
    }
}
