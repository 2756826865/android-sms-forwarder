package org.fossify.messages.helpers

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

object ShadowHmacHelper {
    private const val KEY_ALIAS = "shadow_observability_hmac_key"
    private const val ALGORITHM = "HmacSHA256"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"

    fun calculateHmac(value: String?, normalize: Boolean = false): String? {
        if (value == null) return null
        val target = if (normalize) normalizeForHmac(value) else value
        
        return try {
            val key = getOrCreateKey()
            val mac = Mac.getInstance(ALGORITHM)
            mac.init(key)
            val hmacBytes = mac.doFinal(target.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(hmacBytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            // Fail-open.
            null
        }
    }

    private fun normalizeForHmac(value: String): String {
        // Basic normalization: trim and lowercase
        // For phone numbers, we should ideally strip separators, but we'll do general trim for now.
        return value.trim().lowercase()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existingKey = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existingKey != null) return existingKey

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setUserAuthenticationRequired(false)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }
}
