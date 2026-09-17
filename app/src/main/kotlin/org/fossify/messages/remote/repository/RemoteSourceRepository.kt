@file:Suppress("SpellCheckingInspection")

package org.fossify.messages.remote.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fossify.messages.forwarding.AndroidKeystoreCipher
import org.fossify.messages.forwarding.CredentialCipher
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.repository.ChannelRepository
import org.fossify.messages.messaging.SubscriptionResolver
import org.fossify.messages.remote.NumberMatcher
import org.fossify.messages.remote.RemoteSmsCommandConfig
import org.fossify.messages.security.audit.SecurityAuditEventType
import org.fossify.messages.security.audit.SecurityAuditManager
import org.fossify.messages.security.crypto.CredentialHealth
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
    WECOM("企业微信长连接", "💬"),
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
    // 安全默认值：白名单默认开启。关闭白名单意味着任何人都可以用本机号码对外发短信。
    val whitelistEnabled: Boolean = true,
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
            RemoteSourceType.WECOM -> credential("botId").isNotBlank() && credential("secret").isNotBlank()
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
    customPrefs: SharedPreferences? = null,
    customSmsCommandConfig: RemoteSmsCommandConfig? = null,
    // 凭据加解密实现可注入：生产走 AndroidKeystoreCipher；
    // 单元测试环境没有 AndroidKeyStore，只有注入直通实现才能覆盖
    // "加密失败即整批弃写"之外的正常读写路径（否则所有保存都会失败）。
    private val cipher: CredentialCipher = AndroidKeystoreCipher
) {
    private val appContext: Context? = context?.applicationContext
    private val prefs: SharedPreferences = customPrefs 
        ?: appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?: error("Context or customPrefs must be provided")
    // 存量名单回填的数据源。声明必须在 init{} 之前，保证 loadFromPrefs() 能用到。
    private val smsCommandConfig: RemoteSmsCommandConfig? = customSmsCommandConfig
        ?: appContext?.let { RemoteSmsCommandConfig(it) }

    private val _sourcesFlow = MutableStateFlow<List<RemoteSourceInstance>>(emptyList())
    val sourcesFlow: StateFlow<List<RemoteSourceInstance>> = _sourcesFlow.asStateFlow()

    /**
     * 白名单由历史发信记录**自动生成**过的来源 id。
     *
     * 这些来源的名单不是用户亲手填的，可能混入 P0-1 修复前"任何人都能发"时期留下的陌生号码，
     * 所以 UI 必须提示用户核对。只标记真正被回填过的来源，避免对所有来源产生噪音。
     * 标记仅用于提示，不阻断用户编辑或删除名单。
     */
    private val _autoBackfilledIds = MutableStateFlow<Set<String>>(emptySet())
    val autoBackfilledIds: StateFlow<Set<String>> = _autoBackfilledIds.asStateFlow()

    init {
        _autoBackfilledIds.value = readAutoBackfilledIds()
        loadFromPrefs()
        // 升级后立即把旧版远程来源中的敏感字段重存为 Keystore 密文。
        if (_sourcesFlow.value.isNotEmpty()) {
            persist(_sourcesFlow.value)
        }
    }

    @Synchronized
    private fun loadFromPrefs() {
        val raw = prefs.getString(KEY_SOURCES, "[]").orEmpty()
        val parsed = parseJson(raw)
        val list = enforceWhitelistSecurityDefault(parsed)
        _sourcesFlow.value = list
        val storedCount = runCatching { JSONArray(raw).length() }.getOrDefault(list.size)
        if (storedCount != list.size || list != parsed) {
            // 清除已弃用或未知类型的持久化实例，避免旧凭据继续滞留或被误识别；
            // 同时把白名单安全加固结果立即落库，避免重复计算。
            persist(list)
        }
    }

    /**
     * 存量名单自动回填（升级迁移）：把「白名单关闭且名单为空」的**短信来源**
     * 用历史发件人记录补充完整名单并开启白名单。
     *
     * 两个分支：
     * - 能取到历史发件人 → `copy(whitelistEnabled = true, authorizedUsers = 历史号码)`：
     *   既收敛了"任何人可发"的风险，又不会中断功能。
     * - 取不到历史发件人（含非短信来源 —— 只有短信路径会落限流记录）→ **保持关闭、接受全部**，
     *   与旧版行为一致，功能同样不中断，由设置页的橙色警告标签提示。
     *
     * 硬约束：**绝不允许出现"名单为空 + 白名单开启"的失效态**（那会被
     * AUTHORIZED_USERS_REQUIRED 全部拒绝，用户必须手动补充名单才能恢复）。
     *
     * 「白名单关闭但名单非空」的来源不做迁移 —— 那是用户在填写了名单的前提下显式选择
     * "接受所有用户"，迁移会让其存量配置突然失效；这类来源仅在设置页以警告色提示。
     *
     * 该函数在 [loadFromPrefs] 与 [persist] 两处调用，等价于在 synchronized 内做
     * read-modify-write；回填是一次性的（回填后名单非空，下次不再命中条件）。
     *
     * @return 加固后的实例列表；无变化时返回原列表。
     */
    private fun enforceWhitelistSecurityDefault(list: List<RemoteSourceInstance>): List<RemoteSourceInstance> {
        if (list.none(::needsWhitelistBackfill)) return list
        // 惰性读取：只有确实存在待回填来源时才去读 prefs，避免每次 persist 都白白读盘。
        val knownRequesters = smsCommandConfig?.knownRequesters().orEmpty()
        if (knownRequesters.isEmpty()) return list
        val backfilledIds = linkedSetOf<String>()
        val migrated = list.map { instance ->
            if (needsWhitelistBackfill(instance)) {
                backfilledIds.add(instance.id)
                instance.copy(whitelistEnabled = true, authorizedUsers = knownRequesters)
            } else {
                instance
            }
        }
        markAutoBackfilled(backfilledIds)
        return migrated
    }

    /** 记录"名单由历史记录自动生成"的来源，供 UI 提示核对。 */
    private fun markAutoBackfilled(ids: Set<String>) {
        if (ids.isEmpty()) return
        val merged = _autoBackfilledIds.value + ids
        _autoBackfilledIds.value = merged
        prefs.edit()
            .putString(KEY_AUTO_BACKFILLED_IDS, JSONArray(merged.toList()).toString())
            .apply()
    }

    private fun readAutoBackfilledIds(): Set<String> = runCatching {
        parseStringSet(JSONArray(prefs.getString(KEY_AUTO_BACKFILLED_IDS, "[]").orEmpty()))
    }.getOrDefault(emptySet())

    /**
     * 是否属于"关闭白名单且名单为空"、需要用历史发件人回填的状态。
     * 仅限短信来源：历史发件人记录来自短信指令的限流表，回填到非短信来源（其名单是
     * 用户 ID / 邮箱，不是号码）只会让该来源永远匹配不上，反而制造出失效态。
     */
    private fun needsWhitelistBackfill(instance: RemoteSourceInstance): Boolean =
        !instance.whitelistEnabled &&
            instance.authorizedUsers.isEmpty() &&
            instance.type == RemoteSourceType.SMS

    /**
     * 白名单已启用但未配置任何授权用户的来源。
     * 这些来源在运行时会被 [org.fossify.messages.remote.RemoteCommandProcessor] 以
     * AUTHORIZED_USERS_REQUIRED 拒绝，UI 需要常驻提示引导用户补充完整名单。
     */
    fun getSourcesMissingAuthorizedUsers(): List<RemoteSourceInstance> =
        _sourcesFlow.value.filter { it.whitelistEnabled && it.authorizedUsers.isEmpty() }

    /**
     * 落盘全部来源。
     *
     * @return 是否成功落盘。**false 表示加密失败已整批弃写**：磁盘与内存态都保持原值。
     *         绝不允许降级为"写明文"——那正是 P2 问题的形态：
     *         用户看到保存成功，prefs 里却躺着明文 token。
     *         与 `ChannelRepository.saveChannelInstances` 的"整批中止"语义保持一致。
     */
    @Synchronized
    private fun persist(list: List<RemoteSourceInstance>): Boolean {
        val safeList = enforceWhitelistSecurityDefault(list)
        val array = JSONArray()
        for (item in safeList) {
            // 任一来源的敏感字段加密失败 ⇒ 整批中止，连"部分字段写明文"的混合态都不产生。
            val encryptedConfig = encryptSensitiveConfig(item.configJson) ?: return false
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
                put("configJson", encryptedConfig)
            }
            array.put(obj)
        }
        // 全部来源都加密成功后才统一落盘并更新内存态，磁盘与内存永远同构。
        prefs.edit().putString(KEY_SOURCES, array.toString()).apply()
        _sourcesFlow.value = safeList
        return true
    }

    fun getAllSources(): List<RemoteSourceInstance> = _sourcesFlow.value

    fun getEnabledSources(): List<RemoteSourceInstance> = _sourcesFlow.value.filter { it.enabled }

    fun getSourceById(id: String): RemoteSourceInstance? = _sourcesFlow.value.firstOrNull { it.id == id }

    fun getSourcesByType(type: RemoteSourceType): List<RemoteSourceInstance> =
        _sourcesFlow.value.filter { it.type == type }

    fun saveSource(instance: RemoteSourceInstance) {
        val persisted = saveSourceLocked(instance)
        // 落盘失败（如加密失败）时不得联动建通道：来源根本没保存成功，
        // 拿它的 botId/chatId 去建联动通道会留下一个指向不存在来源的孤儿通道。
        if (persisted && instance.type == RemoteSourceType.WECOM) {
            appContext?.let { ctx ->
                val botId = instance.optString("botId")
                val chatId = instance.optString("chatId")
                ChannelRepository.getInstance(ctx).syncLinkedWeComStreamChannel(
                    sourceInstanceId = instance.id,
                    botId = botId,
                    chatId = chatId,
                    sourceName = "${instance.name} (长连接)"
                )
            }
        }
        syncRuntime()
    }

    /**
     * @return 是否成功落盘；false 表示加密失败已弃写，内存态与磁盘都保持原值。
     */
    @Synchronized
    private fun saveSourceLocked(instance: RemoteSourceInstance): Boolean {
        val current = _sourcesFlow.value.toMutableList()
        val index = current.indexOfFirst { it.id == instance.id }
        // 短信来源的白名单条目落库前统一归一化，保证与运行时比较的两侧同构。
        val securedInstance = if (instance.type == RemoteSourceType.SMS) {
            instance.copy(authorizedUsers = NumberMatcher.normalizeWhitelist(instance.authorizedUsers))
        } else {
            instance
        }
        val updatedInstance = if (!securedInstance.hasValidCredentials() && securedInstance.type != RemoteSourceType.SMS) {
            securedInstance.copy(connectionState = RemoteSourceConnectionState.CONFIG_REQUIRED)
        } else {
            securedInstance
        }
        if (index >= 0) {
            current[index] = updatedInstance
        } else {
            current.add(updatedInstance)
        }
        return persist(current)
    }

    fun deleteSource(id: String) {
        val (deleted, persisted) = synchronized(this) {
            val target = _sourcesFlow.value.firstOrNull { it.id == id }
            val updated = _sourcesFlow.value.filterNot { it.id == id }
            val ok = persist(updated)
            target to ok
        }
        if (!persisted) {
            Log.e(TAG, "deleteSource: 落盘失败，已中止下游联动删除，避免产生孤儿通道 (id=$id)")
            return
        }
        if (deleted?.type == RemoteSourceType.WECOM) {
            appContext?.let { ctx ->
                ChannelRepository.getInstance(ctx).syncLinkedWeComStreamChannel(
                    sourceInstanceId = id,
                    botId = "",
                    chatId = ""
                )
            }
        }
        syncRuntime()
    }

    fun toggleEnabled(id: String, enabled: Boolean) {
        synchronized(this) {
            val updated = _sourcesFlow.value.map {
                if (it.id == id) it.copy(enabled = enabled) else it
            }
            persist(updated)
        }
        syncRuntime()
    }

    // Never acquire the runtime manager lock while holding this repository's monitor.
    private fun syncRuntime() {
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
        val hourlyBlocked = instance.hourlyLimit > 0 && hourlyCount !in 0 until instance.hourlyLimit
        val dailyBlocked = instance.dailyLimit > 0 && dailyCount !in 0 until instance.dailyLimit
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
                            authorizedUsers = NumberMatcher.normalizeWhitelist(smsConfig.authorizedList()),
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
                            whitelistEnabled = true,
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
                            whitelistEnabled = true,
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

        // 6. 企业微信长连接
        val wecomBotId = multiConfig.weComRemoteBotId().trim()
        val wecomSecret = multiConfig.weComRemoteSecret().trim()
        if (wecomBotId.isNotBlank() && wecomSecret.isNotBlank()) {
            candidates.add(
                LegacySourceCandidate(
                    type = RemoteSourceType.WECOM,
                    defaultName = "企业微信长连接",
                    credentialsSummary = "Bot ID: ${wecomBotId.take(8)}***",
                    createInstance = {
                        val json = JSONObject().apply {
                            put("botId", wecomBotId)
                            put("secret", wecomSecret)
                            put("chatId", multiConfig.weComRemoteChatId().trim())
                        }
                        RemoteSourceInstance(
                            id = "legacy_remote_wecom",
                            name = "企业微信长连接",
                            type = RemoteSourceType.WECOM,
                            enabled = multiConfig.weComRemoteControlEnabled,
                            connectionState = if (multiConfig.weComRemoteControlEnabled) RemoteSourceConnectionState.CONNECTING else RemoteSourceConnectionState.DISABLED,
                            customCommandPrefix = multiConfig.weComRemoteCustomPrefix(),
                            whitelistEnabled = true,
                            defaultSimMode = multiConfig.weComRemoteSendSimMode,
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
                            whitelistEnabled = true,
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

    fun importLegacySources(
        customMultiConfig: MultiForwardConfig? = null,
        customSmsConfig: RemoteSmsCommandConfig? = null
    ): Int {
        val importedCount = importLegacySourcesLocked(customMultiConfig, customSmsConfig)
        if (importedCount > 0) syncRuntime()
        return importedCount
    }

    @Synchronized
    private fun importLegacySourcesLocked(
        customMultiConfig: MultiForwardConfig?,
        customSmsConfig: RemoteSmsCommandConfig?
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
            // 落盘失败（如加密失败）则整体作废：既不更新内存态，也不谎报"已导入 N 个"。
            if (!persist(current)) return 0
        }
        return importedCount
    }

    /** 将经典版单实例配置同步为开发版 legacy_* 实例，不覆盖开发版创建的其他实例。 */
    fun syncLegacySourcesFromClassic() {
        if (syncLegacySourcesFromClassicLocked()) syncRuntime()
    }

    @Synchronized
    private fun syncLegacySourcesFromClassicLocked(): Boolean {
        val incoming = detectLegacyConfiguredSources()
            .map { it.createInstance() }
            .associateBy { it.id }
        val legacyIds = setOf(
            "legacy_remote_sms",
            "legacy_remote_telegram",
            "legacy_remote_dingtalk",
            "legacy_remote_feishu",
            "legacy_remote_wecom",
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
            // 落盘失败则视为"本次同步未发生"：不更新内存态，也不去联动建通道。
            if (!persist(updated)) return false
            val wecomSource = updated.firstOrNull { it.type == RemoteSourceType.WECOM }
            if (wecomSource != null && appContext != null) {
                val botId = wecomSource.optString("botId")
                val chatId = wecomSource.optString("chatId")
                if (botId.isNotBlank() && chatId.isNotBlank()) {
                    ChannelRepository.getInstance(appContext).syncLinkedWeComStreamChannel(
                        sourceInstanceId = wecomSource.id,
                        botId = botId,
                        chatId = chatId,
                        sourceName = "${wecomSource.name} (长连接)"
                    )
                }
            }
            return true
        }
        return false
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
     * 自动通过 AndroidKeyStore AES-GCM 硬件加密保护，杜绝明文写入 SharedPreferences。
     *
     * 失败语义（P2 修复）：**任一敏感字段加密失败即整体失败**，不再"该字段保持明文、其余照常"。
     *
     * @return 加密后的 configJson；**返回 null 表示加密失败**，
     *         调用方必须整批弃写，绝不能把明文落盘。
     */
    private fun encryptSensitiveConfig(rawConfig: String): String? {
        if (rawConfig.isBlank() || rawConfig == "{}") return rawConfig
        val json = runCatching { JSONObject(rawConfig) }.getOrNull()
        if (json == null) {
            // 解析失败也不能原样写回：无法确认里面是否夹带明文凭据，按失败处理。
            CredentialHealth.markEncryptFailed(KEY_SOURCES)
            SecurityAuditManager.logEvent(
                SecurityAuditEventType.SECRET_ENCRYPT_FAILED,
                KEY_SOURCES,
                "unparsable source config; aborted persistence instead of writing it as-is"
            )
            return null
        }
        for (key in SENSITIVE_CONFIG_KEYS) {
            if (!json.has(key)) continue
            val value = json.optString(key)
            if (value.isBlank() || value.startsWith(ENC_PREFIX)) continue
            val encrypted = cipher.encrypt(value)
            if (encrypted.isBlank()) {
                // 加密失败绝不降级为明文：返回 null 让 persist 弃写整批，磁盘保留旧值。
                CredentialHealth.markEncryptFailed(KEY_SOURCES)
                SecurityAuditManager.logEvent(
                    SecurityAuditEventType.SECRET_ENCRYPT_FAILED,
                    KEY_SOURCES,
                    "keystore unavailable; aborted persistence instead of writing plaintext"
                )
                return null
            }
            json.put(key, ENC_PREFIX + encrypted)
        }
        return json.toString()
    }

    private fun decryptSensitiveConfig(rawConfig: String): String = runCatching {
        if (rawConfig.isBlank() || rawConfig == "{}") return@runCatching rawConfig
        val json = JSONObject(rawConfig)
        for (key in SENSITIVE_CONFIG_KEYS) {
            if (!json.has(key)) continue
            val value = json.optString(key)
            if (!value.startsWith(ENC_PREFIX)) continue
            val decrypted = cipher.decrypt(value.removePrefix(ENC_PREFIX))
            // 解密失败时保留 ENC 标记，防止下次持久化把密文本身再次加密。
            json.put(key, decrypted.ifBlank { value })
        }
        json.toString()
    }.getOrDefault(rawConfig)

    companion object {
        private const val TAG = "RemoteSourceRepository"
        private const val PREFS_NAME = "remote_source_repository_prefs"
        private const val KEY_SOURCES = "remote_sources"
        private const val KEY_AUTO_BACKFILLED_IDS = "auto_backfilled_whitelist_ids"

        /** 已加密字段的前缀标记，加解密两侧共用同一常量，避免漂移。 */
        private const val ENC_PREFIX = "ENC:"

        /** 需要落盘前加密的敏感字段名，加密与解密两侧共用同一份清单。 */
        private val SENSITIVE_CONFIG_KEYS = listOf(
            "secret", "relaySharedSecret", "botToken", "clientSecret",
            "appSecret", "pass", "token", "botId"
        )

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
