package org.fossify.messages.forwarding

import android.content.SharedPreferences
import org.fossify.messages.security.crypto.CredentialHealth
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.Base64

/**
 * P0-3 第 2 批核心判定之二：`saveChannelInstances` 的「整批中止」语义。
 *
 * 修复前的历史实现有两个不对称、代价极高的缺陷：
 *  1. `encrypt()` 返回空串时把**明文 configJson 原样落盘** —— 用户以为已加密，实际明文躺在 prefs 里；
 *  2. 逐个写盘，中途失败会留下「部分明文 + 部分旧密文」的半截不一致配置。
 *
 * 修复后要求：任一实例加密失败 ⇒ **整批弃写、磁盘一字未动**
 * （见 `MultiForwardConfig.saveChannelInstances`）。本测试用可控假加密器复现「Keystore 故障」，
 * 逐条锁定上述语义。
 *
 * 注意：这里刻意使用**不含明文子串**的密文形态（Base64 后加前缀），
 * 这样「磁盘上不得出现明文凭据」才是真正可被证伪的断言，而不是恒真的空断言。
 */
class ChannelInstanceBatchAbortTest {

    private companion object {
        // MultiForwardConfig.KEY_CHANNEL_INSTANCES 为 private，这里镜像其字面量。
        const val KEY_INSTANCES = "channel_instances"
    }

    /** 可控假加密器：默认输出 "enc:"+Base64(明文)；[failOn] 命中的明文加密失败（返回空串）。 */
    private class ReversibleCipher(private val failOn: (String) -> Boolean = { false }) : CredentialCipher {
        var encryptCalls = 0
            private set

        override fun encrypt(value: String): String {
            encryptCalls++
            if (failOn(value)) return ""
            return PREFIX + Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        }

        override fun decrypt(value: String): String = runCatching {
            if (!value.startsWith(PREFIX)) return@runCatching ""
            String(Base64.getDecoder().decode(value.removePrefix(PREFIX)), Charsets.UTF_8)
        }.getOrDefault("")

        override fun looksLikeCiphertext(value: String): Boolean = value.startsWith(PREFIX)

        companion object {
            const val PREFIX = "enc:"
        }
    }

    @Before
    @After
    fun cleanup() {
        // CredentialHealth 是进程级单例，避免污染其他测试类。
        CredentialHealth.clearAll()
    }

    // ---------- 1. 整批中止：一个失败，全部不写 ----------

    @Test
    fun oneFailureAbortsTheWholeBatchAndWritesNothing() {
        val prefs = createFakeSharedPrefs()
        val cipher = ReversibleCipher(failOn = { it.contains("BAD") })
        val config = MultiForwardConfig(customPrefs = prefs, cipher = cipher)

        val saved = config.saveChannelInstances(
            listOf(
                instance("a", "PUSHPLUS", """{"token":"good-1"}"""),
                instance("b", "PUSHPLUS", """{"token":"good-2"}"""),
                instance("c", "BARK", """{"deviceKey":"BAD-value"}"""),
            )
        )

        assertFalse("任一实例加密失败必须返回 false", saved)
        assertNull("整批中止后磁盘不得出现任何半截数据", prefs.getString(KEY_INSTANCES, null))
        assertTrue(
            "失败必须显式登记到 CredentialHealth，供上层跳过后续写盘",
            KEY_INSTANCES in CredentialHealth.failedKeys()
        )
    }

    // ---------- 2. 成功路径：只落密文，绝不落明文 ----------

    @Test
    fun successPathPersistsCiphertextOnlyAndNeverPlaintext() {
        val prefs = createFakeSharedPrefs()
        val config = MultiForwardConfig(customPrefs = prefs, cipher = ReversibleCipher())

        val plaintext = """{"token":"super-secret-token"}"""
        val saved = config.saveChannelInstances(listOf(instance("a", "PUSHPLUS", plaintext)))

        assertTrue(saved)
        val raw = prefs.getString(KEY_INSTANCES, null)
        assertNotNull("成功路径必须写盘", raw)
        assertFalse("明文凭据绝不能出现在落盘内容里", raw!!.contains("super-secret-token"))

        val obj = JSONArray(raw).getJSONObject(0)
        assertEquals("明文 configJson 必须被清空", "{}", obj.getString("configJson"))
        assertEquals(
            "密文必须写入 configJsonEncrypted",
            ReversibleCipher.PREFIX + Base64.getEncoder().encodeToString(plaintext.toByteArray(Charsets.UTF_8)),
            obj.getString("configJsonEncrypted")
        )
        assertFalse("成功写盘后不得残留失败标记", CredentialHealth.hasFailures())
    }

    // ---------- 3. 往返：写进去的配置必须能原样读回（密文真的可解） ----------

    @Test
    fun savedConfigRoundTripsThroughChannelInstances() {
        val prefs = createFakeSharedPrefs()
        val config = MultiForwardConfig(customPrefs = prefs, cipher = ReversibleCipher())

        config.saveChannelInstances(listOf(instance("a", "PUSHPLUS", """{"token":"t-1"}""")))

        val readBack = config.channelInstances()
        assertEquals(1, readBack.size)
        assertEquals("""{"token":"t-1"}""", readBack[0].configJson)
    }

    // ---------- 4. 保留旧密文：读不出（Keystore 暂不可用）不得被空配置覆盖 ----------

    @Test
    fun blankConfigPreservesPreviouslyStoredCiphertext() {
        val prefs = createFakeSharedPrefs(
            mapOf(
                KEY_INSTANCES to
                    """[{"id":"a","channelType":"PUSHPLUS","name":"a","enabled":true,""" +
                    """"configJson":"{}","configJsonEncrypted":"enc:OLD-CIPHERTEXT"}]"""
            )
        )
        val config = MultiForwardConfig(customPrefs = prefs, cipher = ReversibleCipher())

        // 读取层解不出旧密文时给出 configJson="{}" 的空实例；此时必须保留原密文，不能被覆盖掉。
        val saved = config.saveChannelInstances(listOf(instance("a", "PUSHPLUS", "{}")))

        assertTrue(saved)
        val obj = JSONArray(prefs.getString(KEY_INSTANCES, null)).getJSONObject(0)
        assertEquals("旧密文必须原样保留", "enc:OLD-CIPHERTEXT", obj.getString("configJsonEncrypted"))
    }

    // ---------- 5. 反「空断言」探针：加密器确实被逐个调用 ----------

    @Test
    fun encryptIsInvokedOncePerInstanceWithNonBlankConfig() {
        val cipher = ReversibleCipher()
        val config = MultiForwardConfig(customPrefs = createFakeSharedPrefs(), cipher = cipher)

        config.saveChannelInstances(
            listOf(
                instance("a", "PUSHPLUS", """{"token":"x"}"""),
                instance("b", "BARK", """{"deviceKey":"y"}"""),
                instance("c", "PUSHPLUS", "{}"), // 空配置：不应触发加密
            )
        )

        assertEquals("两个非空配置实例各触发一次加密，空配置跳过", 2, cipher.encryptCalls)
    }

    // ---------- 辅助 ----------

    private fun instance(id: String, channelType: String, configJson: String) =
        ForwardingChannelInstance(id = id, channelType = channelType, name = id, configJson = configJson)

    private fun createFakeSharedPrefs(initial: Map<String, Any> = emptyMap()): SharedPreferences {
        val prefsMap = HashMap<String, Any>(initial)

        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    val value = args[1] as? String
                    if (value != null) prefsMap[args[0] as String] = value else prefsMap.remove(args[0] as String)
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
                "edit" -> editor
                "getAll" -> prefsMap
                else -> null
            }
        } as SharedPreferences
    }
}
