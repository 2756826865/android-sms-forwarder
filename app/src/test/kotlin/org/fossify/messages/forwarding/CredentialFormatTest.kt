package org.fossify.messages.forwarding

import android.content.SharedPreferences
import org.fossify.messages.security.crypto.CredentialHealth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * 凭据存储格式的判定边界：遗留明文必须能读出来，密文绝不能被当明文外泄。
 *
 * 这里刻意使用生产实现（构造时不注入 cipher，走默认的 AndroidKeystoreCipher）：
 * JVM 单元测试没有 AndroidKeyStore，加解密必然失败，
 * 这正好复现"老版本遗留明文 / 密钥丢失"的读取路径——
 * 一个值最终是被当作明文返回、还是被判为密文后丢弃，完全由格式判定决定。
 */
class CredentialFormatTest {

    @Before
    @After
    fun cleanup() {
        // CredentialHealth 是进程级单例，避免污染其他测试类的联动同步守卫。
        CredentialHealth.clearAll()
    }

    @Test
    fun pushPlusHexTokenIsTreatedAsLegacyPlaintext() {
        // 32 位十六进制：字符集 ⊂ Base64 字母表且长度可被 4 整除，可解码为 24 字节。
        // AES-GCM 最短输出 = IV(12) + Tag(16) = 28 字节，24 < 28 ⇒ 判为明文。
        val token = "0123456789abcdef0123456789abcdef"
        assertEquals(token, configWith("pushplus_token" to token).pushPlusToken())
    }

    @Test
    fun shortAlphanumericLegacyTokensAreTreatedAsPlaintext() {
        val barkDeviceKey = "abcdefghijklmnopqrstuv" // 22 位 → 16 字节
        val gotifyToken = "abcdefghijklmnop" // 16 位 → 12 字节
        assertEquals(barkDeviceKey, configWith("bark_device_key" to barkDeviceKey).barkDeviceKey())
        assertEquals(gotifyToken, configWith("gotify_token" to gotifyToken).gotifyToken())
    }

    @Test
    fun dingTalkSecretIsTreatedAsLegacyPlaintext() {
        // SEC + 43 字符（46位）：Base64 可解码且长度 ≥ 28 字节，必须按明文处理，绝不误判为密文清空
        val dingTalkSecret = "SEC" + "a".repeat(43)
        assertEquals(dingTalkSecret, configWith("dingtalk_secret" to dingTalkSecret).dingTalkSecret())
    }

    @Test
    fun weComSecretIsTreatedAsLegacyPlaintext() {
        // 43 位字母数字：企业微信 corpsecret 常见格式，必须保留为明文
        val weComSecret = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"
        assertEquals(weComSecret, configWith("wecom_secret" to weComSecret).weComSecret())
    }

    @Test
    fun sha256HexTokenIsTreatedAsLegacyPlaintext() {
        // 64 位十六进制：常见 SHA-256 密钥，必须保留为明文
        val sha256Token = "0123456789abcdef".repeat(4)
        assertEquals(sha256Token, configWith("pushplus_token" to sha256Token).pushPlusToken())
    }

    @Test
    fun versionPrefixedCiphertextIsNeverExposedAsPlaintext() {
        // 带版本前缀 ⇒ 判定为密文；解不开时返回空串，绝不把密文当明文返回。
        val ciphertext = "v1:" + "A".repeat(40)
        assertEquals("", configWith("pushplus_token" to ciphertext).pushPlusToken())
    }

    private fun configWith(vararg entries: Pair<String, String>): MultiForwardConfig =
        MultiForwardConfig(customPrefs = createFakeSharedPrefs(mapOf(*entries)))

    private fun createFakeSharedPrefs(initial: Map<String, Any> = emptyMap()): SharedPreferences {
        val prefsMap = HashMap<String, Any>(initial)

        val editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { proxy, method, args ->
            when (method.name) {
                "putString" -> {
                    val key = args[0] as String
                    val value = args[1] as? String
                    if (value != null) prefsMap[key] = value else prefsMap.remove(key)
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
