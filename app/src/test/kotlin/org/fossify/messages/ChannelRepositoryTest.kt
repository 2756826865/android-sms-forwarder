package org.fossify.messages

import android.content.Context
import android.content.SharedPreferences
import org.fossify.messages.forwarding.ForwardingChannelInstance
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.repository.ChannelRepository
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

class ChannelRepositoryTest {

    @Before
    @After
    fun cleanup() {
        ChannelRepository.resetForTesting()
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

    private fun createFakeRepo(initialPrefs: Map<String, Any> = emptyMap()): Pair<ChannelRepository, MultiForwardConfig> {
        val prefs = createFakeSharedPrefs(initialPrefs)
        val config = MultiForwardConfig(customPrefs = prefs)
        val repo = ChannelRepository(multiConfig = config)
        return Pair(repo, config)
    }

    @Test
    fun testForwardingChannelInstanceSerialization() {
        val jsonConfig = JSONObject()
            .put("webhook", "https://oapi.dingtalk.com/robot/send?access_token=xyz")
            .put("secret", "SEC123")
            .toString()

        val instance = ForwardingChannelInstance(
            id = "inst-1",
            channelType = ForwardingChannels.DINGTALK,
            name = "钉钉群机器人 1",
            enabled = true,
            configJson = jsonConfig
        )

        assertEquals("inst-1", instance.id)
        assertEquals(ForwardingChannels.DINGTALK, instance.channelType)
        assertEquals("钉钉群机器人 1", instance.name)
        assertTrue(instance.enabled)
        assertEquals("https://oapi.dingtalk.com/robot/send?access_token=xyz", instance.optString("webhook"))
        assertEquals("SEC123", instance.optString("secret"))

        val serialized = instance.toJson()
        val restored = ForwardingChannelInstance.fromJson(serialized)
        assertEquals(instance.id, restored.id)
        assertEquals(instance.channelType, restored.channelType)
        assertEquals(instance.name, restored.name)
        assertEquals(instance.enabled, restored.enabled)
        assertEquals(instance.optString("webhook"), restored.optString("webhook"))
    }

    @Test
    fun testChannelRepositoryCrudAndFlow() {
        val (repo, _) = createFakeRepo()

        // 初始为空
        assertTrue(repo.getInstances().isEmpty())
        assertTrue(repo.instancesFlow.value.isEmpty())

        // 1. 添加实例 1
        val inst1 = ForwardingChannelInstance(
            id = "inst-1",
            channelType = ForwardingChannels.WECOM_BOT,
            name = "运维企微群",
            enabled = true,
            configJson = JSONObject().put("webhook", "https://qyapi.weixin.qq.com/send/1").toString()
        )
        repo.saveInstance(inst1)

        assertEquals(1, repo.getInstances().size)
        assertEquals(1, repo.instancesFlow.value.size)
        assertEquals("运维企微群", repo.getInstanceById("inst-1")?.name)

        // 2. 添加同渠道类型的第二个独立实例 (多实例支持)
        val inst2 = ForwardingChannelInstance(
            id = "inst-2",
            channelType = ForwardingChannels.WECOM_BOT,
            name = "测试企微群",
            enabled = true,
            configJson = JSONObject().put("webhook", "https://qyapi.weixin.qq.com/send/2").toString()
        )
        repo.saveInstance(inst2)

        assertEquals(2, repo.getInstances().size)
        assertEquals(2, repo.instancesFlow.value.size)

        // 3. 停用实例 1
        repo.toggleInstanceEnabled("inst-1", false)
        assertFalse(repo.getInstanceById("inst-1")!!.enabled)
        assertEquals(1, repo.getEnabledInstances().size)
        assertEquals("inst-2", repo.getEnabledInstances().first().id)

        // 4. 删除实例 2
        val deleted = repo.deleteInstance("inst-2")
        assertTrue(deleted)
        assertEquals(1, repo.getInstances().size)
        assertNull(repo.getInstanceById("inst-2"))
        assertEquals(1, repo.instancesFlow.value.size)
    }

    @Test
    fun testDetectLegacyConfiguredChannelsOnlyReturnsRealConfigs() {
        // 只配置钉钉群机器人与短信直发，不配置其他任何渠道
        val prefs = mapOf(
            "dingtalk_enabled" to true,
            "dingtalk_webhook" to "https://oapi.dingtalk.com/send",
            "dingtalk_secret" to "SEC_SECRET",
            "sms_direct_enabled" to true,
            "sms_direct_phone" to "13800138000"
        )
        val (repo, _) = createFakeRepo(prefs)

        val detected = repo.detectLegacyConfiguredChannels()
        // 必须只检测出真正配置过的 2 个渠道，严禁生成未配置的内置通道
        assertEquals(2, detected.size)
        val types = detected.map { it.channelType }.toSet()
        assertTrue(types.contains(ForwardingChannels.DINGTALK))
        assertTrue(types.contains(ForwardingChannels.SMS_DIRECT))
        assertFalse(types.contains(ForwardingChannels.TELEGRAM))
        assertFalse(types.contains(ForwardingChannels.BARK))
        assertFalse(types.contains(ForwardingChannels.PUSHPLUS))
    }

    @Test
    fun testIdempotentLegacyMigration() {
        val prefs = mapOf(
            "dingtalk_enabled" to true,
            "dingtalk_webhook" to "https://oapi.dingtalk.com/send",
            "dingtalk_secret" to "SEC_SECRET",
            "sms_direct_enabled" to true,
            "sms_direct_phone" to "13800138000"
        )
        val (repo, _) = createFakeRepo(prefs)

        assertTrue(repo.hasLegacyConfigToMigrate())

        // 第一次导入
        val firstCount = repo.importLegacyChannels()
        assertEquals(2, firstCount)
        assertEquals(2, repo.getInstances().size)
        assertFalse("已全部导入后不应再提示迁移引导", repo.hasLegacyConfigToMigrate())

        // 第二次重复导入必须幂等，不得增加任何重复实例
        val secondCount = repo.importLegacyChannels()
        assertEquals(0, secondCount)
        assertEquals(2, repo.getInstances().size)
    }

    @Test
    fun testDualEntryConsistency() {
        val sharedPrefs = createFakeSharedPrefs()
        val multiConfig = MultiForwardConfig(customPrefs = sharedPrefs)

        // 模拟经典版与开发版同时获取同一个共享 Repository 实例
        val sharedRepo = ChannelRepository(multiConfig = multiConfig)
        val classicRepo = sharedRepo
        val devRepo = sharedRepo

        // 验证两边引用同一个 Repository 实例
        assertEquals(classicRepo, devRepo)

        // 在开发版新增通道实例
        val newInstance = ForwardingChannelInstance(
            id = "feishu-bot-1",
            channelType = ForwardingChannels.FEISHU_BOT,
            name = "飞书通知群",
            enabled = true,
            configJson = JSONObject().put("webhook", "https://open.feishu.cn/webhook/1").toString()
        )
        devRepo.saveInstance(newInstance)

        // 经典版必须立即读取到新增的实例
        val fromClassic = classicRepo.getInstanceById("feishu-bot-1")
        assertNotNull(fromClassic)
        assertEquals("飞书通知群", fromClassic?.name)

        // 在经典版停用该实例
        classicRepo.toggleInstanceEnabled("feishu-bot-1", false)

        // 开发版必须立即感知其 enabled 状态更新
        assertFalse(devRepo.getInstanceById("feishu-bot-1")!!.enabled)

        // MultiForwardConfig 的 enabledChannelIds 必须同步响应
        assertFalse(multiConfig.isChannelEnabled(ForwardingChannels.FEISHU_BOT))

        devRepo.toggleInstanceEnabled("feishu-bot-1", true)
        assertTrue(multiConfig.isChannelEnabled(ForwardingChannels.FEISHU_BOT))
        assertTrue(multiConfig.enabledChannelIds().contains(ForwardingChannels.FEISHU_BOT))
    }
}

