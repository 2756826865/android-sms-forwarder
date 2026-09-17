package org.fossify.messages.forwarding

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
// 注意：这里用 java.util.Base64 而非 android.util.Base64。
// android.jar 在 JVM 单元测试里是 Stub（android.util.Base64.decode 直接抛异常），
// 若用它，"明文 / 密文"判定在单测中恒为 false，任何针对该判定的用例都会变成空跑。
// 两者编码结果一致（标准字母表 + padding、无换行），存量密文可无缝互读；minSdk 26 已支持。
import java.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.fossify.messages.messaging.SimSendResolver
import org.fossify.messages.security.audit.SecurityAuditEventType
import org.fossify.messages.security.audit.SecurityAuditManager
import org.fossify.messages.security.crypto.CredentialHealth

class MultiForwardConfig(
    private val context: Context? = null,
    customPrefs: SharedPreferences? = null,
    // 凭据加解密实现可注入：生产走 AndroidKeystoreCipher，
    // 单元测试环境无 AndroidKeyStore，注入 PlaintextCipher 才能覆盖凭据读写逻辑。
    private val cipher: CredentialCipher = AndroidKeystoreCipher
) {
    private val prefs: SharedPreferences = customPrefs
        ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?: error("Either context or customPrefs must be provided")

    // 15 大通道开关
    var pushPlusEnabled: Boolean
        get() = prefs.getBoolean(KEY_PUSHPLUS_ENABLED, false) ||
            (context?.getSharedPreferences("pushplus_forwarding", Context.MODE_PRIVATE)?.getBoolean("enabled", false) ?: false)
        set(value) {
            prefs.edit().putBoolean(KEY_PUSHPLUS_ENABLED, value).apply()
            context?.getSharedPreferences("pushplus_forwarding", Context.MODE_PRIVATE)?.edit()?.putBoolean("enabled", value)?.apply()
        }
    var wechatTestEnabled by booleanPreference(KEY_WECHAT_TEST_ENABLED)
    var qqEnabled by booleanPreference(KEY_QQ_ENABLED)
    var weComEnabled by booleanPreference(KEY_WECOM_ENABLED)
    var weComBotEnabled by booleanPreference(KEY_WECOM_BOT_ENABLED)
    var feishuAppEnabled by booleanPreference(KEY_FEISHU_APP_ENABLED)
    var feishuEnabled by booleanPreference(KEY_FEISHU_ENABLED)
    var dingTalkEnabled by booleanPreference(KEY_DINGTALK_ENABLED)
    var barkEnabled by booleanPreference(KEY_BARK_ENABLED)
    var barkAllowHttp by booleanPreference(KEY_BARK_ALLOW_HTTP)
    var websocketEnabled by booleanPreference(KEY_WEBSOCKET_ENABLED)
    var telegramEnabled by booleanPreference(KEY_TELEGRAM_ENABLED)
    var discordEnabled by booleanPreference(KEY_DISCORD_ENABLED)
    var tencentCloudEnabled by booleanPreference(KEY_TENCENT_CLOUD_ENABLED)
    var emailEnabled by booleanPreference(KEY_EMAIL_ENABLED)
    var smsDirectEnabled by booleanPreference(KEY_SMS_DIRECT_ENABLED)
    var smsDirectOnlyOnNoNetwork by booleanPreference(KEY_SMS_DIRECT_ONLY_ON_NO_NETWORK)
    var customWebhookEnabled by booleanPreference(KEY_CUSTOM_WEBHOOK_ENABLED)
    var channelGroupEnabled by booleanPreference(KEY_CHANNEL_GROUP_ENABLED)
    var gotifyEnabled by booleanPreference(KEY_GOTIFY_ENABLED)
    var gotifyAllowHttp by booleanPreference(KEY_GOTIFY_ALLOW_HTTP)
    var dingTalkRemoteControlEnabled by booleanPreference(KEY_DINGTALK_REMOTE_CONTROL_ENABLED)
    var enablePrivacyMask by booleanPreference(KEY_ENABLE_PRIVACY_MASK)
    var maskVerificationCode by booleanPreference(KEY_MASK_VERIFICATION_CODE)
    var markAsReadAfterForward by booleanPreference(KEY_MARK_AS_READ_AFTER_FORWARD)

    var forwardingDelaySeconds: Int
        get() = prefs.getInt(KEY_FORWARDING_DELAY_SECONDS, 0).coerceIn(0, 60)
        set(value) = prefs.edit().putInt(KEY_FORWARDING_DELAY_SECONDS, value.coerceIn(0, 60)).apply()

    var keepAliveServiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_KEEP_ALIVE_SERVICE_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_KEEP_ALIVE_SERVICE_ENABLED, value).apply()

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

    // 飞书远程控制
    var feishuRemoteControlEnabled by booleanPreference(KEY_FEISHU_REMOTE_CONTROL_ENABLED)
    var feishuRemoteSendSimMode: Int
        get() = prefs.getInt(KEY_FEISHU_REMOTE_SEND_SIM, SimSendMode.DEFAULT).let { mode ->
            when (mode) {
                SimSendMode.SIM1, SimSendMode.SIM2, SimSendMode.DEFAULT -> mode
                else -> SimSendMode.DEFAULT
            }
        }
        set(value) = prefs.edit().putInt(
            KEY_FEISHU_REMOTE_SEND_SIM,
            when (value) {
                SimSendMode.SIM1, SimSendMode.SIM2, SimSendMode.DEFAULT -> value
                else -> SimSendMode.DEFAULT
            },
        ).apply()

    var feishuRemoteConnectionStatus: String
        get() = prefs.getString(KEY_FEISHU_REMOTE_STATUS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_FEISHU_REMOTE_STATUS, value).apply()

    fun appendFeishuRemoteLog(message: String) {
        val now = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val line = "$now $message"
        val current = prefs.getString(KEY_FEISHU_REMOTE_LOGS, "").orEmpty().lines().filter(String::isNotBlank)
        val logs = (listOf(line) + current).take(30).joinToString("\n")
        prefs.edit().putString(KEY_FEISHU_REMOTE_LOGS, logs).putString(KEY_FEISHU_REMOTE_STATUS, line).apply()
    }

    fun feishuRemoteLogs(): String = prefs.getString(KEY_FEISHU_REMOTE_LOGS, "").orEmpty()

    // 邮箱远程控制
    var emailRemoteControlEnabled by booleanPreference(KEY_EMAIL_REMOTE_CONTROL_ENABLED)
    var emailRemoteSendSimMode: Int
        get() = prefs.getInt(KEY_EMAIL_REMOTE_SEND_SIM, SimSendMode.DEFAULT).let { mode ->
            when (mode) {
                SimSendMode.SIM1, SimSendMode.SIM2, SimSendMode.DEFAULT -> mode
                else -> SimSendMode.DEFAULT
            }
        }
        set(value) = prefs.edit().putInt(
            KEY_EMAIL_REMOTE_SEND_SIM,
            when (value) {
                SimSendMode.SIM1, SimSendMode.SIM2, SimSendMode.DEFAULT -> value
                else -> SimSendMode.DEFAULT
            },
        ).apply()

    var emailRemoteConnectionStatus: String
        get() = prefs.getString(KEY_EMAIL_REMOTE_STATUS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_EMAIL_REMOTE_STATUS, value).apply()

    var emailRemoteSecurity: Int
        get() = prefs.getInt(KEY_EMAIL_REMOTE_SECURITY, EMAIL_SECURITY_SSL)
        set(value) = prefs.edit().putInt(KEY_EMAIL_REMOTE_SECURITY, value).apply()

    fun appendEmailRemoteLog(message: String) {
        val now = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val line = "$now $message"
        val current = prefs.getString(KEY_EMAIL_REMOTE_LOGS, "").orEmpty().lines().filter(String::isNotBlank)
        val logs = (listOf(line) + current).take(30).joinToString("\n")
        prefs.edit().putString(KEY_EMAIL_REMOTE_LOGS, logs).putString(KEY_EMAIL_REMOTE_STATUS, line).apply()
    }

    fun emailRemoteLogs(): String = prefs.getString(KEY_EMAIL_REMOTE_LOGS, "").orEmpty()

    // Telegram 远程控制
    var telegramRemoteControlEnabled by booleanPreference(KEY_TELEGRAM_REMOTE_CONTROL_ENABLED)
    var telegramRemoteSendSimMode: Int
        get() = prefs.getInt(KEY_TELEGRAM_REMOTE_SEND_SIM, SimSendMode.DEFAULT).let { mode ->
            when (mode) {
                SimSendMode.SIM1, SimSendMode.SIM2, SimSendMode.DEFAULT -> mode
                else -> SimSendMode.DEFAULT
            }
        }
        set(value) = prefs.edit().putInt(
            KEY_TELEGRAM_REMOTE_SEND_SIM,
            when (value) {
                SimSendMode.SIM1, SimSendMode.SIM2, SimSendMode.DEFAULT -> value
                else -> SimSendMode.DEFAULT
            },
        ).apply()

    var telegramRemoteConnectionStatus: String
        get() = prefs.getString(KEY_TELEGRAM_REMOTE_STATUS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_TELEGRAM_REMOTE_STATUS, value).apply()

    fun appendTelegramRemoteLog(message: String) {
        val now = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val line = "$now $message"
        val current = prefs.getString(KEY_TELEGRAM_REMOTE_LOGS, "").orEmpty().lines().filter(String::isNotBlank)
        val logs = (listOf(line) + current).take(30).joinToString("\n")
        prefs.edit().putString(KEY_TELEGRAM_REMOTE_LOGS, logs).putString(KEY_TELEGRAM_REMOTE_STATUS, line).apply()
    }

    fun telegramRemoteLogs(): String = prefs.getString(KEY_TELEGRAM_REMOTE_LOGS, "").orEmpty()

    // WebSocket 远程控制
    var websocketRemoteControlEnabled by booleanPreference(KEY_WEBSOCKET_REMOTE_CONTROL_ENABLED)
    var websocketRemoteSendSimMode: Int
        get() = prefs.getInt(KEY_WEBSOCKET_REMOTE_SEND_SIM, SimSendMode.DEFAULT).let { mode ->
            when (mode) {
                SimSendMode.SIM1, SimSendMode.SIM2, SimSendMode.DEFAULT -> mode
                else -> SimSendMode.DEFAULT
            }
        }
        set(value) = prefs.edit().putInt(
            KEY_WEBSOCKET_REMOTE_SEND_SIM,
            when (value) {
                SimSendMode.SIM1, SimSendMode.SIM2, SimSendMode.DEFAULT -> value
                else -> SimSendMode.DEFAULT
            },
        ).apply()

    var websocketRemoteConnectionStatus: String
        get() = prefs.getString(KEY_WEBSOCKET_REMOTE_STATUS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_WEBSOCKET_REMOTE_STATUS, value).apply()

    fun appendWebSocketRemoteLog(message: String) {
        val now = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val line = "$now $message"
        val current = prefs.getString(KEY_WEBSOCKET_REMOTE_LOGS, "").orEmpty().lines().filter(String::isNotBlank)
        val logs = (listOf(line) + current).take(30).joinToString("\n")
        prefs.edit().putString(KEY_WEBSOCKET_REMOTE_LOGS, logs).putString(KEY_WEBSOCKET_REMOTE_STATUS, line).apply()
    }

    fun websocketRemoteLogs(): String = prefs.getString(KEY_WEBSOCKET_REMOTE_LOGS, "").orEmpty()

    // 企业微信智能机器人长连接远程控制
    var weComRemoteControlEnabled by booleanPreference(KEY_WECOM_REMOTE_CONTROL_ENABLED)
    var weComRemoteSendSimMode: Int
        get() = prefs.getInt(KEY_WECOM_REMOTE_SEND_SIM, SimSendMode.DEFAULT).let { mode ->
            when (mode) {
                SimSendMode.SIM1, SimSendMode.SIM2, SimSendMode.DEFAULT -> mode
                else -> SimSendMode.DEFAULT
            }
        }
        set(value) = prefs.edit().putInt(
            KEY_WECOM_REMOTE_SEND_SIM,
            when (value) {
                SimSendMode.SIM1, SimSendMode.SIM2, SimSendMode.DEFAULT -> value
                else -> SimSendMode.DEFAULT
            },
        ).apply()

    var weComRemoteConnectionStatus: String
        get() = prefs.getString(KEY_WECOM_REMOTE_STATUS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_WECOM_REMOTE_STATUS, value).apply()

    fun appendWeComRemoteLog(message: String) {
        val now = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val line = "$now $message"
        val current = prefs.getString(KEY_WECOM_REMOTE_LOGS, "").orEmpty().lines().filter(String::isNotBlank)
        val logs = (listOf(line) + current).take(30).joinToString("\n")
        prefs.edit().putString(KEY_WECOM_REMOTE_LOGS, logs).putString(KEY_WECOM_REMOTE_STATUS, line).apply()
    }

    fun weComRemoteLogs(): String = prefs.getString(KEY_WECOM_REMOTE_LOGS, "").orEmpty()

    fun saveWeComRemoteControl(
        botId: String,
        secret: String,
        chatId: String = "",
        customPrefix: String = "",
    ) {
        saveSecret(KEY_WECOM_REMOTE_BOT_ID, botId)
        saveSecret(KEY_WECOM_REMOTE_SECRET, secret)
        saveSecret(KEY_WECOM_REMOTE_CHAT_ID, chatId)
        prefs.edit().putString(KEY_WECOM_REMOTE_CUSTOM_PREFIX, customPrefix.trim()).apply()
    }
    fun weComRemoteBotId() = getSecret(KEY_WECOM_REMOTE_BOT_ID)
    fun weComRemoteSecret() = getSecret(KEY_WECOM_REMOTE_SECRET)
    fun weComRemoteChatId() = getSecret(KEY_WECOM_REMOTE_CHAT_ID)
    fun weComRemoteCustomPrefix() = prefs.getString(KEY_WECOM_REMOTE_CUSTOM_PREFIX, "").orEmpty()

    var lastCapturedWeComChatId: String
        get() = prefs.getString(KEY_LAST_CAPTURED_WECOM_CHAT_ID, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_LAST_CAPTURED_WECOM_CHAT_ID, value.trim()).apply()

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

    // 1. PushPlus
    fun savePushPlus(token: String, topic: String = "") {
        saveSecret(KEY_PUSHPLUS_TOKEN, token)
        saveSecret(KEY_PUSHPLUS_TOPIC, topic)
    }
    fun pushPlusToken(): String {
        val token = getSecret(KEY_PUSHPLUS_TOKEN)
        if (token.isNotBlank()) return token
        return ""
    }
    fun pushPlusTopic() = getSecret(KEY_PUSHPLUS_TOPIC)

    // 2. 微信测试号
    fun saveWechatTest(appId: String, appSecret: String, templateId: String, openId: String) {
        saveSecret(KEY_WECHAT_TEST_APP_ID, appId)
        saveSecret(KEY_WECHAT_TEST_APP_SECRET, appSecret)
        saveSecret(KEY_WECHAT_TEST_TEMPLATE_ID, templateId)
        saveSecret(KEY_WECHAT_TEST_OPEN_ID, openId)
    }
    fun wechatTestAppId() = getSecret(KEY_WECHAT_TEST_APP_ID)
    fun wechatTestAppSecret() = getSecret(KEY_WECHAT_TEST_APP_SECRET)
    fun wechatTestTemplateId() = getSecret(KEY_WECHAT_TEST_TEMPLATE_ID)
    fun wechatTestOpenId() = getSecret(KEY_WECHAT_TEST_OPEN_ID)

    // 3. QQ (Qmsg / OneBot)
    fun saveQq(webhookOrKey: String, type: String = "qmsg") {
        saveSecret(KEY_QQ_WEBHOOK, webhookOrKey)
        saveSecret(KEY_QQ_TYPE, type)
    }
    fun qqWebhook() = getSecret(KEY_QQ_WEBHOOK)
    fun qqType() = getSecret(KEY_QQ_TYPE).ifBlank { "qmsg" }

    // 4. 企业微信应用号
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

    // 5. 企业微信群机器人
    fun saveWeComBot(webhook: String) {
        saveSecret(KEY_WECOM_BOT_WEBHOOK, webhook)
    }
    fun weComBotWebhook() = getSecret(KEY_WECOM_BOT_WEBHOOK)

    // 6. 飞书自建应用
    fun saveFeishuApp(appId: String, appSecret: String, receiveId: String) {
        saveSecret(KEY_FEISHU_APP_ID, appId)
        saveSecret(KEY_FEISHU_APP_SECRET, appSecret)
        saveSecret(KEY_FEISHU_RECEIVE_ID, receiveId)
    }
    fun feishuAppId() = getSecret(KEY_FEISHU_APP_ID)
    fun feishuAppSecret() = getSecret(KEY_FEISHU_APP_SECRET)
    fun feishuReceiveId() = getSecret(KEY_FEISHU_RECEIVE_ID)

    // 7. 飞书群机器人
    fun saveFeishu(webhook: String, secret: String) {
        saveSecret(KEY_FEISHU_WEBHOOK, webhook)
        saveSecret(KEY_FEISHU_SECRET, secret)
    }
    fun feishuWebhook() = getSecret(KEY_FEISHU_WEBHOOK)
    fun feishuSecret() = getSecret(KEY_FEISHU_SECRET)

    // 8. 钉钉群机器人
    fun saveDingTalk(webhook: String, secret: String) {
        saveSecret(KEY_DINGTALK_WEBHOOK, webhook)
        saveSecret(KEY_DINGTALK_SECRET, secret)
    }
    fun dingTalkWebhook() = getSecret(KEY_DINGTALK_WEBHOOK)
    fun dingTalkSecret() = getSecret(KEY_DINGTALK_SECRET)

    fun saveDingTalkRemoteControl(clientId: String, clientSecret: String, customPrefix: String = "") {
        saveSecret(KEY_DINGTALK_REMOTE_CLIENT_ID, clientId)
        saveSecret(KEY_DINGTALK_REMOTE_CLIENT_SECRET, clientSecret)
        prefs.edit().putString(KEY_DINGTALK_REMOTE_CUSTOM_PREFIX, customPrefix.trim()).apply()
    }
    fun dingTalkRemoteClientId() = getSecret(KEY_DINGTALK_REMOTE_CLIENT_ID)
    fun dingTalkRemoteClientSecret() = getSecret(KEY_DINGTALK_REMOTE_CLIENT_SECRET)
    fun dingTalkRemoteCustomPrefix() = prefs.getString(KEY_DINGTALK_REMOTE_CUSTOM_PREFIX, "").orEmpty()

    fun saveFeishuRemoteControl(appId: String, appSecret: String, customPrefix: String = "") {
        saveSecret(KEY_FEISHU_REMOTE_APP_ID, appId)
        saveSecret(KEY_FEISHU_REMOTE_APP_SECRET, appSecret)
        prefs.edit().putString(KEY_FEISHU_REMOTE_CUSTOM_PREFIX, customPrefix.trim()).apply()
    }
    fun feishuRemoteAppId() = getSecret(KEY_FEISHU_REMOTE_APP_ID)
    fun feishuRemoteAppSecret() = getSecret(KEY_FEISHU_REMOTE_APP_SECRET)
    fun feishuRemoteCustomPrefix() = prefs.getString(KEY_FEISHU_REMOTE_CUSTOM_PREFIX, "").orEmpty()

    fun saveEmailRemoteControl(
        host: String,
        port: Int,
        user: String,
        pass: String,
        authorizedSenders: String = "",
        security: Int = EMAIL_SECURITY_SSL,
        customPrefix: String = "",
    ) {
        prefs.edit()
            .putString(KEY_EMAIL_REMOTE_HOST, host.trim())
            .putInt(KEY_EMAIL_REMOTE_PORT, port)
            .putString(KEY_EMAIL_REMOTE_AUTH_SENDERS, authorizedSenders.trim())
            .putInt(KEY_EMAIL_REMOTE_SECURITY, security)
            .putString(KEY_EMAIL_REMOTE_CUSTOM_PREFIX, customPrefix.trim())
            .apply()
        saveSecret(KEY_EMAIL_REMOTE_USER, user)
        saveSecret(KEY_EMAIL_REMOTE_PASSWORD, pass)
    }
    fun emailRemoteHost() = prefs.getString(KEY_EMAIL_REMOTE_HOST, "").orEmpty()
    fun emailRemotePort() = prefs.getInt(KEY_EMAIL_REMOTE_PORT, 993)
    fun emailRemoteUser() = getSecret(KEY_EMAIL_REMOTE_USER)
    fun emailRemotePassword() = getSecret(KEY_EMAIL_REMOTE_PASSWORD)
    fun emailRemoteAuthorizedSenders() = prefs.getString(KEY_EMAIL_REMOTE_AUTH_SENDERS, "").orEmpty()
    fun emailRemoteCustomPrefix() = prefs.getString(KEY_EMAIL_REMOTE_CUSTOM_PREFIX, "").orEmpty()

    fun saveTelegramRemoteControl(
        botToken: String,
        chatId: String,
        customHost: String = "",
        authorizedUsers: String = "",
        customPrefix: String = "",
    ) {
        saveSecret(KEY_TELEGRAM_REMOTE_BOT_TOKEN, botToken)
        saveSecret(KEY_TELEGRAM_REMOTE_CHAT_ID, chatId)
        prefs.edit()
            .putString(KEY_TELEGRAM_REMOTE_CUSTOM_HOST, customHost.trim())
            .putString(KEY_TELEGRAM_REMOTE_AUTH_USERS, authorizedUsers.trim())
            .putString(KEY_TELEGRAM_REMOTE_CUSTOM_PREFIX, customPrefix.trim())
            .apply()
    }
    fun telegramRemoteBotToken() = getSecret(KEY_TELEGRAM_REMOTE_BOT_TOKEN)
    fun telegramRemoteChatId() = getSecret(KEY_TELEGRAM_REMOTE_CHAT_ID)
    fun telegramRemoteCustomHost() = prefs.getString(KEY_TELEGRAM_REMOTE_CUSTOM_HOST, "").orEmpty()
    fun telegramRemoteAuthorizedUsers() = prefs.getString(KEY_TELEGRAM_REMOTE_AUTH_USERS, "").orEmpty()
    fun telegramRemoteCustomPrefix() = prefs.getString(KEY_TELEGRAM_REMOTE_CUSTOM_PREFIX, "").orEmpty()

    fun saveWebSocketRemoteControl(serverUrl: String, token: String = "", customPrefix: String = "") {
        saveSecret(KEY_WEBSOCKET_REMOTE_URL, serverUrl)
        saveSecret(KEY_WEBSOCKET_REMOTE_TOKEN, token)
        prefs.edit().putString(KEY_WEBSOCKET_REMOTE_CUSTOM_PREFIX, customPrefix.trim()).apply()
    }
    fun websocketRemoteUrl() = getSecret(KEY_WEBSOCKET_REMOTE_URL).ifBlank { websocketUrl() }
    fun websocketRemoteToken() = getSecret(KEY_WEBSOCKET_REMOTE_TOKEN).ifBlank { websocketToken() }
    fun websocketRemoteCustomPrefix() = prefs.getString(KEY_WEBSOCKET_REMOTE_CUSTOM_PREFIX, "").orEmpty()

    // 9. Bark
    fun saveBark(serverUrl: String, deviceKey: String) {
        saveSecret(KEY_BARK_SERVER_URL, serverUrl)
        saveSecret(KEY_BARK_DEVICE_KEY, deviceKey)
    }
    fun barkServerUrl() = getSecret(KEY_BARK_SERVER_URL).ifBlank { "https://api.day.app" }
    fun barkDeviceKey() = getSecret(KEY_BARK_DEVICE_KEY)

    // 10. WebSocket 客户端
    fun saveWebsocket(serverUrl: String, token: String = "") {
        saveSecret(KEY_WEBSOCKET_URL, serverUrl)
        saveSecret(KEY_WEBSOCKET_TOKEN, token)
    }
    fun websocketUrl() = getSecret(KEY_WEBSOCKET_URL)
    fun websocketToken() = getSecret(KEY_WEBSOCKET_TOKEN)

    // 11. Telegram 机器人
    fun saveTelegram(botToken: String, chatId: String) {
        saveSecret(KEY_TELEGRAM_BOT_TOKEN, botToken)
        saveSecret(KEY_TELEGRAM_CHAT_ID, chatId)
    }
    fun telegramBotToken() = getSecret(KEY_TELEGRAM_BOT_TOKEN)
    fun telegramChatId() = getSecret(KEY_TELEGRAM_CHAT_ID)

    // 12. Discord 机器人
    fun saveDiscord(webhook: String) {
        saveSecret(KEY_DISCORD_WEBHOOK, webhook)
    }
    fun discordWebhook() = getSecret(KEY_DISCORD_WEBHOOK)

    // 13. 腾讯云自定义告警
    fun saveTencentCloud(webhook: String, secret: String = "") {
        saveSecret(KEY_TENCENT_CLOUD_WEBHOOK, webhook)
        saveSecret(KEY_TENCENT_CLOUD_SECRET, secret)
    }
    fun tencentCloudWebhook() = getSecret(KEY_TENCENT_CLOUD_WEBHOOK)
    fun tencentCloudSecret() = getSecret(KEY_TENCENT_CLOUD_SECRET)

    // 14. 邮件 SMTP
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

    // 15. 短信直发
    fun saveSmsDirect(phone: String) {
        saveSecret(KEY_SMS_DIRECT_PHONE, phone)
    }
    fun smsDirectPhone() = getSecret(KEY_SMS_DIRECT_PHONE)

    // 16. 自定义 Webhook
    fun saveCustomWebhook(
        url: String,
        headers: String = "",
        method: String = "POST",
        contentType: String = "application/json",
        bodyTemplate: String = DEFAULT_CUSTOM_WEBHOOK_BODY
    ) {
        saveSecret(KEY_CUSTOM_WEBHOOK_URL, url)
        saveSecret(KEY_CUSTOM_WEBHOOK_HEADERS, headers)
        prefs.edit()
            .putString(KEY_CUSTOM_WEBHOOK_METHOD, method.uppercase())
            .putString(KEY_CUSTOM_WEBHOOK_CONTENT_TYPE, contentType)
            .putString(KEY_CUSTOM_WEBHOOK_BODY, bodyTemplate)
            .apply()
    }
    fun customWebhookUrl() = getSecret(KEY_CUSTOM_WEBHOOK_URL)
    fun customWebhookHeaders() = getSecret(KEY_CUSTOM_WEBHOOK_HEADERS)
    fun customWebhookMethod() = prefs.getString(KEY_CUSTOM_WEBHOOK_METHOD, "POST").orEmpty().ifBlank { "POST" }
    fun customWebhookContentType() = prefs.getString(KEY_CUSTOM_WEBHOOK_CONTENT_TYPE, "application/json").orEmpty().ifBlank { "application/json" }
    fun customWebhookBodyTemplate() = prefs.getString(KEY_CUSTOM_WEBHOOK_BODY, DEFAULT_CUSTOM_WEBHOOK_BODY).orEmpty()

    // 17. 群组消息成员
    fun saveChannelGroupMembers(members: Set<String>) {
        prefs.edit().putStringSet(KEY_CHANNEL_GROUP_MEMBERS, members).apply()
    }
    fun channelGroupMembers(): Set<String> = prefs.getStringSet(KEY_CHANNEL_GROUP_MEMBERS, emptySet()).orEmpty()

    // Gotify
    fun saveGotify(serverUrl: String, token: String) {
        saveSecret(KEY_GOTIFY_SERVER_URL, serverUrl)
        saveSecret(KEY_GOTIFY_TOKEN, token)
    }
    fun gotifyServerUrl() = getSecret(KEY_GOTIFY_SERVER_URL)
    fun gotifyToken() = getSecret(KEY_GOTIFY_TOKEN)

    fun anyEnabled() = channelInstances().any { it.enabled } ||
        pushPlusEnabled || wechatTestEnabled || qqEnabled || weComEnabled || weComBotEnabled ||
        feishuAppEnabled || feishuEnabled || dingTalkEnabled || barkEnabled || websocketEnabled ||
        telegramEnabled || discordEnabled || tencentCloudEnabled || emailEnabled || smsDirectEnabled ||
        customWebhookEnabled || channelGroupEnabled || gotifyEnabled

    fun isChannelEnabled(channel: String): Boolean {
        if (channelInstances().any { it.channelType == channel && it.enabled }) return true
        return when (channel) {
            ForwardingChannels.PUSHPLUS -> pushPlusEnabled
            ForwardingChannels.WECHAT_TEST -> wechatTestEnabled
            ForwardingChannels.QQ -> qqEnabled
            ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> weComEnabled
            ForwardingChannels.WECOM_BOT -> weComBotEnabled
            ForwardingChannels.FEISHU_APP -> feishuAppEnabled
            ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT -> feishuEnabled
            ForwardingChannels.DINGTALK -> dingTalkEnabled
            ForwardingChannels.BARK -> barkEnabled
            ForwardingChannels.WEBSOCKET -> websocketEnabled
            ForwardingChannels.TELEGRAM -> telegramEnabled
            ForwardingChannels.DISCORD -> discordEnabled
            ForwardingChannels.TENCENT_CLOUD -> tencentCloudEnabled
            ForwardingChannels.EMAIL -> emailEnabled
            ForwardingChannels.SMS_DIRECT -> smsDirectEnabled
            ForwardingChannels.CUSTOM_WEBHOOK -> customWebhookEnabled
            ForwardingChannels.CHANNEL_GROUP -> channelGroupEnabled
            ForwardingChannels.GOTIFY -> gotifyEnabled
            else -> false
        }
    }

    // 多实例渠道池 (Multi-Instance Channel Hub)
    fun channelInstances(): List<ForwardingChannelInstance> = runCatching {
        val raw = prefs.getString(KEY_CHANNEL_INSTANCES, null)
        if (raw.isNullOrBlank()) return@runCatching emptyList<ForwardingChannelInstance>()
        val array = org.json.JSONArray(raw)
        buildList {
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val stored = ForwardingChannelInstance.fromJson(obj)
                val encryptedConfig = obj.optString("configJsonEncrypted")
                val decryptedConfig = encryptedConfig.takeIf(String::isNotBlank)
                    ?.let { cipher.decrypt(it) }
                    .orEmpty()
                add(stored.copy(configJson = decryptedConfig.ifBlank { stored.configJson }))
            }
        }
    }.getOrDefault(emptyList())

    /**
     * 保存通道实例列表。
     *
     * P0-3 止血：任一实例的 configJson 加密失败时**整批中止、完全不写盘**。
     * 历史实现在 `encrypt()` 返回空串时会把明文 configJson 原样落盘，
     * 且可能留下 configJson 为空、configJsonEncrypted 仍是旧值的半截不一致配置。
     * 整批中止保证磁盘永远是自洽状态：要么全部加密成功，要么一点都不动。
     *
     * @return 是否成功落盘；false 表示本次未写入，磁盘保持原样。
     */
    fun saveChannelInstances(instances: List<ForwardingChannelInstance>): Boolean {
        val existingEncryptedById = runCatching {
            val previousRaw = prefs.getString(KEY_CHANNEL_INSTANCES, "[]").orEmpty().ifBlank { "[]" }
            val previous = org.json.JSONArray(previousRaw)
            buildMap {
                for (index in 0 until previous.length()) {
                    val item = previous.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val encrypted = item.optString("configJsonEncrypted")
                    if (id.isNotBlank() && encrypted.isNotBlank()) put(id, encrypted)
                }
            }
        }.getOrDefault(emptyMap())
        // 第一步：对全部实例逐个尝试加密并收集结果。任一项失败即整批中止，
        // 绝不进入写盘阶段，避免留下"部分明文 + 部分旧密文"的半截配置。
        val encryptedById = LinkedHashMap<String, String>()
        for (instance in instances) {
            // 若旧密文因 Keystore 暂时不可用而未能解出，读取层会给出空对象。
            // 此时保留原密文，避免用户仅切换开关就永久覆盖凭据。
            val preservedEncrypted = existingEncryptedById[instance.id]
                .takeIf { instance.configJson.isBlank() || instance.configJson == "{}" }
            if (preservedEncrypted != null) {
                encryptedById[instance.id] = preservedEncrypted
                continue
            }
            val plaintextConfig = instance.configJson.takeUnless { it.isBlank() || it == "{}" } ?: continue
            val encrypted = cipher.encrypt(plaintextConfig)
            if (encrypted.isBlank()) {
                CredentialHealth.markEncryptFailed(KEY_CHANNEL_INSTANCES)
                SecurityAuditManager.logEvent(
                    SecurityAuditEventType.SECRET_ENCRYPT_FAILED,
                    KEY_CHANNEL_INSTANCES,
                    "keystore unavailable; aborted the whole channel instance batch"
                )
                return false
            }
            encryptedById[instance.id] = encrypted
        }

        // 第二步：全部加密成功后才统一组装并落盘。
        val array = org.json.JSONArray()
        for (instance in instances) {
            val obj = instance.toJson()
            val encryptedConfig = encryptedById[instance.id]
            if (!encryptedConfig.isNullOrBlank()) {
                obj.put("configJson", "{}")
                obj.put("configJsonEncrypted", encryptedConfig)
            }
            array.put(obj)
        }
        prefs.edit().putString(KEY_CHANNEL_INSTANCES, array.toString()).apply()
        CredentialHealth.clear(KEY_CHANNEL_INSTANCES)
        return true
    }

    fun addChannelInstance(instance: ForwardingChannelInstance) {
        val current = channelInstances().toMutableList()
        val existingIndex = current.indexOfFirst { it.id == instance.id }
        if (existingIndex >= 0) {
            current[existingIndex] = instance
        } else {
            current.add(instance)
        }
        saveChannelInstances(current)
    }

    fun removeChannelInstance(instanceId: String) {
        val current = channelInstances().filterNot { it.id == instanceId }
        saveChannelInstances(current)
    }

    fun getChannelInstanceById(instanceId: String): ForwardingChannelInstance? {
        return channelInstances().firstOrNull { it.id == instanceId }
    }

    fun setChannelEnabled(channel: String, enabled: Boolean) {
        when (channel) {
            ForwardingChannels.PUSHPLUS -> pushPlusEnabled = enabled
            ForwardingChannels.WECHAT_TEST -> wechatTestEnabled = enabled
            ForwardingChannels.QQ -> qqEnabled = enabled
            ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> weComEnabled = enabled
            ForwardingChannels.WECOM_BOT -> weComBotEnabled = enabled
            ForwardingChannels.FEISHU_APP -> feishuAppEnabled = enabled
            ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT -> feishuEnabled = enabled
            ForwardingChannels.DINGTALK -> dingTalkEnabled = enabled
            ForwardingChannels.BARK -> barkEnabled = enabled
            ForwardingChannels.WEBSOCKET -> websocketEnabled = enabled
            ForwardingChannels.TELEGRAM -> telegramEnabled = enabled
            ForwardingChannels.DISCORD -> discordEnabled = enabled
            ForwardingChannels.TENCENT_CLOUD -> tencentCloudEnabled = enabled
            ForwardingChannels.EMAIL -> emailEnabled = enabled
            ForwardingChannels.SMS_DIRECT -> smsDirectEnabled = enabled
            ForwardingChannels.CUSTOM_WEBHOOK -> customWebhookEnabled = enabled
            ForwardingChannels.CHANNEL_GROUP -> channelGroupEnabled = enabled
            ForwardingChannels.GOTIFY -> gotifyEnabled = enabled
        }
    }

    fun enabledChannelIds(includePushPlus: Boolean = false): Set<String> = buildSet {
        channelInstances().filter { it.enabled && it.hasDispatchConfiguration() }.forEach { add(it.channelType) }
        if (includePushPlus || pushPlusEnabled) add(ForwardingChannels.PUSHPLUS)
        if (wechatTestEnabled) add(ForwardingChannels.WECHAT_TEST)
        if (qqEnabled) add(ForwardingChannels.QQ)
        if (weComEnabled) add(ForwardingChannels.WECOM_APP)
        if (weComBotEnabled) add(ForwardingChannels.WECOM_BOT)
        if (feishuAppEnabled) add(ForwardingChannels.FEISHU_APP)
        if (feishuEnabled) add(ForwardingChannels.FEISHU_BOT)
        if (dingTalkEnabled) add(ForwardingChannels.DINGTALK)
        if (barkEnabled) add(ForwardingChannels.BARK)
        if (websocketEnabled) add(ForwardingChannels.WEBSOCKET)
        if (telegramEnabled) add(ForwardingChannels.TELEGRAM)
        if (discordEnabled) add(ForwardingChannels.DISCORD)
        if (tencentCloudEnabled) add(ForwardingChannels.TENCENT_CLOUD)
        if (emailEnabled) add(ForwardingChannels.EMAIL)
        if (smsDirectEnabled) add(ForwardingChannels.SMS_DIRECT)
        if (customWebhookEnabled) add(ForwardingChannels.CUSTOM_WEBHOOK)
        if (channelGroupEnabled) add(ForwardingChannels.CHANNEL_GROUP)
        if (gotifyEnabled) add(ForwardingChannels.GOTIFY)
    }

    private fun booleanPreference(key: String) = object : kotlin.properties.ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>) =
            prefs.getBoolean(key, false)

        override fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: Boolean) {
            prefs.edit().putBoolean(key, value).apply()
        }
    }

    /**
     * 写入加密凭据。
     *
     * P0-3 止血：加密失败（Keystore 不可用）时**不写盘、保留旧值**。
     * 历史实现会把 `encrypt()` 失败得到的空串直接 `putString`，等于静默清空用户凭据。
     *
     * @return 是否成功落盘；false 表示本次未写入，旧值保留。
     */
    private fun saveSecret(key: String, value: String): Boolean {
        if (value.isBlank()) {
            prefs.edit().remove(key).apply()
            CredentialHealth.clear(key)
            return true
        }
        val encrypted = cipher.encrypt(value.trim())
        if (encrypted.isBlank()) {
            CredentialHealth.markEncryptFailed(key)
            SecurityAuditManager.logEvent(
                SecurityAuditEventType.SECRET_ENCRYPT_FAILED,
                key,
                "keystore unavailable; kept previous value instead of overwriting"
            )
            return false
        }
        prefs.edit().putString(key, encrypted).apply()
        CredentialHealth.clear(key)
        return true
    }

    /**
     * 读取并解密凭据。
     *
     * P0-3 止血：解密失败时**返回空串，绝不返回 stored（密文）**。
     * 历史实现 `if (decrypted.isNotEmpty()) decrypted else stored` 会把密文当明文返回，
     * 上层据此误判"已配置"，进而用密文覆写真实凭据或重建联动通道（路径 B）。
     */
    private fun getSecret(key: String): String {
        val stored = prefs.getString(key, null) ?: return ""
        if (stored.isBlank()) return ""
        val decrypted = cipher.decrypt(stored)
        if (decrypted.isNotEmpty()) {
            // 解密成功但存量值没有版本前缀 ⇒ 升级前写入的老密文。
            // 顺手重写为带前缀的新格式，之后该值不再依赖长度启发式判定。
            // 前缀本身就是幂等标记：重写成功后 stored 已带前缀，不会再触发第二次；
            // 这里额外用一次性的迁移记录，避免加密反复失败时每次读取都去打一次 KeyStore。
            if (!stored.startsWith(CREDENTIAL_CIPHER_PREFIX) && legacyCipherMigrated.add(key)) {
                SecurityAuditManager.logEvent(
                    SecurityAuditEventType.SECRET_UPDATED,
                    key,
                    "legacy ciphertext without version prefix; re-encrypted in current format"
                )
                saveSecret(key, decrypted)
            }
            return decrypted
        }

        // 解不开有两种原因，必须分开处理：
        // 1) 不是合法密文形态 ⇒ 老版本遗留的明文值，按明文返回以保证老用户仍能读出配置，
        //    并顺手重新加密写回完成迁移（写回失败也只是维持原状，不会丢数据）；
        // 2) 是合法密文形态但解不开 ⇒ 密钥丢失，绝不能把密文当明文外泄。
        if (!cipher.looksLikeCiphertext(stored)) {
            // 只迁移一次：若上一次回写已因加密失败被记录，本次不再重复打 KeyStore，
            // 但仍按明文返回，保证老用户在 Keystore 故障期依然能读出配置。
            if (key !in CredentialHealth.failedKeys()) {
                SecurityAuditManager.logEvent(
                    SecurityAuditEventType.SECRET_UPDATED,
                    key,
                    "legacy plaintext credential detected; re-encrypting in place"
                )
                saveSecret(key, stored)
            }
            return stored
        }

        CredentialHealth.markDecryptFailed(key)
        SecurityAuditManager.logEvent(
            SecurityAuditEventType.SECRET_DECRYPT_FAILED,
            key,
            "keystore unavailable or key lost; refusing to expose ciphertext as plaintext"
        )
        return ""
    }

    companion object {
        private const val PREFS_NAME = "multi_channel_forwarding"

        /**
         * 已尝试过"老密文 → 带前缀新格式"迁移的 key。
         *
         * 必须独立于 [CredentialHealth.failedKeys]：后者表示"加解密失败"，
         * 前者表示"已尝试过迁移"。两者是不同的生命周期事件——一次加密失败后
         * KeyStore 恢复时仍应获得一次迁移机会，共用一个集合会互相污染。
         */
        private val legacyCipherMigrated =
            java.util.Collections.synchronizedSet(mutableSetOf<String>())

        private const val KEY_PUSHPLUS_ENABLED = "pushplus_enabled"
        private const val KEY_PUSHPLUS_TOKEN = "pushplus_token"
        private const val KEY_PUSHPLUS_TOPIC = "pushplus_topic"
        private const val KEY_WECHAT_TEST_ENABLED = "wechat_test_enabled"
        private const val KEY_WECHAT_TEST_APP_ID = "wechat_test_app_id"
        private const val KEY_WECHAT_TEST_APP_SECRET = "wechat_test_app_secret"
        private const val KEY_WECHAT_TEST_TEMPLATE_ID = "wechat_test_template_id"
        private const val KEY_WECHAT_TEST_OPEN_ID = "wechat_test_open_id"
        private const val KEY_QQ_ENABLED = "qq_enabled"
        private const val KEY_QQ_WEBHOOK = "qq_webhook"
        private const val KEY_QQ_TYPE = "qq_type"
        private const val KEY_WECOM_ENABLED = "wecom_enabled"
        private const val KEY_WECOM_CORP_ID = "wecom_corp_id"
        private const val KEY_WECOM_AGENT_ID = "wecom_agent_id"
        private const val KEY_WECOM_SECRET = "wecom_secret"
        private const val KEY_WECOM_TO_USER = "wecom_to_user"
        private const val KEY_WECOM_BOT_ENABLED = "wecom_bot_enabled"
        private const val KEY_WECOM_BOT_WEBHOOK = "wecom_bot_webhook"
        private const val KEY_FEISHU_APP_ENABLED = "feishu_app_enabled"
        private const val KEY_FEISHU_APP_ID = "feishu_app_id"
        private const val KEY_FEISHU_APP_SECRET = "feishu_app_secret"
        private const val KEY_FEISHU_RECEIVE_ID = "feishu_receive_id"
        private const val KEY_FEISHU_ENABLED = "feishu_enabled"
        private const val KEY_FEISHU_WEBHOOK = "feishu_webhook"
        private const val KEY_FEISHU_SECRET = "feishu_secret"
        private const val KEY_DINGTALK_ENABLED = "dingtalk_enabled"
        private const val KEY_DINGTALK_WEBHOOK = "dingtalk_webhook"
        private const val KEY_DINGTALK_SECRET = "dingtalk_secret"
        private const val KEY_BARK_ENABLED = "bark_enabled"
        private const val KEY_BARK_SERVER_URL = "bark_server_url"
        private const val KEY_BARK_DEVICE_KEY = "bark_device_key"
        private const val KEY_BARK_ALLOW_HTTP = "bark_allow_http"
        private const val KEY_WEBSOCKET_ENABLED = "websocket_enabled"
        private const val KEY_WEBSOCKET_URL = "websocket_url"
        private const val KEY_WEBSOCKET_TOKEN = "websocket_token"
        private const val KEY_TELEGRAM_ENABLED = "telegram_enabled"
        private const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        private const val KEY_TELEGRAM_CHAT_ID = "telegram_chat_id"
        private const val KEY_DISCORD_ENABLED = "discord_enabled"
        private const val KEY_DISCORD_WEBHOOK = "discord_webhook"
        private const val KEY_TENCENT_CLOUD_ENABLED = "tencent_cloud_enabled"
        private const val KEY_TENCENT_CLOUD_WEBHOOK = "tencent_cloud_webhook"
        private const val KEY_TENCENT_CLOUD_SECRET = "tencent_cloud_secret"
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
        private const val KEY_CUSTOM_WEBHOOK_ENABLED = "custom_webhook_enabled"
        private const val KEY_CUSTOM_WEBHOOK_URL = "custom_webhook_url"
        private const val KEY_CUSTOM_WEBHOOK_HEADERS = "custom_webhook_headers"
        private const val KEY_CUSTOM_WEBHOOK_METHOD = "custom_webhook_method"
        private const val KEY_CUSTOM_WEBHOOK_CONTENT_TYPE = "custom_webhook_content_type"
        private const val KEY_CUSTOM_WEBHOOK_BODY = "custom_webhook_body"
        // 保持旧版固定 {"content": ...} 请求体兼容；用户可在界面扩展更多字段。
        const val DEFAULT_CUSTOM_WEBHOOK_BODY = "{\"content\":\"[msg]\"}"
        private const val KEY_CHANNEL_GROUP_ENABLED = "channel_group_enabled"
        private const val KEY_CHANNEL_GROUP_MEMBERS = "channel_group_members"
        private const val KEY_GOTIFY_ENABLED = "gotify_enabled"
        private const val KEY_GOTIFY_SERVER_URL = "gotify_server_url"
        private const val KEY_GOTIFY_TOKEN = "gotify_token"
        private const val KEY_GOTIFY_ALLOW_HTTP = "gotify_allow_http"
        private const val KEY_DINGTALK_REMOTE_CONTROL_ENABLED = "dingtalk_remote_control_enabled"
        private const val KEY_DINGTALK_REMOTE_CLIENT_ID = "dingtalk_remote_client_id"
        private const val KEY_DINGTALK_REMOTE_CLIENT_SECRET = "dingtalk_remote_client_secret"
        private const val KEY_DINGTALK_REMOTE_CUSTOM_PREFIX = "dingtalk_remote_custom_prefix"
        private const val KEY_DINGTALK_REMOTE_SEND_SIM = "dingtalk_remote_send_sim"
        private const val KEY_DINGTALK_REMOTE_STATUS = "dingtalk_remote_status"
        private const val KEY_DINGTALK_REMOTE_LOGS = "dingtalk_remote_logs"

        private const val KEY_FEISHU_REMOTE_CONTROL_ENABLED = "feishu_remote_control_enabled"
        private const val KEY_FEISHU_REMOTE_APP_ID = "feishu_remote_app_id"
        private const val KEY_FEISHU_REMOTE_APP_SECRET = "feishu_remote_app_secret"
        private const val KEY_FEISHU_REMOTE_CUSTOM_PREFIX = "feishu_remote_custom_prefix"
        private const val KEY_FEISHU_REMOTE_SEND_SIM = "feishu_remote_send_sim"
        private const val KEY_FEISHU_REMOTE_STATUS = "feishu_remote_status"
        private const val KEY_FEISHU_REMOTE_LOGS = "feishu_remote_logs"

        private const val KEY_EMAIL_REMOTE_CONTROL_ENABLED = "email_remote_control_enabled"
        private const val KEY_EMAIL_REMOTE_HOST = "email_remote_host"
        private const val KEY_EMAIL_REMOTE_PORT = "email_remote_port"
        private const val KEY_EMAIL_REMOTE_USER = "email_remote_user"
        private const val KEY_EMAIL_REMOTE_PASSWORD = "email_remote_password"
        private const val KEY_EMAIL_REMOTE_AUTH_SENDERS = "email_remote_auth_senders"
        private const val KEY_EMAIL_REMOTE_SECURITY = "email_remote_security"
        private const val KEY_EMAIL_REMOTE_CUSTOM_PREFIX = "email_remote_custom_prefix"
        private const val KEY_EMAIL_REMOTE_SEND_SIM = "email_remote_send_sim"
        private const val KEY_EMAIL_REMOTE_STATUS = "email_remote_status"
        private const val KEY_EMAIL_REMOTE_LOGS = "email_remote_logs"

        private const val KEY_TELEGRAM_REMOTE_CONTROL_ENABLED = "telegram_remote_control_enabled"
        private const val KEY_TELEGRAM_REMOTE_BOT_TOKEN = "telegram_remote_bot_token"
        private const val KEY_TELEGRAM_REMOTE_CHAT_ID = "telegram_remote_chat_id"
        private const val KEY_TELEGRAM_REMOTE_CUSTOM_HOST = "telegram_remote_custom_host"
        private const val KEY_TELEGRAM_REMOTE_AUTH_USERS = "telegram_remote_auth_users"
        private const val KEY_TELEGRAM_REMOTE_CUSTOM_PREFIX = "telegram_remote_custom_prefix"
        private const val KEY_TELEGRAM_REMOTE_SEND_SIM = "telegram_remote_send_sim"
        private const val KEY_TELEGRAM_REMOTE_STATUS = "telegram_remote_status"
        private const val KEY_TELEGRAM_REMOTE_LOGS = "telegram_remote_logs"

        private const val KEY_WEBSOCKET_REMOTE_CONTROL_ENABLED = "websocket_remote_control_enabled"
        private const val KEY_WEBSOCKET_REMOTE_URL = "websocket_remote_url"
        private const val KEY_WEBSOCKET_REMOTE_TOKEN = "websocket_remote_token"
        private const val KEY_WEBSOCKET_REMOTE_CUSTOM_PREFIX = "websocket_remote_custom_prefix"
        private const val KEY_WEBSOCKET_REMOTE_SEND_SIM = "websocket_remote_send_sim"
        private const val KEY_WEBSOCKET_REMOTE_STATUS = "websocket_remote_status"
        private const val KEY_WEBSOCKET_REMOTE_LOGS = "websocket_remote_logs"

        private const val KEY_WECOM_REMOTE_CONTROL_ENABLED = "wecom_remote_control_enabled"
        private const val KEY_WECOM_REMOTE_BOT_ID = "wecom_remote_bot_id"
        private const val KEY_WECOM_REMOTE_SECRET = "wecom_remote_secret"
        private const val KEY_WECOM_REMOTE_CHAT_ID = "wecom_remote_chat_id"
        private const val KEY_WECOM_REMOTE_CUSTOM_PREFIX = "wecom_remote_custom_prefix"
        private const val KEY_WECOM_REMOTE_SEND_SIM = "wecom_remote_send_sim"
        private const val KEY_WECOM_REMOTE_STATUS = "wecom_remote_status"
        private const val KEY_WECOM_REMOTE_LOGS = "wecom_remote_logs"
        private const val KEY_LAST_CAPTURED_WECOM_CHAT_ID = "last_captured_wecom_chat_id"

        private const val KEY_LAST_STATUS = "last_status"
        private const val KEY_SIM_ONE_LABEL = "sim_one_label"
        private const val KEY_SIM_TWO_LABEL = "sim_two_label"
        private const val KEY_SIM_ONE_NUMBER = "sim_one_number"
        private const val KEY_SIM_TWO_NUMBER = "sim_two_number"
        private const val KEY_TEMPLATE_MODE = "template_mode"
        private const val KEY_CUSTOM_TEMPLATE = "custom_template"
        private const val KEY_ACCEPTED_DISCLAIMER_VERSION = "accepted_disclaimer_version"
        private const val KEY_CHANNEL_INSTANCES = "channel_instances"
        private const val KEY_ENABLE_PRIVACY_MASK = "enable_privacy_mask"
        private const val KEY_MASK_VERIFICATION_CODE = "mask_verification_code"
        private const val KEY_MARK_AS_READ_AFTER_FORWARD = "mark_as_read_after_forward"
        private const val KEY_FORWARDING_DELAY_SECONDS = "forwarding_delay_seconds"
        private const val KEY_KEEP_ALIVE_SERVICE_ENABLED = "keep_alive_service_enabled"

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

/** SIM send modes used in Remote Settings UI and command dispatch. */
object SimSendMode {
    const val DEFAULT = 0
    const val SIM1 = 1
    const val SIM2 = 2
}

/**
 * 凭据密文的版本前缀。
 *
 * 存在的理由：纯"Base64 可解码 + 长度"启发式无法区分**老版本遗留明文**与**真正密文**。
 * 32 位十六进制 PushPlus token、22 位 Bark deviceKey、16 位 Gotify token 都满足该条件，
 * 会被误判成密文；一旦 KeyStore 不可用就会被当成"密钥丢失"返回空串，
 * 表现为"升级后某个通道静默消失"。
 *
 * 加密输出统一带上前缀后，判定退化为一次 startsWith，彻底消除启发式误伤。
 * 存量无前缀的老密文仍由 [AndroidKeystoreCipher.decrypt] 兼容读取。
 */
internal const val CREDENTIAL_CIPHER_PREFIX = "v1:"

/**
 * 凭据加解密抽象
 *
 * 存在的意义是让凭据读写路径可测：单元测试环境（JVM）没有 AndroidKeyStore，
 * [AndroidKeystoreCipher] 会直接抛 `KeyStoreException`，导致所有凭据读写返回空串。
 * 通过注入测试源集里的直通实现（见 `app/src/test/.../forwarding/PlaintextCipher.kt`）
 * 即可在 JVM 上覆盖凭据相关逻辑。
 */
interface CredentialCipher {
    /** 返回加密后的字符串；失败时返回空串（调用方据此判定失败，绝不落盘空串）。 */
    fun encrypt(value: String): String

    /** 返回解密后的明文；失败时返回空串（调用方据此判定失败，绝不把密文当明文）。 */
    fun decrypt(value: String): String

    /**
     * 判断 [value] 是否为本实现产出的合法密文形态。
     *
     * 用于区分"解不开"的两种原因：
     * - 不是合法密文形态 ⇒ 遗留明文（老版本未加密落盘的值），应作为明文返回并完成迁移；
     * - 是合法密文形态但解不开 ⇒ 密钥丢失，绝不能外泄密文。
     *
     * 判定方向必须保守：宁可把明文误判成密文（损失一次配置读取），
     * 也绝不把密文误判成明文（会导致明文落盘）。
     */
    fun looksLikeCiphertext(value: String): Boolean
}

/**
 * 基于 AndroidKeyStore AES-GCM 硬件加密的安全凭证加解密工具（生产实现）
 */
internal object AndroidKeystoreCipher : CredentialCipher {
    private const val KEY_ALIAS = "multi_forwarding_credentials_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_IV_LENGTH = 12
    /** AES-GCM 认证标签长度（字节）。GCM 必然输出 Tag，因此它是识别密文的最短依据。 */
    private const val GCM_TAG_LENGTH = 16
    /**
     * 本实现产出的密文最短长度 = IV(12 字节) + Tag(16 字节) = 28 字节。
     * 只有升级前写入的老密文没有版本前缀，只能靠这条结构约束识别；
     * 28 字节这条线同时把 32 位十六进制 PushPlus token(24B)、22 位 Bark key(16B)、
     * 16 位 Gotify token(12B) 等遗留明文挡在"密文"之外。
     */
    private const val GCM_MIN_TOTAL_LENGTH = GCM_IV_LENGTH + GCM_TAG_LENGTH

    override fun encrypt(value: String): String = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        CREDENTIAL_CIPHER_PREFIX + Base64.getEncoder().encodeToString(cipher.iv + encrypted)
    }.getOrDefault("")

    override fun decrypt(value: String): String = runCatching {
        // 同时兼容两种存量格式：带版本前缀的新密文，以及升级前写入的无前缀老密文。
        // 老密文必须继续可读，否则老用户升级后配置会永久丢失。
        val bytes = decodeCipherBytes(value.removePrefix(CREDENTIAL_CIPHER_PREFIX))
            ?: return@runCatching ""
        val iv = bytes.copyOfRange(0, GCM_IV_LENGTH)
        val encrypted = bytes.copyOfRange(GCM_IV_LENGTH, bytes.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
        String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
    }.getOrDefault("")

    /**
     * 只有显式带 [CREDENTIAL_CIPHER_PREFIX] (v1:) 前缀的才是合法密文格式。
     * 无前缀的一律视为老版本明文，彻底消除任何 Base64 长度启发式对钉钉(SEC)、企业微信(43位)等明文的误判清空！
     */
    override fun looksLikeCiphertext(value: String): Boolean {
        return value.startsWith(CREDENTIAL_CIPHER_PREFIX)
    }

    /**
     * 判定规则与 [decrypt] 完全一致，不另立规则，保证两处永不漂移。
     */
    private fun decodeCipherBytes(value: String): ByteArray? = runCatching {
        Base64.getDecoder().decode(value)
    }.getOrNull()?.takeIf { it.size >= GCM_MIN_TOTAL_LENGTH }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // 不在读取异常时删除旧密钥；删除会令所有既有密文永久不可恢复。
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

/**
 * 直通实现（PlaintextCipher）已移至单元测试源集 `app/src/test/.../forwarding/PlaintextCipher.kt`。
 * 生产代码里不应存在"不加密"的实现，哪怕只是个对象声明。
 */
