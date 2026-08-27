package org.fossify.messages.security

import android.content.Context
import android.content.SharedPreferences
import org.fossify.messages.security.audit.SecurityAuditEventType
import org.fossify.messages.security.audit.SecurityAuditManager
import org.fossify.messages.security.crypto.KeyStoreHelper

/**
 * 统一安全加密存储管理器接口
 */
interface SecureStorageManager {
    fun put(key: String, value: String)
    fun get(key: String): String?
    fun remove(key: String)
    fun contains(key: String): Boolean
    fun migrateFromPlainPrefs(context: Context, plainPrefsName: String, keys: List<String>)
}

/**
 * 基于 Android KeyStore AES-GCM 的默认加密存储实现
 */
class DefaultSecureStorageManager(private val context: Context) : SecureStorageManager {

    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(VAULT_PREFS_NAME, Context.MODE_PRIVATE)
    }

    override fun put(key: String, value: String) {
        val cipherText = KeyStoreHelper.encrypt(value)
        prefs.edit().putString(key, cipherText).apply()
        SecurityAuditManager.logEvent(SecurityAuditEventType.SECRET_UPDATED, key)
    }

    override fun get(key: String): String? {
        val cipherText = prefs.getString(key, null) ?: return null
        val plainText = KeyStoreHelper.decrypt(cipherText)
        if (plainText != null) {
            SecurityAuditManager.logEvent(SecurityAuditEventType.SECRET_READ, key)
        }
        return plainText
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
        SecurityAuditManager.logEvent(SecurityAuditEventType.SECRET_DELETED, key)
    }

    override fun contains(key: String): Boolean {
        return prefs.contains(key)
    }

    override fun migrateFromPlainPrefs(
        context: Context,
        plainPrefsName: String,
        keys: List<String>
    ) {
        val oldPrefs = context.getSharedPreferences(plainPrefsName, Context.MODE_PRIVATE)
        val editor = oldPrefs.edit()

        for (key in keys) {
            if (oldPrefs.contains(key)) {
                val plainVal = oldPrefs.getString(key, null)
                if (!plainVal.isNullOrBlank()) {
                    put(key, plainVal)
                    editor.remove(key)
                }
            }
        }
        editor.apply()
    }

    companion object {
        private const val VAULT_PREFS_NAME = "sms_forwarder_encrypted_vault"

        @Volatile
        private var instance: SecureStorageManager? = null

        fun getInstance(context: Context): SecureStorageManager {
            return instance ?: synchronized(this) {
                instance ?: DefaultSecureStorageManager(context.applicationContext).also { instance = it }
            }
        }
    }
}
