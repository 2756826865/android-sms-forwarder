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

class MultiForwardConfig(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 15 大通道开关
    var pushPlusEnabled: Boolean
        get() = prefs.getBoolean(KEY_PUSHPLUS_ENABLED, false) || context.getSharedPreferences("pushplus_forwarding", Context.MODE_PRIVATE).getBoolean("enabled", false)
        set(value) {
            prefs.edit().putBoolean(KEY_PUSHPLUS_ENABLED, value).apply()
            context.getSharedPreferences("pushplus_forwarding", Context.MODE_PRIVATE).edit().putBoolean("enabled", value).apply()
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

    // 企业微信应用远程控制
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

    // QQ (OneBot 11) 远程控制
    var qqRemoteControlEnabled by booleanPreference(KEY_QQ_REMOTE_CONTROL_ENABLED)
    var qqRemoteSendSimMode: Int
        get() = prefs.getInt(KEY_QQ_REMOTE_SEND_SIM, SimSendMode.DEFAULT).let { mode ->
            when (mode) {
                SimSendMode.SIM1, SimSendMode.SIM2, SimSendMode.DEFAULT -> mode
                else -> SimSendMode.DEFAULT
            }
        }
        set(value) = prefs.edit().putInt(
            KEY_QQ_REMOTE_SEND_SIM,
            when (value) {
                SimSendMode.SIM1, SimSendMode.SIM2, SimSendMode.DEFAULT -> value
                else -> SimSendMode.DEFAULT
            },
        ).apply()

    var qqRemoteConnectionStatus: String
        get() = prefs.getString(KEY_QQ_REMOTE_STATUS, "").orEmpty()
        set(value) = prefs.edit().putString(KEY_QQ_REMOTE_STATUS, value).apply()

    var qqRemoteRequireAt: Boolean
        get() = prefs.getBoolean(KEY_QQ_REMOTE_REQUIRE_AT, true)
        set(value) = prefs.edit().putBoolean(KEY_QQ_REMOTE_REQUIRE_AT, value).apply()

    fun appendQqRemoteLog(message: String) {
        val now = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date())
        val line = "$now $message"
        val current = prefs.getString(KEY_QQ_REMOTE_LOGS, "").orEmpty().lines().filter(String::isNotBlank)
        val logs = (listOf(line) + current).take(30).joinToString("\n")
        prefs.edit().putString(KEY_QQ_REMOTE_LOGS, logs).putString(KEY_QQ_REMOTE_STATUS, line).apply()
    }

    fun qqRemoteLogs(): String = prefs.getString(KEY_QQ_REMOTE_LOGS, "").orEmpty()

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

    fun saveWeComRemoteControl(
        corpId: String,
        agentId: String,
        secret: String,
        authorizedUsers: String = "",
        customPrefix: String = "",
    ) {
        saveSecret(KEY_WECOM_REMOTE_CORP_ID, corpId)
        saveSecret(KEY_WECOM_REMOTE_AGENT_ID, agentId)
        saveSecret(KEY_WECOM_REMOTE_SECRET, secret)
        prefs.edit()
            .putString(KEY_WECOM_REMOTE_AUTH_USERS, authorizedUsers.trim())
            .putString(KEY_WECOM_REMOTE_CUSTOM_PREFIX, customPrefix.trim())
            .apply()
    }
    fun weComRemoteCorpId() = getSecret(KEY_WECOM_REMOTE_CORP_ID)
    fun weComRemoteAgentId() = getSecret(KEY_WECOM_REMOTE_AGENT_ID)
    fun weComRemoteSecret() = getSecret(KEY_WECOM_REMOTE_SECRET)
    fun weComRemoteAuthorizedUsers() = prefs.getString(KEY_WECOM_REMOTE_AUTH_USERS, "").orEmpty()
    fun weComRemoteCustomPrefix() = prefs.getString(KEY_WECOM_REMOTE_CUSTOM_PREFIX, "").orEmpty()

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

    fun saveQqRemoteControl(
        wsUrl: String,
        token: String = "",
        authUsers: String = "",
        authGroups: String = "",
        requireAt: Boolean = true,
        customPrefix: String = "",
    ) {
        saveSecret(KEY_QQ_REMOTE_WS_URL, wsUrl)
        saveSecret(KEY_QQ_REMOTE_TOKEN, token)
        prefs.edit()
            .putString(KEY_QQ_REMOTE_AUTH_USERS, authUsers.trim())
            .putString(KEY_QQ_REMOTE_AUTH_GROUPS, authGroups.trim())
            .putBoolean(KEY_QQ_REMOTE_REQUIRE_AT, requireAt)
            .putString(KEY_QQ_REMOTE_CUSTOM_PREFIX, customPrefix.trim())
            .apply()
    }
    fun qqRemoteWsUrl() = getSecret(KEY_QQ_REMOTE_WS_URL)
    fun qqRemoteToken() = getSecret(KEY_QQ_REMOTE_TOKEN)
    fun qqRemoteAuthorizedUsers() = prefs.getString(KEY_QQ_REMOTE_AUTH_USERS, "").orEmpty()
    fun qqRemoteAuthorizedGroups() = prefs.getString(KEY_QQ_REMOTE_AUTH_GROUPS, "").orEmpty()
    fun qqRemoteCustomPrefix() = prefs.getString(KEY_QQ_REMOTE_CUSTOM_PREFIX, "").orEmpty()

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
    fun saveCustomWebhook(url: String, headers: String = "") {
        saveSecret(KEY_CUSTOM_WEBHOOK_URL, url)
        saveSecret(KEY_CUSTOM_WEBHOOK_HEADERS, headers)
    }
    fun customWebhookUrl() = getSecret(KEY_CUSTOM_WEBHOOK_URL)
    fun customWebhookHeaders() = getSecret(KEY_CUSTOM_WEBHOOK_HEADERS)

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

    fun anyEnabled() = pushPlusEnabled || wechatTestEnabled || qqEnabled || weComEnabled || weComBotEnabled ||
        feishuAppEnabled || feishuEnabled || dingTalkEnabled || barkEnabled || websocketEnabled ||
        telegramEnabled || discordEnabled || tencentCloudEnabled || emailEnabled || smsDirectEnabled ||
        customWebhookEnabled || channelGroupEnabled || gotifyEnabled

    fun isChannelEnabled(channel: String): Boolean = when (channel) {
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

        private const val KEY_WECOM_REMOTE_CONTROL_ENABLED = "wecom_remote_control_enabled"
        private const val KEY_WECOM_REMOTE_CORP_ID = "wecom_remote_corp_id"
        private const val KEY_WECOM_REMOTE_AGENT_ID = "wecom_remote_agent_id"
        private const val KEY_WECOM_REMOTE_SECRET = "wecom_remote_secret"
        private const val KEY_WECOM_REMOTE_AUTH_USERS = "wecom_remote_auth_users"
        private const val KEY_WECOM_REMOTE_CUSTOM_PREFIX = "wecom_remote_custom_prefix"
        private const val KEY_WECOM_REMOTE_SEND_SIM = "wecom_remote_send_sim"
        private const val KEY_WECOM_REMOTE_STATUS = "wecom_remote_status"
        private const val KEY_WECOM_REMOTE_LOGS = "wecom_remote_logs"

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

        private const val KEY_QQ_REMOTE_CONTROL_ENABLED = "qq_remote_control_enabled"
        private const val KEY_QQ_REMOTE_WS_URL = "qq_remote_ws_url"
        private const val KEY_QQ_REMOTE_TOKEN = "qq_remote_token"
        private const val KEY_QQ_REMOTE_AUTH_USERS = "qq_remote_auth_users"
        private const val KEY_QQ_REMOTE_AUTH_GROUPS = "qq_remote_auth_groups"
        private const val KEY_QQ_REMOTE_REQUIRE_AT = "qq_remote_require_at"
        private const val KEY_QQ_REMOTE_CUSTOM_PREFIX = "qq_remote_custom_prefix"
        private const val KEY_QQ_REMOTE_SEND_SIM = "qq_remote_send_sim"
        private const val KEY_QQ_REMOTE_STATUS = "qq_remote_status"
        private const val KEY_QQ_REMOTE_LOGS = "qq_remote_logs"
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

/** SIM send modes used in Remote Settings UI and command dispatch. */
object SimSendMode {
    const val DEFAULT = 0
    const val SIM1 = 1
    const val SIM2 = 2
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
