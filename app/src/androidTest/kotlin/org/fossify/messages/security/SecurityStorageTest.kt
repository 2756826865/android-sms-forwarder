package org.fossify.messages.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.security.audit.SecurityAuditManager
import org.fossify.messages.security.crypto.KeyStoreHelper
import org.fossify.messages.security.guard.RemoteAccessGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SecurityStorageTest {

    private lateinit var context: Context
    private lateinit var secureStorage: SecureStorageManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        secureStorage = DefaultSecureStorageManager.getInstance(context)
    }

    @Test
    fun testKeyStoreAesGcmEncryptDecrypt() {
        val originalSecret = "sk-dingtalk-super-secret-token-" + UUID.randomUUID()
        val encrypted = KeyStoreHelper.encrypt(originalSecret)

        assertNotNull(encrypted)
        assertTrue(encrypted.isNotEmpty())
        // Encrypted string must not contain raw secret
        assertFalse(encrypted.contains(originalSecret))

        val decrypted = KeyStoreHelper.decrypt(encrypted)
        assertEquals(originalSecret, decrypted)
    }

    @Test
    fun testSecureStoragePutGetRemove() {
        val key = "pushplus_token_test_" + UUID.randomUUID()
        val secretValue = "pushplus_secret_val_123"

        secureStorage.put(key, secretValue)
        assertTrue(secureStorage.contains(key))

        val readValue = secureStorage.get(key)
        assertEquals(secretValue, readValue)

        secureStorage.remove(key)
        assertFalse(secureStorage.contains(key))
        assertNull(secureStorage.get(key))
    }

    @Test
    fun testMigrationFromPlainSharedPreferences() {
        val plainPrefsName = "test_plain_old_prefs_" + UUID.randomUUID().toString().take(6)
        val oldPrefs = context.getSharedPreferences(plainPrefsName, Context.MODE_PRIVATE)

        val testKey = "webhook_token_legacy"
        val testVal = "legacy_token_abc_999"
        oldPrefs.edit().putString(testKey, testVal).commit()

        assertTrue(oldPrefs.contains(testKey))

        // Run Migration
        secureStorage.migrateFromPlainPrefs(context, plainPrefsName, listOf(testKey))

        // Verify migrated into secure storage
        assertEquals(testVal, secureStorage.get(testKey))

        // Verify removed from old plain prefs
        assertFalse(oldPrefs.contains(testKey))
    }

    @Test
    fun testTamperedCiphertextReturnsNull() {
        val validEncrypted = KeyStoreHelper.encrypt("sensitive_payload")
        // Tamper by modifying the base64 string
        val tampered = "A" + validEncrypted.substring(1)

        val result = KeyStoreHelper.decrypt(tampered)
        assertNull(result)
    }

    @Test
    fun testRemoteAccessGuardLifecycle() {
        RemoteAccessGuard.invalidateSession()
        assertFalse(RemoteAccessGuard.isSessionValid())

        RemoteAccessGuard.markAuthSuccess("FINGERPRINT")
        assertTrue(RemoteAccessGuard.isSessionValid())

        val auditLogs = SecurityAuditManager.getRecentAuditEvents(10)
        assertTrue(auditLogs.any { it.details?.contains("FINGERPRINT") == true })
    }
}
