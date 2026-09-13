package org.fossify.messages

import android.content.SharedPreferences
import org.fossify.messages.forwarding.CredentialCipher
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.PlaintextCipher
import org.fossify.messages.messaging.SubscriptionResolver
import org.fossify.messages.security.crypto.CredentialHealth
import org.fossify.messages.remote.RemoteSmsCommandConfig
import org.fossify.messages.remote.repository.RemoteSourceConnectionState
import org.fossify.messages.remote.repository.RemoteSourceInstance
import org.fossify.messages.remote.repository.RemoteSourceRepository
import org.fossify.messages.remote.repository.RemoteSourceType
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/** 模拟 Keystore 不可用：加密/解密恒失败（生产侧对应 AndroidKeyStore 抛异常）。 */
private object FailingCipher : CredentialCipher {
    override fun encrypt(value: String): String = ""
    override fun decrypt(value: String): String = ""
    override fun looksLikeCiphertext(value: String): Boolean = false
}

class RemoteSourceRepositoryTest {

    @Before
    @After
    fun cleanup() {
        RemoteSourceRepository.resetForTesting()
        CredentialHealth.clearAll()
    }

    private fun createFakeSharedPrefs(initialPrefs: Map<String, Any> = emptyMap()): SharedPreferences {
        val prefsMap = HashMap<String, Any>(initialPrefs)

        val editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    val k = args[0] as String
                    val v = args[1] as? String
                    if (v != null) prefsMap[k] = v else prefsMap.remove(k)
                    proxy
                }
                "putBoolean" -> {
                    prefsMap[args[0] as String] = args[1] as Boolean
                    proxy
                }
                "putInt" -> {
                    prefsMap[args[0] as String] = args[1] as Int
                    proxy
                }
                "remove" -> {
                    prefsMap.remove(args[0] as String)
                    proxy
                }
                "apply", "commit" -> true
                else -> proxy
            }
        } as SharedPreferences.Editor

        return Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> prefsMap[args[0] as String] ?: (args[1] as? String)
                "getBoolean" -> prefsMap[args[0] as String] ?: (args[1] as? Boolean) ?: false
                "getInt" -> prefsMap[args[0] as String] ?: (args[1] as? Int) ?: 0
                "getLong" -> prefsMap[args[0] as String] ?: (args[1] as? Long) ?: 0L
                "contains" -> prefsMap.containsKey(args[0] as String)
                "edit" -> editorProxy
                "getAll" -> prefsMap
                else -> null
            }
        } as SharedPreferences
    }

    @Test
    fun testRemoteSourceCrudAndPersistence() {
        val prefs = createFakeSharedPrefs()
        val repo = RemoteSourceRepository(customPrefs = prefs, cipher = PlaintextCipher)

        assertTrue(repo.getAllSources().isEmpty())

        // 1. 创建并添加 Telegram 实例
        val tgConfig = JSONObject().put("botToken", "123456:ABC-DEF").toString()
        val tgInstance = RemoteSourceInstance(
            id = "src-tg-1",
            name = "运维应急 Bot",
            type = RemoteSourceType.TELEGRAM,
            enabled = true,
            connectionState = RemoteSourceConnectionState.READY,
            configJson = tgConfig
        )
        repo.saveSource(tgInstance)

        assertEquals(1, repo.getAllSources().size)
        val loaded = repo.getSourceById("src-tg-1")
        assertNotNull(loaded)
        assertEquals("运维应急 Bot", loaded?.name)
        assertEquals(RemoteSourceType.TELEGRAM, loaded?.type)
        assertTrue(loaded?.hasValidCredentials() == true)

        // 2. 状态更新
        repo.updateConnectionState("src-tg-1", RemoteSourceConnectionState.ERROR, errorCode = 401, errorMessage = "Unauthorized token")
        val updatedState = repo.getSourceById("src-tg-1")
        assertEquals(RemoteSourceConnectionState.ERROR, updatedState?.connectionState)
        assertEquals(401, updatedState?.lastErrorCode)
        assertEquals("Unauthorized token", updatedState?.lastErrorMessage)

        // 3. 启用/禁用切换
        repo.toggleEnabled("src-tg-1", false)
        assertFalse(repo.getSourceById("src-tg-1")?.enabled ?: true)
        assertTrue(repo.getEnabledSources().isEmpty())

        // 4. 新实例还原持久化测试（模拟重启）
        val repoRestored = RemoteSourceRepository(customPrefs = prefs, cipher = PlaintextCipher)
        val restoredItem = repoRestored.getSourceById("src-tg-1")
        assertNotNull(restoredItem)
        assertEquals(401, restoredItem?.lastErrorCode)
        assertFalse(restoredItem?.enabled ?: true)

        // 5. 删除实例
        repoRestored.deleteSource("src-tg-1")
        assertTrue(repoRestored.getAllSources().isEmpty())
    }

    @Test
    fun testLegacyConfigDetectionAndIdempotentImport() {
        val repoPrefs = createFakeSharedPrefs()
        val multiPrefs = createFakeSharedPrefs()
        val smsPrefs = createFakeSharedPrefs()

        val repo = RemoteSourceRepository(customPrefs = repoPrefs, cipher = PlaintextCipher)
        // 旧版凭据以明文写入 prefs，JVM 无 AndroidKeyStore，必须注入 PlaintextCipher 才能读回。
        val multiConfig = MultiForwardConfig(customPrefs = multiPrefs, cipher = PlaintextCipher)
        val smsConfig = RemoteSmsCommandConfig(customPrefs = smsPrefs)

        // 配置旧版 SMS 指令
        smsConfig.enabled = true
        smsConfig.authorizedNumbers = "13800138000\n13900139000"
        smsConfig.customPrefix = "/远程短信"

        // 配置旧版 邮箱 IMAP
        multiPrefs.edit()
            .putBoolean("email_remote_control_enabled", true)
            .putString("email_remote_host", "imap.example.com")
            .putInt("email_remote_port", 993)
            .putString("email_remote_user", "admin@example.com")
            .putString("email_remote_password", "password123")
            .putString("email_remote_auth_senders", "boss@example.com")
            // 配置旧版 Telegram
            .putBoolean("telegram_remote_control_enabled", true)
            .putString("telegram_remote_bot_token", "987654:XYZ")
            .putString("telegram_remote_chat_id", "12345678")
            .apply()

        // 1. 检测旧配置
        val candidates = repo.detectLegacyConfiguredSources(multiConfig, smsConfig)
        assertEquals(3, candidates.size)
        assertTrue(repo.hasLegacyConfigToMigrate(multiConfig, smsConfig))

        // 2. 首次一键迁移
        val imported = repo.importLegacySources(multiConfig, smsConfig)
        assertEquals(3, imported)
        assertEquals(3, repo.getAllSources().size)

        // 验证 SMS 实例属性
        val importedSms = repo.getSourceById("legacy_remote_sms")
        assertNotNull(importedSms)
        assertEquals(RemoteSourceType.SMS, importedSms?.type)
        assertEquals("/远程短信", importedSms?.customCommandPrefix)
        assertEquals(setOf("13800138000", "13900139000"), importedSms?.authorizedUsers)

        // 验证 邮箱 实例属性
        val importedEmail = repo.getSourceById("legacy_remote_email")
        assertNotNull(importedEmail)
        assertEquals(RemoteSourceType.EMAIL, importedEmail?.type)
        assertEquals(setOf("boss@example.com"), importedEmail?.authorizedUsers)

        // 3. 幂等性测试：再次调用迁移不会重复生成
        assertFalse(repo.hasLegacyConfigToMigrate(multiConfig, smsConfig))
        val reimported = repo.importLegacySources(multiConfig, smsConfig)
        assertEquals(0, reimported)
        assertEquals(3, repo.getAllSources().size)
    }

    @Test
    fun testWhitelistBackfilledFromKnownSmsRequesters() {
        val repoPrefs = createFakeSharedPrefs()
        // 限流记录的 key 形如 rate_<NumberMatcher.normalize(sender)>
        val smsPrefs = createFakeSharedPrefs(
            mapOf(
                "rate_13800138000" to "[1690000000000]",
                "rate_13900139000" to "[1690000001000]",
                "last_status" to "历史状态(不应被当成发件人)",
            )
        )
        val smsConfig = RemoteSmsCommandConfig(customPrefs = smsPrefs)

        val repo = RemoteSourceRepository(customPrefs = repoPrefs, customSmsCommandConfig = smsConfig, cipher = PlaintextCipher)
        repo.saveSource(
            RemoteSourceInstance(
                id = "src-sms-legacy",
                name = "短信远程指令",
                type = RemoteSourceType.SMS,
                enabled = true,
                whitelistEnabled = false,
                authorizedUsers = emptySet()
            )
        )

        val migrated = repo.getSourceById("src-sms-legacy")
        assertNotNull(migrated)
        // 取到历史发件人 → 自动开启白名单并回填，绝不留下"名单空+白名单开"的失效态
        assertEquals(true, migrated?.whitelistEnabled)
        assertEquals(setOf("13800138000", "13900139000"), migrated?.authorizedUsers)

        // 回填结果必须真的落盘（模拟重启后仍然生效）
        val restored = RemoteSourceRepository(customPrefs = repoPrefs, customSmsCommandConfig = smsConfig, cipher = PlaintextCipher)
            .getSourceById("src-sms-legacy")
        assertEquals(true, restored?.whitelistEnabled)
        assertEquals(setOf("13800138000", "13900139000"), restored?.authorizedUsers)
    }

    @Test
    fun testWhitelistStaysClosedWhenNoKnownRequesters() {
        val repoPrefs = createFakeSharedPrefs()
        val smsPrefs = createFakeSharedPrefs() // 无任何 rate_ key
        val smsConfig = RemoteSmsCommandConfig(customPrefs = smsPrefs)

        val repo = RemoteSourceRepository(customPrefs = repoPrefs, customSmsCommandConfig = smsConfig, cipher = PlaintextCipher)
        repo.saveSource(
            RemoteSourceInstance(
                id = "src-sms-empty",
                name = "短信远程指令(无历史记录)",
                type = RemoteSourceType.SMS,
                whitelistEnabled = false,
                authorizedUsers = emptySet()
            )
        )

        val source = repo.getSourceById("src-sms-empty")
        assertNotNull(source)
        // 取不到历史发件人 → 保持关闭、接受全部（与旧版一致），不被强行开启成失效态
        assertEquals(false, source?.whitelistEnabled)
        assertTrue(source?.authorizedUsers?.isEmpty() == true)
    }

    @Test
    fun testAutoBackfillMarksSourceAsAutoGenerated() {
        val repoPrefs = createFakeSharedPrefs()
        val smsPrefs = createFakeSharedPrefs(mapOf("rate_13800138000" to "[1690000000000]"))
        val smsConfig = RemoteSmsCommandConfig(customPrefs = smsPrefs)

        val repo = RemoteSourceRepository(customPrefs = repoPrefs, customSmsCommandConfig = smsConfig, cipher = PlaintextCipher)

        // 1. 未被回填的来源（白名单开启且名单非空）不应被标记
        repo.saveSource(
            RemoteSourceInstance(
                id = "src-sms-manual",
                name = "手工配置的短信来源",
                type = RemoteSourceType.SMS,
                whitelistEnabled = true,
                authorizedUsers = setOf("13800138000")
            )
        )
        assertFalse(repo.autoBackfilledIds.value.contains("src-sms-manual"))

        // 2. 被回填的来源必须被标记
        repo.saveSource(
            RemoteSourceInstance(
                id = "src-sms-backfilled",
                name = "待回填的短信来源",
                type = RemoteSourceType.SMS,
                whitelistEnabled = false,
                authorizedUsers = emptySet()
            )
        )
        assertTrue(repo.autoBackfilledIds.value.contains("src-sms-backfilled"))
        assertEquals(setOf("13800138000"), repo.getSourceById("src-sms-backfilled")?.authorizedUsers)

        // 3. 标记必须落盘：换一个 repo 实例（模拟重启）后仍在
        val restored = RemoteSourceRepository(customPrefs = repoPrefs, customSmsCommandConfig = smsConfig, cipher = PlaintextCipher)
        assertTrue(restored.autoBackfilledIds.value.contains("src-sms-backfilled"))
        assertFalse(restored.autoBackfilledIds.value.contains("src-sms-manual"))
    }

    @Test
    fun testNonSmsSourceNotBackfilledWithPhoneNumbers() {
        val repoPrefs = createFakeSharedPrefs()
        // 即使存在短信来源的历史发件人，也不能回填到非短信来源（其名单是用户 ID/邮箱）
        val smsPrefs = createFakeSharedPrefs(mapOf("rate_13800138000" to "[1690000000000]"))
        val smsConfig = RemoteSmsCommandConfig(customPrefs = smsPrefs)

        val repo = RemoteSourceRepository(customPrefs = repoPrefs, customSmsCommandConfig = smsConfig, cipher = PlaintextCipher)
        repo.saveSource(
            RemoteSourceInstance(
                id = "src-ding-1",
                name = "钉钉 Stream 指令",
                type = RemoteSourceType.DINGTALK,
                whitelistEnabled = false,
                authorizedUsers = emptySet()
            )
        )

        val source = repo.getSourceById("src-ding-1")
        assertNotNull(source)
        // 钉钉取不到自己的历史发件人 → 保持关闭（旧版行为），不塞入号码名单
        assertEquals(false, source?.whitelistEnabled)
        assertTrue(source?.authorizedUsers?.isEmpty() == true)
    }

    /**
     * P2 回归：Keystore 故障期保存远程来源，绝不能把明文凭据写进 prefs。
     *
     * 修复前 encryptSensitiveConfig 加密失败时会原样保留明文字段并照常落盘，
     * 表现为"用户看到保存成功，prefs 里躺着明文 token"。
     */
    @Test
    fun testEncryptFailureAbortsPersistAndNeverWritesPlaintext() {
        val prefs = createFakeSharedPrefs()
        val repo = RemoteSourceRepository(customPrefs = prefs, cipher = PlaintextCipher)
        repo.saveSource(
            RemoteSourceInstance(
                id = "src-tg-p2",
                name = "原有 Bot",
                type = RemoteSourceType.TELEGRAM,
                enabled = true,
                configJson = JSONObject().put("botToken", "111:AAA").toString()
            )
        )
        val storedBefore = prefs.getString("remote_sources", "").orEmpty()
        assertTrue(storedBefore.contains("ENC:"))

        // 换用"加密恒失败"的 cipher 打开同一份 prefs（模拟 Keystore 故障期），
        // 尝试把 token 改成新的明文值。
        val brokenRepo = RemoteSourceRepository(customPrefs = prefs, cipher = FailingCipher)
        assertEquals(1, brokenRepo.getAllSources().size)
        brokenRepo.saveSource(
            brokenRepo.getSourceById("src-tg-p2")!!
                .copy(configJson = JSONObject().put("botToken", "222:BBB").toString())
        )

        // 1) 磁盘一字未动：整批弃写，保留旧值
        assertEquals(storedBefore, prefs.getString("remote_sources", "").orEmpty())
        // 2) 磁盘上绝不能出现新的明文凭据
        assertFalse(prefs.getString("remote_sources", "").orEmpty().contains("222:BBB"))
        // 3) 内存态也不能更新：UI 回退显示磁盘上的真实状态
        assertFalse(brokenRepo.getSourceById("src-tg-p2")!!.configJson.contains("222:BBB"))
    }
}