package org.fossify.messages.security.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android KeyStore 硬件级 AES-GCM 加密辅助类
 */
object KeyStoreHelper {

    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "SmsForwarder_MasterKey_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_LENGTH = 128

    private val secureRandom by lazy { SecureRandom() }

    init {
        ensureMasterKey()
    }

    private fun ensureMasterKey() {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        if (!keyStore.containsAlias(KEY_ALIAS)) {
            val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(false) // 允许显式传递安全随机 IV
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
    }

    private fun getSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }

    /**
     * 加密明文字符串
     * 返回结构：Base64([12字节 IV] + [密文 + 16字节 AuthTag])
     */
    fun encrypt(plainText: String): String {
        if (plainText.isEmpty()) return ""

        val iv = ByteArray(GCM_IV_LENGTH).apply { secureRandom.nextBytes(this) }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, getSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
        }

        val cipherBytes = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val combined = ByteArray(iv.size + cipherBytes.size)
        System.arraycopy(iv, 0, combined, 0, iv.size)
        System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)

        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /**
     * 解密 Base64 密文字符串
     */
    fun decrypt(cipherTextBase64: String): String? {
        if (cipherTextBase64.isEmpty()) return ""

        return try {
            val combined = Base64.decode(cipherTextBase64, Base64.NO_WRAP)
            if (combined.size <= GCM_IV_LENGTH) return null

            val iv = ByteArray(GCM_IV_LENGTH)
            val cipherBytes = ByteArray(combined.size - GCM_IV_LENGTH)

            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH)
            System.arraycopy(combined, GCM_IV_LENGTH, cipherBytes, 0, cipherBytes.size)

            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, getSecretKey(), GCMParameterSpec(GCM_TAG_LENGTH, iv))
            }

            val plainBytes = cipher.doFinal(cipherBytes)
            String(plainBytes, Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}
