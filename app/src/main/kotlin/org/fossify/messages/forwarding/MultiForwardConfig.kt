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

class MultiForwardConfig(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var dingTalkEnabled by booleanPreference(KEY_DINGTALK_ENABLED)
    var feishuEnabled by booleanPreference(KEY_FEISHU_ENABLED)
    var weComEnabled by booleanPreference(KEY_WECOM_ENABLED)
    var emailEnabled by booleanPreference(KEY_EMAIL_ENABLED)

    var simOneLabel: String
        get() = prefs.getString(KEY_SIM_ONE_LABEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SIM_ONE_LABEL, value.trim()).apply()

    var simTwoLabel: String
        get() = prefs.getString(KEY_SIM_TWO_LABEL, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SIM_TWO_LABEL, value.trim()).apply()

    var simOneNumber: String
        get() = prefs.getString(KEY_SIM_ONE_NUMBER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SIM_ONE_NUMBER, value.trim()).apply()

    var simTwoNumber: String
        get() = prefs.getString(KEY_SIM_TWO_NUMBER, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_SIM_TWO_NUMBER, value.trim()).apply()

    var templateMode: Int
        get() = prefs.getInt(KEY_TEMPLATE_MODE, TEMPLATE_COMPACT).coerceIn(TEMPLATE_COMPACT, TEMPLATE_DETAILED)
        set(value) = prefs.edit().putInt(KEY_TEMPLATE_MODE, value.coerceIn(TEMPLATE_COMPACT, TEMPLATE_DETAILED)).apply()

    var acceptedDisclaimerVersion: Int
        get() = prefs.getInt(KEY_ACCEPTED_DISCLAIMER_VERSION, 0)
        set(value) = prefs.edit().putInt(KEY_ACCEPTED_DISCLAIMER_VERSION, value).apply()

    fun hasAcceptedDisclaimer() = acceptedDisclaimerVersion >= CURRENT_DISCLAIMER_VERSION

    fun acceptCurrentDisclaimer() {
        acceptedDisclaimerVersion = CURRENT_DISCLAIMER_VERSION
    }

    fun customSimLabel(slotIndex: Int): String = when (slotIndex) {
        0 -> simOneLabel
        1 -> simTwoLabel
        else -> ""
    }

    fun customSimNumber(slotIndex: Int): String = when (slotIndex) {
        0 -> simOneNumber
        1 -> simTwoNumber
        else -> ""
    }

    var emailPort: Int
        get() = prefs.getInt(KEY_EMAIL_PORT, 465).coerceIn(1, 65535)
        set(value) = prefs.edit().putInt(KEY_EMAIL_PORT, value.coerceIn(1, 65535)).apply()

    var lastStatus: String
        get() = prefs.getString(KEY_LAST_STATUS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_STATUS, value).apply()

    fun saveDingTalk(webhook: String, secret: String) {
        saveSecret(KEY_DINGTALK_WEBHOOK, webhook)
        saveSecret(KEY_DINGTALK_SECRET, secret)
    }

    fun dingTalkWebhook() = getSecret(KEY_DINGTALK_WEBHOOK)
    fun dingTalkSecret() = getSecret(KEY_DINGTALK_SECRET)

    fun saveFeishu(webhook: String, secret: String) {
        saveSecret(KEY_FEISHU_WEBHOOK, webhook)
        saveSecret(KEY_FEISHU_SECRET, secret)
    }

    fun feishuWebhook() = getSecret(KEY_FEISHU_WEBHOOK)
    fun feishuSecret() = getSecret(KEY_FEISHU_SECRET)

    fun saveWeCom(corpId: String, agentId: String, secret: String, toUser: String) {
        saveSecret(KEY_WECOM_CORP_ID, corpId)
        saveSecret(KEY_WECOM_AGENT_ID, agentId)
        saveSecret(KEY_WECOM_SECRET, secret)
        saveSecret(KEY_WECOM_TO_USER, toUser)
    }

    fun weComCorpId() = getSecret(KEY_WECOM_CORP_ID)
    fun weComAgentId() = getSecret(KEY_WECOM_AGENT_ID)
    fun weComSecret() = getSecret(KEY_WECOM_SECRET)
    fun weComToUser() = getSecret(KEY_WECOM_TO_USER)

    fun saveEmail(host: String, port: Int, user: String, password: String, recipients: String) {
        saveSecret(KEY_EMAIL_HOST, host)
        emailPort = port
        saveSecret(KEY_EMAIL_USER, user)
        saveSecret(KEY_EMAIL_PASSWORD, password)
        saveSecret(KEY_EMAIL_RECIPIENTS, recipients)
    }

    fun emailHost() = getSecret(KEY_EMAIL_HOST)
    fun emailUser() = getSecret(KEY_EMAIL_USER)
    fun emailPassword() = getSecret(KEY_EMAIL_PASSWORD)
    fun emailRecipients() = getSecret(KEY_EMAIL_RECIPIENTS)

    fun anyEnabled() = dingTalkEnabled || feishuEnabled || weComEnabled || emailEnabled

    private fun booleanPreference(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) =
            prefs.getBoolean(key, false)

        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }

    private fun saveSecret(key: String, value: String) {
        if (value.isBlank()) {
            prefs.edit().remove(key).apply()
        } else {
            prefs.edit().putString(key, ForwardingCipher.encrypt(value.trim())).apply()
        }
    }

    private fun getSecret(key: String) = prefs.getString(key, null)
        ?.let(ForwardingCipher::decrypt)
        .orEmpty()

    companion object {
        private const val PREFS_NAME = "multi_channel_forwarding"
        private const val KEY_DINGTALK_ENABLED = "dingtalk_enabled"
        private const val KEY_DINGTALK_WEBHOOK = "dingtalk_webhook"
        private const val KEY_DINGTALK_SECRET = "dingtalk_secret"
        private const val KEY_FEISHU_ENABLED = "feishu_enabled"
        private const val KEY_FEISHU_WEBHOOK = "feishu_webhook"
        private const val KEY_FEISHU_SECRET = "feishu_secret"
        private const val KEY_WECOM_ENABLED = "wecom_enabled"
        private const val KEY_WECOM_CORP_ID = "wecom_corp_id"
        private const val KEY_WECOM_AGENT_ID = "wecom_agent_id"
        private const val KEY_WECOM_SECRET = "wecom_secret"
        private const val KEY_WECOM_TO_USER = "wecom_to_user"
        private const val KEY_EMAIL_ENABLED = "email_enabled"
        private const val KEY_EMAIL_HOST = "email_host"
        private const val KEY_EMAIL_PORT = "email_port"
        private const val KEY_EMAIL_USER = "email_user"
        private const val KEY_EMAIL_PASSWORD = "email_password"
        private const val KEY_EMAIL_RECIPIENTS = "email_recipients"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_SIM_ONE_LABEL = "sim_one_label"
        private const val KEY_SIM_TWO_LABEL = "sim_two_label"
        private const val KEY_SIM_ONE_NUMBER = "sim_one_number"
        private const val KEY_SIM_TWO_NUMBER = "sim_two_number"
        private const val KEY_TEMPLATE_MODE = "template_mode"
        private const val KEY_ACCEPTED_DISCLAIMER_VERSION = "accepted_disclaimer_version"

        const val CURRENT_DISCLAIMER_VERSION = 1

        const val TEMPLATE_COMPACT = 0
        const val TEMPLATE_STANDARD = 1
        const val TEMPLATE_DETAILED = 2
    }
}

private object ForwardingCipher {
    private const val KEY_ALIAS = "multi_forwarding_credentials_key"
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
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
            generateKey()
        }
    }
}
