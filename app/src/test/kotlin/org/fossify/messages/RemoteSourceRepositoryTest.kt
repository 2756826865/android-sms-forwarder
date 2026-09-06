package org.fossify.messages

import android.content.SharedPreferences
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.messaging.SubscriptionResolver
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

class RemoteSourceRepositoryTest {

    @Before
    @After
    fun cleanup() {
        RemoteSourceRepository.resetForTesting()
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
        val repo = RemoteSourceRepository(customPrefs = prefs)

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
        val repoRestored = RemoteSourceRepository(customPrefs = prefs)
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

        val repo = RemoteSourceRepository(customPrefs = repoPrefs)
        val multiConfig = MultiForwardConfig(customPrefs = multiPrefs)
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
}