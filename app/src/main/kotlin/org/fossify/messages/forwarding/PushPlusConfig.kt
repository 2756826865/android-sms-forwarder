package org.fossify.messages.forwarding

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class PushPlusConfig(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    var titlePrefix: String
        get() = prefs.getString(KEY_TITLE_PREFIX, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TITLE_PREFIX, value.trim()).apply()

    var includeSender: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_SENDER, true)
        set(value) = prefs.edit().putBoolean(KEY_INCLUDE_SENDER, value).apply()

    var includeSim: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_SIM, true)
        set(value) = prefs.edit().putBoolean(KEY_INCLUDE_SIM, value).apply()

    var includeTime: Boolean
        get() = prefs.getBoolean(KEY_INCLUDE_TIME, true)
        set(value) = prefs.edit().putBoolean(KEY_INCLUDE_TIME, value).apply()

    var lastStatus: String
        get() = prefs.getString(KEY_LAST_STATUS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_STATUS, value).apply()

    fun saveToken(token: String) {
        if (token.isBlank()) {
            prefs.edit().remove(KEY_TOKEN).apply()
            return
        }
        prefs.edit().putString(KEY_TOKEN, TokenCipher.encrypt(token.trim())).apply()
    }

    fun getToken(): String = prefs.getString(KEY_TOKEN, null)?.let(TokenCipher::decrypt).orEmpty()

    companion object {
        private const val PREFS_NAME = "pushplus_forwarding"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_TOKEN = "token"
        private const val KEY_TITLE_PREFIX = "title_prefix"
        private const val KEY_INCLUDE_SENDER = "include_sender"
        private const val KEY_INCLUDE_SIM = "include_sim"
        private const val KEY_INCLUDE_TIME = "include_time"
        private const val KEY_LAST_STATUS = "last_status"
    }
}

private object TokenCipher {
    private const val KEY_ALIAS = "pushplus_token_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun encrypt(value: String): String = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }.getOrDefault("")

    fun decrypt(value: String): String = runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        if (bytes.size <= 12) return ""
        val iv = bytes.copyOfRange(0, 12)
        val encrypted = bytes.copyOfRange(12, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }.getOrDefault("")

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }
}
