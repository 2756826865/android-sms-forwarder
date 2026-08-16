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

import org.fossify.messages.messaging.SimSendResolver

class MultiForwardConfig(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var dingTalkEnabled by booleanPreference(KEY_DINGTALK_ENABLED)
    var feishuEnabled by booleanPreference(KEY_FEISHU_ENABLED)
    var weComEnabled by booleanPreference(KEY_WECOM_ENABLED)
    var weComBotEnabled by booleanPreference(KEY_WECOM_BOT_ENABLED)
    var emailEnabled by booleanPreference(KEY_EMAIL_ENABLED)
    var smsDirectEnabled by booleanPreference(KEY_SMS_DIRECT_ENABLED)
    var smsDirectOnlyOnNoNetwork by booleanPreference(KEY_SMS_DIRECT_ONLY_ON_NO_NETWORK)
    var barkEnabled by booleanPreference(KEY_BARK_ENABLED)
    var barkAllowHttp by booleanPreference(KEY_BARK_ALLOW_HTTP)
    var gotifyEnabled by booleanPreference(KEY_GOTIFY_ENABLED)
    var gotifyAllowHttp by booleanPreference(KEY_GOTIFY_ALLOW_HTTP)
    var dingTalkRemoteControlEnabled by booleanPreference(KEY_DINGTALK_REMOTE_CONTROL_ENABLED)

    var dingTalkRemoteSendSimMode: Int
        get() = prefs.getInt(KEY_DINGTALK_REMOTE_SEND_SIM, SimSendMode.DEFAULT).let { mode ->
            when (mode) {
                SimSendMode.SIM1, SimSendMode.SIM2, SimSendMode.DEFAULT -> mode
                else -> SimSendMode.DEFAULT
            }
        }
        set(value) = prefs.edit().putInt(
            KEY_DINGTALK_REMOTE_SEND_SIM,
            when (value) {
                SimSendMode.SIM1, SimSendMode.SIM2, SimSendMode.DEFAULT -> value
                else -> SimSendMode.DEFAULT
            },
        ).apply()

    var dingTalkRemoteConnectionStatus: String
        get() = prefs.getString(KEY_DINGTALK_REMOTE_STATUS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_DINGTALK_REMOTE_STATUS, value).apply()

    fun appendDingTalkRemoteLog(message: String) {
        val now = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val line = "$now $message"
        val current = prefs.getString(KEY_DINGTALK_REMOTE_LOGS, "").orEmpty().lines().filter(String::isNotBlank)
        val logs = (listOf(line) + current).take(30).joinToString("\n")
        prefs.edit().putString(KEY_DINGTALK_REMOTE_LOGS, logs).putString(KEY_DINGTALK_REMOTE_STATUS, line).apply()
    }

    fun dingTalkRemoteLogs(): String = prefs.getString(KEY_DINGTALK_REMOTE_LOGS, "").orEmpty()

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
        get() = prefs.getInt(KEY_TEMPLATE_MODE, TEMPLATE_COMPACT).coerceIn(TEMPLATE_COMPACT, TEMPLATE_CUSTOM)
        set(value) = prefs.edit().putInt(KEY_TEMPLATE_MODE, value.coerceIn(TEMPLATE_COMPACT, TEMPLATE_CUSTOM)).apply()

    var customTemplate: String
        get() = prefs.getString(KEY_CUSTOM_TEMPLATE, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_CUSTOM_TEMPLATE, value.trim()).apply()

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

    var emailSecurity: Int
        get() = if (prefs.contains(KEY_EMAIL_SECURITY)) {
            prefs.getInt(KEY_EMAIL_SECURITY, EMAIL_SECURITY_SSL)
                .coerceIn(EMAIL_SECURITY_SSL, EMAIL_SECURITY_STARTTLS)
        } else if (emailPort == 587) {
            EMAIL_SECURITY_STARTTLS
        } else {
            EMAIL_SECURITY_SSL
        }
        set(value) = prefs.edit()
            .putInt(KEY_EMAIL_SECURITY, value.coerceIn(EMAIL_SECURITY_SSL, EMAIL_SECURITY_STARTTLS))
            .apply()

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

    fun saveBark(serverUrl: String, deviceKey: String) {
        saveSecret(KEY_BARK_SERVER_URL, serverUrl)
        saveSecret(KEY_BARK_DEVICE_KEY, deviceKey)
    }

    fun barkServerUrl() = getSecret(KEY_BARK_SERVER_URL)
    fun barkDeviceKey() = getSecret(KEY_BARK_DEVICE_KEY)

    fun saveGotify(serverUrl: String, token: String) {
        saveSecret(KEY_GOTIFY_SERVER_URL, serverUrl)
        saveSecret(KEY_GOTIFY_TOKEN, token)
    }

    fun gotifyServerUrl() = getSecret(KEY_GOTIFY_SERVER_URL)
    fun gotifyToken() = getSecret(KEY_GOTIFY_TOKEN)

    fun saveDingTalkRemoteControl(clientId: String, clientSecret: String) {
        saveSecret(KEY_DINGTALK_REMOTE_CLIENT_ID, clientId)
        saveSecret(KEY_DINGTALK_REMOTE_CLIENT_SECRET, clientSecret)
    }

    fun dingTalkRemoteClientId() = getSecret(KEY_DINGTALK_REMOTE_CLIENT_ID)
    fun dingTalkRemoteClientSecret() = getSecret(KEY_DINGTALK_REMOTE_CLIENT_SECRET)

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

    fun saveWeComBot(webhook: String) {
        saveSecret(KEY_WECOM_BOT_WEBHOOK, webhook)
    }

    fun weComBotWebhook() = getSecret(KEY_WECOM_BOT_WEBHOOK)

    fun saveEmail(
        host: String,
        port: Int,
        user: String,
        password: String,
        recipients: String,
        security: Int = if (port == 587) EMAIL_SECURITY_STARTTLS else EMAIL_SECURITY_SSL,
    ) {
        saveSecret(KEY_EMAIL_HOST, host)
        emailPort = port
        emailSecurity = security
        saveSecret(KEY_EMAIL_USER, user)
        saveSecret(KEY_EMAIL_PASSWORD, password)
        saveSecret(KEY_EMAIL_RECIPIENTS, recipients)
    }

    fun emailHost() = getSecret(KEY_EMAIL_HOST)
    fun emailUser() = getSecret(KEY_EMAIL_USER)
    fun emailPassword() = getSecret(KEY_EMAIL_PASSWORD)
    fun emailRecipients() = getSecret(KEY_EMAIL_RECIPIENTS)

    fun saveSmsDirect(phone: String) {
        saveSecret(KEY_SMS_DIRECT_PHONE, phone)
    }

    fun smsDirectPhone() = getSecret(KEY_SMS_DIRECT_PHONE)

    fun anyEnabled() = dingTalkEnabled || feishuEnabled || weComEnabled || weComBotEnabled ||
        emailEnabled || smsDirectEnabled || barkEnabled || gotifyEnabled

    fun enabledChannelIds(includePushPlus: Boolean = false): Set<String> = buildSet {
        if (includePushPlus) add(ForwardingChannels.PUSHPLUS)
        if (dingTalkEnabled) add(ForwardingChannels.DINGTALK)
        if (feishuEnabled) add(ForwardingChannels.FEISHU)
        if (weComEnabled) add(ForwardingChannels.WECOM)
        if (weComBotEnabled) add(ForwardingChannels.WECOM_BOT)
        if (emailEnabled) add(ForwardingChannels.EMAIL)
        if (smsDirectEnabled) add(ForwardingChannels.SMS_DIRECT)
        if (barkEnabled) add(ForwardingChannels.BARK)
        if (gotifyEnabled) add(ForwardingChannels.GOTIFY)
    }

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
        ?.let { encrypted ->
            ForwardingCipher.decrypt(encrypted).ifEmpty {
                prefs.edit().remove(key).apply()
                ""
            }
        }
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
        private const val KEY_WECOM_BOT_ENABLED = "wecom_bot_enabled"
        private const val KEY_WECOM_BOT_WEBHOOK = "wecom_bot_webhook"
        private const val KEY_EMAIL_ENABLED = "email_enabled"
        private const val KEY_EMAIL_HOST = "email_host"
        private const val KEY_EMAIL_PORT = "email_port"
        private const val KEY_EMAIL_SECURITY = "email_security"
        private const val KEY_EMAIL_USER = "email_user"
        private const val KEY_EMAIL_PASSWORD = "email_password"
        private const val KEY_EMAIL_RECIPIENTS = "email_recipients"
        private const val KEY_SMS_DIRECT_ENABLED = "sms_direct_enabled"
        private const val KEY_SMS_DIRECT_PHONE = "sms_direct_phone"
        private const val KEY_SMS_DIRECT_ONLY_ON_NO_NETWORK = "sms_direct_only_on_no_network"
        private const val KEY_BARK_ENABLED = "bark_enabled"
        private const val KEY_BARK_SERVER_URL = "bark_server_url"
        private const val KEY_BARK_DEVICE_KEY = "bark_device_key"
        private const val KEY_BARK_ALLOW_HTTP = "bark_allow_http"
        private const val KEY_GOTIFY_ENABLED = "gotify_enabled"
        private const val KEY_GOTIFY_SERVER_URL = "gotify_server_url"
        private const val KEY_GOTIFY_TOKEN = "gotify_token"
        private const val KEY_GOTIFY_ALLOW_HTTP = "gotify_allow_http"
        private const val KEY_DINGTALK_REMOTE_CONTROL_ENABLED = "dingtalk_remote_control_enabled"
        private const val KEY_DINGTALK_REMOTE_CLIENT_ID = "dingtalk_remote_client_id"
        private const val KEY_DINGTALK_REMOTE_CLIENT_SECRET = "dingtalk_remote_client_secret"
        private const val KEY_DINGTALK_REMOTE_SEND_SIM = "dingtalk_remote_send_sim"
        private const val KEY_DINGTALK_REMOTE_STATUS = "dingtalk_remote_status"
        private const val KEY_DINGTALK_REMOTE_LOGS = "dingtalk_remote_logs"
        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_SIM_ONE_LABEL = "sim_one_label"
        private const val KEY_SIM_TWO_LABEL = "sim_two_label"
        private const val KEY_SIM_ONE_NUMBER = "sim_one_number"
        private const val KEY_SIM_TWO_NUMBER = "sim_two_number"
        private const val KEY_TEMPLATE_MODE = "template_mode"
        private const val KEY_CUSTOM_TEMPLATE = "custom_template"
        private const val KEY_ACCEPTED_DISCLAIMER_VERSION = "accepted_disclaimer_version"

        const val CURRENT_DISCLAIMER_VERSION = 1

        const val EMAIL_SECURITY_SSL = 0
        const val EMAIL_SECURITY_STARTTLS = 1

        const val TEMPLATE_COMPACT = 0
        const val TEMPLATE_STANDARD = 1
        const val TEMPLATE_DETAILED = 2
        const val TEMPLATE_EMOJI = 3
        const val TEMPLATE_CUSTOM = 4
    }
}

/** Alias for [SimSendResolver] modes used in DingTalk remote settings UI. */
object SimSendMode {
    const val DEFAULT = SimSendResolver.MODE_DEFAULT
    const val SIM1 = SimSendResolver.MODE_SIM1
    const val SIM2 = SimSendResolver.MODE_SIM2
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
