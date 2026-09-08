package org.fossify.messages.forwarding

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.fossify.messages.messaging.sendMessageCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.fossify.messages.helpers.ShadowRepository
import org.fossify.messages.models.ForwardingShadowAttempt

class MultiChannelForwardWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo() = ForwardingForegroundInfo.create(applicationContext)

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        setForeground(getForegroundInfo())
        val config = MultiForwardConfig(applicationContext)
        val targetChannel = inputData.getString(KEY_TARGET_CHANNEL).orEmpty()
        val isTest = inputData.getBoolean(KEY_IS_TEST, false)
        val history = ForwardingHistoryStore(applicationContext)
        val historyRecordId = inputData.getString(KEY_HISTORY_RECORD_ID).orEmpty()
        val sender = inputData.getString(KEY_SENDER).orEmpty()
        val body = inputData.getString(KEY_BODY).orEmpty()
        val receivedAt = inputData.getLong(KEY_RECEIVED_AT, System.currentTimeMillis())
        val subscriptionId = inputData.getInt(KEY_SUBSCRIPTION_ID, -1)
        val operationId = inputData.getString(KEY_OPERATION_ID)

        // Shadow observation: Worker started
        operationId?.let { ShadowRepository.recordStep(applicationContext, it, "MULTICHANNEL_WORKER", "STARTED", "target=$targetChannel") }

        if (historyRecordId.isNotBlank()) history.markRunning(historyRecordId)
        if (targetChannel.isBlank() && !config.anyEnabled()) {
            val workId = inputData.getString(KEY_HISTORY_RECORD_ID).orEmpty().ifBlank { "empty-${System.currentTimeMillis()}" }
            val historyId = history.registerQueued(
                workId = workId,
                channel = "system",
                sender = sender,
                body = body,
                receivedAt = receivedAt,
                subscriptionId = subscriptionId,
                isTest = isTest,
            )
            history.markSkipped(historyId, "所有转发通道已关闭，无需发送")
            Log.d(TAG, "all channels disabled, skip")
            return@withContext Result.success()
        }
        Log.d(TAG, "doWork start: target=$targetChannel, test=$isTest, historyId=$historyRecordId")

        val ruleAllowedChannels = decodeRuleAllowedChannels(
            inputData.getString(KEY_ALLOWED_CHANNELS).orEmpty(),
        )
        val enabledInstanceTypes = config.channelInstances()
            .asSequence()
            .filter { it.enabled && it.hasDispatchConfiguration() }
            .map { it.channelType }
            .toSet()
        fun shouldRun(channel: String, enabled: Boolean): Boolean {
            if (!enabled && !(isTest && targetChannel == channel)) return false
            // 自动迁移后旧版扁平配置仍会保留；同类型已有启用实例时只发送实例，避免双发。
            if (channel in enabledInstanceTypes) return false
            if (ruleAllowedChannels != null && channel !in ruleAllowedChannels) return false
            return when (targetChannel) {
                "" -> true
                ForwardingChannels.ALL -> true
                else -> targetChannel == channel
            }
        }

        val formattedPayload = ForwardingMessageFormatter.format(
            context = applicationContext,
            sender = sender,
            body = body,
            receivedAt = receivedAt,
            subscriptionId = subscriptionId,
        )
        val payload = if (inputData.getBoolean(KEY_BODY_ALREADY_RENDERED, false)) {
            formattedPayload.copy(content = body)
        } else formattedPayload
        val title = payload.title
        val content = payload.content

        val targetInstanceId = inputData.getString(KEY_INSTANCE_ID).orEmpty()
        val ruleId = inputData.getString(KEY_RULE_ID).orEmpty()
        val actionId = inputData.getString(KEY_ACTION_ID).orEmpty()

        val successes = mutableListOf<String>()
        val failures = mutableListOf<String>()

        suspend fun runChannel(name: String, channelKey: String, action: suspend () -> Unit) {
            operationId?.let { ShadowRepository.recordStep(applicationContext, it, "CHANNEL_REQUEST", "STARTED", name) }
            runCatching { action() }
                .onSuccess {
                    successes += name
                    operationId?.let { 
                        ShadowRepository.recordStep(applicationContext, it, "CHANNEL_REQUEST", "SUCCESS", name)
                        ShadowRepository.recordAttempt(applicationContext, it, channelKey, 
                            ForwardingShadowAttempt(
                                attemptNumber = runAttemptCount + 1,
                                state = "SUCCESS",
                                httpStatus = 200
                            )
                        )
                    }
                    Log.d(TAG, "channel success: $name")
                }
                .onFailure {
                    val detail = it.message ?: it.javaClass.simpleName
                    failures += "$name：$detail"
                    operationId?.let { 
                        ShadowRepository.recordStep(applicationContext, it, "CHANNEL_REQUEST", "FAILED", "$name: $detail")
                        ShadowRepository.recordAttempt(applicationContext, it, channelKey, 
                            ForwardingShadowAttempt(
                                attemptNumber = runAttemptCount + 1,
                                state = "FAILED",
                                errorClass = it.javaClass.simpleName
                            )
                        )
                    }
                    Log.d(TAG, "channel failed: $name -> $detail")
                }
        }

        if (targetInstanceId.isNotBlank()) {
            val instance = config.channelInstances().firstOrNull { it.id == targetInstanceId }
            if (instance == null) {
                history.markSkipped(historyRecordId, "目标实例不存在 (ID: $targetInstanceId)")
                return@withContext Result.success()
            }
            if (!instance.enabled) {
                history.markSkipped(historyRecordId, "目标通道实例已停用 (${instance.name})")
                return@withContext Result.success()
            }
            runChannel(instance.name, "instance_${instance.id}") {
                when (instance.channelType) {
                    ForwardingChannels.WECHAT_TEST -> {
                        val appId = instance.optString("appId")
                        val appSecret = instance.optString("appSecret")
                        val templateId = instance.optString("templateId")
                        val openId = instance.optString("openId")
                        check(appId.isNotBlank() && appSecret.isNotBlank() && templateId.isNotBlank() && openId.isNotBlank()) {
                            "微信测试号配置不完整"
                        }
                        sendWechatTest(appId, appSecret, templateId, openId, title, content)
                    }
                    ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> {
                        val corpId = instance.optString("corpId")
                        val agentId = instance.optString("agentId")
                        val secret = instance.optString("secret")
                        val toUser = instance.optString("toUser")
                        check(corpId.isNotBlank() && agentId.isNotBlank() && secret.isNotBlank() && toUser.isNotBlank()) {
                            "企业微信应用号配置不完整"
                        }
                        sendWeCom(corpId, agentId, secret, toUser, content)
                    }
                    ForwardingChannels.WECOM_BOT -> {
                        val webhook = instance.optString("webhook")
                        check(webhook.isNotBlank()) { "企微群机器人 Webhook 未配置" }
                        sendWeComBot(webhook, content)
                    }
                    ForwardingChannels.DINGTALK -> {
                        val webhook = instance.optString("webhook")
                        val secret = instance.optString("secret")
                        check(webhook.isNotBlank()) { "钉钉群机器人 Webhook 未配置" }
                        sendDingTalk(webhook, secret, content)
                    }
                    ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT -> {
                        val webhook = instance.optString("webhook")
                        val secret = instance.optString("secret")
                        check(webhook.isNotBlank()) { "飞书群机器人 Webhook 未配置" }
                        sendFeishu(webhook, secret, content)
                    }
                    ForwardingChannels.FEISHU_APP -> {
                        val appId = instance.optString("appId")
                        val appSecret = instance.optString("appSecret")
                        val receiveId = instance.optString("receiveId")
                        check(appId.isNotBlank() && appSecret.isNotBlank() && receiveId.isNotBlank()) {
                            "飞书自建应用配置不完整"
                        }
                        sendFeishuApp(appId, appSecret, receiveId, title, content)
                    }
                    ForwardingChannels.TELEGRAM -> {
                        val token = instance.optString("botToken")
                        val chatId = instance.optString("chatId")
                        check(token.isNotBlank() && chatId.isNotBlank()) { "Telegram 凭据未配置" }
                        sendTelegram(token, chatId, title, content)
                    }
                    ForwardingChannels.BARK -> {
                        val url = instance.optString("serverUrl")
                        val key = instance.optString("deviceKey")
                        check(url.isNotBlank() && key.isNotBlank()) { "Bark URL 或 DeviceKey 未配置" }
                        sendBark(url, key, title, content, true)
                    }
                    ForwardingChannels.CUSTOM_WEBHOOK -> {
                        val url = instance.optString("url")
                        val headers = instance.optString("headers")
                        check(url.isNotBlank()) { "自定义 Webhook URL 未配置" }
                        sendCustomWebhook(
                            url, headers, instance.optString("method", "POST"),
                            instance.optString("contentType", "application/json"),
                            instance.optString("bodyTemplate", MultiForwardConfig.DEFAULT_CUSTOM_WEBHOOK_BODY),
                            title, content, sender, receivedAt, subscriptionId
                        )
                    }
                    ForwardingChannels.DISCORD -> {
                        val webhook = instance.optString("webhook")
                        check(webhook.isNotBlank()) { "Discord Webhook 未配置" }
                        sendDiscord(webhook, title, content)
                    }
                    ForwardingChannels.TENCENT_CLOUD -> {
                        val webhook = instance.optString("webhook")
                        check(webhook.isNotBlank()) { "腾讯云告警 Webhook 未配置" }
                        sendTencentCloud(webhook, instance.optString("secret"), content)
                    }
                    ForwardingChannels.WEBSOCKET -> {
                        val serverUrl = instance.optString("serverUrl")
                        check(serverUrl.isNotBlank()) { "WebSocket 推送地址未配置" }
                        sendWebsocket(serverUrl, instance.optString("token"), title, content)
                    }
                    ForwardingChannels.PUSHPLUS -> {
                        val token = instance.optString("token")
                        val topic = instance.optString("topic")
                        check(token.isNotBlank()) { "PushPlus Token 未配置" }
                        sendPushPlus(token, topic, title, content)
                    }
                    ForwardingChannels.GOTIFY -> {
                        val url = instance.optString("serverUrl")
                        val token = instance.optString("token")
                        check(url.isNotBlank() && token.isNotBlank()) { "Gotify URL 或 Token 未配置" }
                        sendGotify(url, token, title, content, true)
                    }
                    ForwardingChannels.NTFY -> {
                        sendNtfy(
                            instance.optString("serverUrl").ifBlank { "https://ntfy.sh" },
                            instance.optString("topic"),
                            instance.optString("token"),
                            instance.optString("priority").ifBlank { "default" },
                            instance.optString("tags"),
                            instance.optString("clickUrl"),
                            title,
                            content
                        )
                    }
                    ForwardingChannels.EMAIL -> {
                        val host = instance.optString("host")
                        val port = instance.optInt("port", 465)
                        val user = instance.optString("user")
                        val pass = instance.optString("password")
                        val recipients = instance.optString("recipients")
                        check(host.isNotBlank() && user.isNotBlank()) { "邮件配置不完整" }
                        sendEmail(host, port, 0, user, pass, recipients, title, content)
                    }
                    ForwardingChannels.QQ -> {
                        val qmsgKey = instance.optString("qmsgKey")
                        val onebotUrl = instance.optString("onebotUrl")
                        val target = if (qmsgKey.isNotBlank()) qmsgKey else onebotUrl
                        val type = if (qmsgKey.isNotBlank()) "qmsg" else "onebot"
                        check(target.isNotBlank()) { "QQ 机器人未配置 Key 或 Webhook" }
                        sendQq(target, type, title, content)
                    }
                    ForwardingChannels.SMS_DIRECT -> {
                        val phone = instance.optString("phone")
                        check(phone.isNotBlank()) { "目标手机号未配置" }
                        sendSmsDirect(phone, content, subscriptionId, isTest = isTest)
                    }
                    ForwardingChannels.CHANNEL_GROUP -> error("通道组必须展开为具体实例后发送")
                    else -> error("暂不支持的通道类型：${instance.channelType}")
                }
            }
        } else {
            val networkAvailable = isNetworkAvailable()
            val onlyOnNoNetwork = config.smsDirectOnlyOnNoNetwork
            Log.d(TAG, "networkAvailable=$networkAvailable, onlyOnNoNetwork=$onlyOnNoNetwork")

        // 短信直发逻辑
        if (shouldRun(ForwardingChannels.SMS_DIRECT, config.smsDirectEnabled)) {
            if (onlyOnNoNetwork) {
                // 仅断网时发送模式
                if (!networkAvailable) {
                    runChannel("短信直发", ForwardingChannels.SMS_DIRECT) {
                        sendSmsDirect(config.smsDirectPhone(), content, subscriptionId, isTest = isTest)
                    }
                }
            } else {
                // 始终发送模式
                runChannel("短信直发", ForwardingChannels.SMS_DIRECT) {
                    sendSmsDirect(config.smsDirectPhone(), content, subscriptionId, isTest = isTest)
                }
            }
        }

        // 网络渠道直接尝试发送，依赖 HTTP 超时和 WorkManager 约束
        if (shouldRun(ForwardingChannels.PUSHPLUS, config.pushPlusEnabled)) runChannel("PushPlus", ForwardingChannels.PUSHPLUS) {
            sendPushPlus(config.pushPlusToken(), config.pushPlusTopic(), title, content)
        }
        if (shouldRun(ForwardingChannels.WECHAT_TEST, config.wechatTestEnabled)) runChannel("微信测试号", ForwardingChannels.WECHAT_TEST) {
            sendWechatTest(config.wechatTestAppId(), config.wechatTestAppSecret(), config.wechatTestTemplateId(), config.wechatTestOpenId(), title, content)
        }
        if (shouldRun(ForwardingChannels.QQ, config.qqEnabled)) runChannel("QQ", ForwardingChannels.QQ) {
            sendQq(config.qqWebhook(), config.qqType(), title, content)
        }
        if (shouldRun(ForwardingChannels.WECOM_APP, config.weComEnabled)) runChannel("企业微信应用号", ForwardingChannels.WECOM_APP) {
            sendWeCom(
                config.weComCorpId(),
                config.weComAgentId(),
                config.weComSecret(),
                config.weComToUser(),
                content
            )
        }
        if (shouldRun(ForwardingChannels.WECOM_BOT, config.weComBotEnabled)) runChannel("企业微信群机器人", ForwardingChannels.WECOM_BOT) {
            sendWeComBot(config.weComBotWebhook(), content)
        }
        if (shouldRun(ForwardingChannels.FEISHU_APP, config.feishuAppEnabled)) runChannel("飞书自建应用", ForwardingChannels.FEISHU_APP) {
            sendFeishuApp(config.feishuAppId(), config.feishuAppSecret(), config.feishuReceiveId(), title, content)
        }
        if (shouldRun(ForwardingChannels.FEISHU_BOT, config.feishuEnabled)) runChannel("飞书群机器人", ForwardingChannels.FEISHU_BOT) {
            sendFeishu(config.feishuWebhook(), config.feishuSecret(), content)
        }
        if (shouldRun(ForwardingChannels.DINGTALK, config.dingTalkEnabled)) runChannel("钉钉群机器人", ForwardingChannels.DINGTALK) {
            sendDingTalk(config.dingTalkWebhook(), config.dingTalkSecret(), content)
        }
        if (shouldRun(ForwardingChannels.BARK, config.barkEnabled)) runChannel("Bark", ForwardingChannels.BARK) {
            sendBark(config.barkServerUrl(), config.barkDeviceKey(), title, content, config.barkAllowHttp)
        }
        if (shouldRun(ForwardingChannels.WEBSOCKET, config.websocketEnabled)) runChannel("WebSocket客户端", ForwardingChannels.WEBSOCKET) {
            sendWebsocket(config.websocketUrl(), config.websocketToken(), title, content)
        }
        if (shouldRun(ForwardingChannels.TELEGRAM, config.telegramEnabled)) runChannel("Telegram", ForwardingChannels.TELEGRAM) {
            sendTelegram(config.telegramBotToken(), config.telegramChatId(), title, content)
        }
        if (shouldRun(ForwardingChannels.DISCORD, config.discordEnabled)) runChannel("Discord", ForwardingChannels.DISCORD) {
            sendDiscord(config.discordWebhook(), title, content)
        }
        if (shouldRun(ForwardingChannels.TENCENT_CLOUD, config.tencentCloudEnabled)) runChannel("腾讯云自定义告警", ForwardingChannels.TENCENT_CLOUD) {
            sendTencentCloud(config.tencentCloudWebhook(), config.tencentCloudSecret(), content)
        }
        if (shouldRun(ForwardingChannels.EMAIL, config.emailEnabled)) runChannel("邮箱", ForwardingChannels.EMAIL) {
            sendEmail(
                config.emailHost(),
                config.emailPort,
                config.emailSecurity,
                config.emailUser(),
                config.emailPassword(),
                config.emailRecipients(),
                title,
                content
            )
        }
        if (shouldRun(ForwardingChannels.CUSTOM_WEBHOOK, config.customWebhookEnabled)) runChannel("自定义Webhook", ForwardingChannels.CUSTOM_WEBHOOK) {
            sendCustomWebhook(
                config.customWebhookUrl(), config.customWebhookHeaders(), config.customWebhookMethod(),
                config.customWebhookContentType(), config.customWebhookBodyTemplate(),
                title, content, sender, receivedAt, subscriptionId
            )
        }
        if (shouldRun(ForwardingChannels.GOTIFY, config.gotifyEnabled)) runChannel("Gotify", ForwardingChannels.GOTIFY) {
            sendGotify(config.gotifyServerUrl(), config.gotifyToken(), title, content, config.gotifyAllowHttp)
        }

        // 多实例渠道池定向调度 (Multi-Instance Channel Hub Dispatch)
        val instances = config.channelInstances().filter { it.enabled && it.hasDispatchConfiguration() }
        for (instance in instances) {
            val matchesTargetChannel = targetChannel.isBlank() ||
                targetChannel == ForwardingChannels.ALL ||
                targetChannel == instance.channelType
            val isInstanceTargeted = matchesTargetChannel && (
                ruleAllowedChannels == null ||
                    ruleAllowedChannels.contains(instance.id) ||
                    ruleAllowedChannels.contains(instance.channelType)
                )
            if (!isInstanceTargeted) continue

            runChannel(instance.name, "instance_${instance.id}") {
                when (instance.channelType) {
                    ForwardingChannels.WECHAT_TEST -> sendWechatTest(
                        instance.optString("appId"),
                        instance.optString("appSecret"),
                        instance.optString("templateId"),
                        instance.optString("openId"),
                        title,
                        content
                    )
                    ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> sendWeCom(
                        instance.optString("corpId"),
                        instance.optString("agentId"),
                        instance.optString("secret"),
                        instance.optString("toUser"),
                        content
                    )
                    ForwardingChannels.WECOM_BOT -> {
                        val webhook = instance.optString("webhook")
                        check(webhook.isNotBlank()) { "企微群机器人 Webhook 未配置" }
                        sendWeComBot(webhook, content)
                    }
                    ForwardingChannels.DINGTALK -> {
                        val webhook = instance.optString("webhook")
                        val secret = instance.optString("secret")
                        check(webhook.isNotBlank()) { "钉钉群机器人 Webhook 未配置" }
                        sendDingTalk(webhook, secret, content)
                    }
                    ForwardingChannels.FEISHU_BOT -> {
                        val webhook = instance.optString("webhook")
                        val secret = instance.optString("secret")
                        check(webhook.isNotBlank()) { "飞书群机器人 Webhook 未配置" }
                        sendFeishu(webhook, secret, content)
                    }
                    ForwardingChannels.FEISHU -> {
                        val webhook = instance.optString("webhook")
                        check(webhook.isNotBlank()) { "飞书群机器人 Webhook 未配置" }
                        sendFeishu(webhook, instance.optString("secret"), content)
                    }
                    ForwardingChannels.FEISHU_APP -> sendFeishuApp(
                        instance.optString("appId"),
                        instance.optString("appSecret"),
                        instance.optString("receiveId"),
                        title,
                        content
                    )
                    ForwardingChannels.TELEGRAM -> {
                        val token = instance.optString("botToken")
                        val chatId = instance.optString("chatId")
                        check(token.isNotBlank() && chatId.isNotBlank()) { "Telegram 凭据未配置" }
                        sendTelegram(token, chatId, title, content)
                    }
                    ForwardingChannels.BARK -> {
                        val url = instance.optString("serverUrl")
                        val key = instance.optString("deviceKey")
                        check(url.isNotBlank() && key.isNotBlank()) { "Bark URL 或 DeviceKey 未配置" }
                        sendBark(url, key, title, content, true)
                    }
                    ForwardingChannels.CUSTOM_WEBHOOK -> {
                        val url = instance.optString("url")
                        val headers = instance.optString("headers")
                        check(url.isNotBlank()) { "自定义 Webhook URL 未配置" }
                        sendCustomWebhook(
                            url, headers, instance.optString("method", "POST"),
                            instance.optString("contentType", "application/json"),
                            instance.optString("bodyTemplate", MultiForwardConfig.DEFAULT_CUSTOM_WEBHOOK_BODY),
                            title, content, sender, receivedAt, subscriptionId
                        )
                    }
                    ForwardingChannels.DISCORD -> {
                        val webhook = instance.optString("webhook")
                        check(webhook.isNotBlank()) { "Discord Webhook 未配置" }
                        sendDiscord(webhook, title, content)
                    }
                    ForwardingChannels.TENCENT_CLOUD -> sendTencentCloud(
                        instance.optString("webhook"),
                        instance.optString("secret"),
                        content
                    )
                    ForwardingChannels.WEBSOCKET -> sendWebsocket(
                        instance.optString("serverUrl"),
                        instance.optString("token"),
                        title,
                        content
                    )
                    ForwardingChannels.PUSHPLUS -> {
                        val token = instance.optString("token")
                        val topic = instance.optString("topic")
                        check(token.isNotBlank()) { "PushPlus Token 未配置" }
                        sendPushPlus(token, topic, title, content)
                    }
                    ForwardingChannels.GOTIFY -> {
                        val url = instance.optString("serverUrl")
                        val token = instance.optString("token")
                        check(url.isNotBlank() && token.isNotBlank()) { "Gotify URL 或 Token 未配置" }
                        sendGotify(url, token, title, content, true)
                    }
                    ForwardingChannels.NTFY -> {
                        val topic = instance.optString("topic")
                        check(topic.isNotBlank()) { "ntfy Topic 未配置" }
                        sendNtfy(
                            instance.optString("serverUrl").ifBlank { "https://ntfy.sh" },
                            topic,
                            instance.optString("token"),
                            instance.optString("priority").ifBlank { "default" },
                            instance.optString("tags"),
                            instance.optString("clickUrl"),
                            title,
                            content
                        )
                    }
                    ForwardingChannels.EMAIL -> sendEmail(
                        instance.optString("host"),
                        instance.optInt("port", 465),
                        0,
                        instance.optString("user"),
                        instance.optString("password"),
                        instance.optString("recipients"),
                        title,
                        content
                    )
                    ForwardingChannels.QQ -> {
                        val qmsgKey = instance.optString("qmsgKey")
                        val onebotUrl = instance.optString("onebotUrl")
                        val target = qmsgKey.ifBlank { onebotUrl }
                        check(target.isNotBlank()) { "QQ 机器人未配置 Key 或 Webhook" }
                        sendQq(target, if (qmsgKey.isNotBlank()) "qmsg" else "onebot", title, content)
                    }
                    ForwardingChannels.SMS_DIRECT -> sendSmsDirect(
                        instance.optString("phone"), content, subscriptionId, isTest
                    )
                    ForwardingChannels.CHANNEL_GROUP -> error("通道组必须展开为具体实例后发送")
                    else -> error("暂不支持的通道类型：${instance.channelType}")
                }
            }
        }
        }

        val now = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        config.lastStatus = buildString {
            append(now)
            if (successes.isNotEmpty()) append(" 成功：${successes.joinToString("、")}")
            if (failures.isNotEmpty()) append(" 失败：${failures.joinToString("；")}")
        }
        Log.d(TAG, "result: successes=$successes, failures=$failures, attempt=$runAttemptCount, isTest=$isTest")

        // 识别是否属于永久性凭据/配置错误（不可通过无脑重试解决）
        fun isPermanentError(msg: String): Boolean {
            val permanentKeywords = listOf(
                "令牌不正确", "Token", "token", "未配置", "配置不完整", "401", "403", "400", "404",
                "invalid", "unauthorized", "Secret", "secret", "不正确", "不存在", "拒绝请求", "签名"
            )
            return permanentKeywords.any { msg.contains(it, ignoreCase = true) }
        }

        val allFailuresArePermanent = failures.isNotEmpty() && failures.all { isPermanentError(it) }
        val canRetry = !isTest && !allFailuresArePermanent && runAttemptCount < 2

        when {
            failures.isEmpty() && successes.isNotEmpty() -> {
                if (historyRecordId.isNotBlank()) {
                    history.markSuccess(historyRecordId, "发送成功：${successes.joinToString("、")}")
                }
                Log.d(TAG, "result: success")
                Result.success()
            }
            failures.isEmpty() -> {
                if (historyRecordId.isNotBlank()) {
                    history.markSkipped(historyRecordId, "渠道已关闭、规则未允许或发送条件未满足")
                }
                Log.d(TAG, "result: skipped")
                Result.success()
            }
            canRetry -> {
                if (historyRecordId.isNotBlank()) history.markRetry(historyRecordId, failures.joinToString("；"))
                Log.d(TAG, "result: retry (transient network error)")
                Result.retry()
            }
            else -> {
                if (historyRecordId.isNotBlank()) history.markFailed(historyRecordId, failures.joinToString("；"))
                Log.d(TAG, "result: failed (permanent or test error, no loop retry)")
                Result.failure()
            }
        }
    }

    private fun sendDingTalk(webhook: String, secret: String, content: String) {
        requireHttps(webhook)
        val timestamp = System.currentTimeMillis()
        val signedUrl = if (secret.isBlank()) {
            webhook
        } else {
            val sign = hmacSha256Base64(secret, "$timestamp\n$secret")
            val separator = if (webhook.contains('?')) '&' else '?'
            "$webhook${separator}timestamp=$timestamp&sign=${URLEncoder.encode(sign, "UTF-8")}"
        }
        val result = postJson(
            signedUrl,
            JSONObject()
                .put("msgtype", "text")
                .put("text", JSONObject().put("content", content))
        )
        check(result.optInt("errcode", -1) == 0) {
            result.optString("errmsg", "钉钉接口拒绝请求")
        }
    }

    private fun sendFeishu(webhook: String, secret: String, content: String) {
        requireHttps(webhook)
        val payload = JSONObject()
            .put("msg_type", "text")
            .put("content", JSONObject().put("text", content))
        if (secret.isNotBlank()) {
            val timestamp = System.currentTimeMillis() / 1000
            val stringToSign = "$timestamp\n$secret"
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(stringToSign.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
            val sign = Base64.encodeToString(mac.doFinal(ByteArray(0)), Base64.NO_WRAP)
            payload.put("timestamp", timestamp.toString()).put("sign", sign)
        }
        val result = postJson(webhook, payload)
        val code = if (result.has("StatusCode")) result.optInt("StatusCode", -1) else result.optInt("code", -1)
        check(code == 0) {
            result.optString("msg", result.optString("StatusMessage", "飞书接口拒绝请求"))
        }
    }

    private fun sendWeCom(
        corpId: String,
        agentId: String,
        secret: String,
        toUser: String,
        content: String
    ) {
        require(corpId.isNotBlank() && agentId.toLongOrNull() != null && secret.isNotBlank() && toUser.isNotBlank()) {
            "企业微信配置不完整"
        }
        val tokenUrl = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=" +
            URLEncoder.encode(corpId, "UTF-8") + "&corpsecret=" + URLEncoder.encode(secret, "UTF-8")
        val tokenResult = getJson(tokenUrl)
        check(tokenResult.optInt("errcode", -1) == 0) {
            tokenResult.optString("errmsg", "获取企业微信 access_token 失败")
        }
        val token = tokenResult.optString("access_token")
        val result = postJson(
            "https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=" +
                URLEncoder.encode(token, "UTF-8"),
            JSONObject()
                .put("touser", toUser)
                .put("msgtype", "text")
                .put("agentid", agentId.trim().toLongOrNull() ?: 0L)
                .put("text", JSONObject().put("content", content))
                .put("safe", 0)
        )
        check(result.optInt("errcode", -1) == 0) {
            result.optString("errmsg", "企业微信接口拒绝请求")
        }
    }

    private fun sendWeComBot(webhook: String, content: String) {
        requireHttps(webhook)
        val result = postJson(
            webhook,
            JSONObject()
                .put("msgtype", "text")
                .put("text", JSONObject().put("content", content))
        )
        check(result.optInt("errcode", -1) == 0) {
            result.optString("errmsg", "企业微信群机器人请求失败")
        }
    }

    private fun sendEmail(
        host: String,
        port: Int,
        security: Int,
        user: String,
        password: String,
        recipientsText: String,
        subject: String,
        content: String,
    ) {
        require(host.isNotBlank() && user.isNotBlank() && password.isNotBlank() && recipientsText.isNotBlank()) {
            "邮箱配置不完整"
        }
        val recipients = recipientsText.split(',', ';')
            .map(String::trim)
            .filter(String::isNotBlank)
        require(recipients.isNotEmpty()) { "未配置收件邮箱" }

        if (security == MultiForwardConfig.EMAIL_SECURITY_STARTTLS) {
            sendEmailStartTls(host, port, user, password, recipients, subject, content)
        } else {
            createTlsSocket(host, port).use { socket ->
                expectSmtp(socket.inputStream.bufferedReader(StandardCharsets.UTF_8), 220)
                runSmtpSession(socket, user, password, recipients, subject, content)
            }
        }
    }

    private fun sendEmailStartTls(
        host: String,
        port: Int,
        user: String,
        password: String,
        recipients: List<String>,
        subject: String,
        content: String,
    ) {
        Socket(host, port).use { plainSocket ->
            plainSocket.soTimeout = SMTP_TIMEOUT_MS
            val reader = plainSocket.inputStream.bufferedReader(StandardCharsets.UTF_8)
            val writer = plainSocket.outputStream.bufferedWriter(StandardCharsets.UTF_8)
            expectSmtp(reader, 220)
            smtpCommand(writer, reader, "EHLO android-sms-forwarder", 250)
            smtpCommand(writer, reader, "STARTTLS", 220)

            val tlsSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(plainSocket, host, port, true) as SSLSocket
            configureTls(tlsSocket)
            tlsSocket.use {
                runSmtpSession(it, user, password, recipients, subject, content)
            }
        }
    }

    private fun runSmtpSession(
        socket: Socket,
        user: String,
        password: String,
        recipients: List<String>,
        subject: String,
        content: String,
    ) {
        val reader = socket.inputStream.bufferedReader(StandardCharsets.UTF_8)
        val writer = socket.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        smtpCommand(writer, reader, "EHLO android-sms-forwarder", 250)
        smtpCommand(writer, reader, "AUTH LOGIN", 334)
        smtpCommand(writer, reader, Base64.encodeToString(user.toByteArray(), Base64.NO_WRAP), 334)
        smtpCommand(writer, reader, Base64.encodeToString(password.toByteArray(), Base64.NO_WRAP), 235)
        smtpCommand(writer, reader, "MAIL FROM:<$user>", 250)
        recipients.forEach { smtpCommand(writer, reader, "RCPT TO:<$it>", 250) }
        smtpCommand(writer, reader, "DATA", 354)

        val encodedSubject = Base64.encodeToString(subject.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        val encodedBody = java.util.Base64.getMimeEncoder(76, "\r\n".toByteArray())
            .encodeToString(content.toByteArray(StandardCharsets.UTF_8))
        writer.write("From: <$user>\r\n")
        writer.write("To: ${recipients.joinToString(", ")}\r\n")
        writer.write("Subject: =?UTF-8?B?$encodedSubject?=\r\n")
        writer.write("MIME-Version: 1.0\r\n")
        writer.write("Content-Type: text/plain; charset=UTF-8\r\n")
        writer.write("Content-Transfer-Encoding: base64\r\n\r\n")
        writer.write(encodedBody)
        writer.write("\r\n.\r\n")
        writer.flush()
        expectSmtp(reader, 250)
        smtpCommand(writer, reader, "QUIT", 221)
    }

    private fun createTlsSocket(host: String, port: Int): SSLSocket =
        (SSLSocketFactory.getDefault().createSocket(host, port) as SSLSocket).also(::configureTls)

    private fun configureTls(socket: SSLSocket) {
        socket.soTimeout = SMTP_TIMEOUT_MS
        socket.enabledProtocols = socket.enabledProtocols
            .filter { it == "TLSv1.2" || it == "TLSv1.3" }
            .toTypedArray()
        socket.sslParameters = socket.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
        }
        socket.startHandshake()
    }

    private fun smtpCommand(
        writer: BufferedWriter,
        reader: BufferedReader,
        command: String,
        expected: Int
    ) {
        writer.write(command)
        writer.write("\r\n")
        writer.flush()
        expectSmtp(reader, expected)
    }

    private fun expectSmtp(reader: BufferedReader, expected: Int) {
        var line = reader.readLine() ?: error("SMTP 服务器无响应")
        val code = line.take(3).toIntOrNull() ?: error("SMTP 响应无效")
        while (line.length > 3 && line[3] == '-') {
            line = reader.readLine() ?: break
        }
        check(code == expected) { "SMTP $code ${line.drop(4)}" }
    }

    private fun hmacSha256Base64(secret: String, content: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return Base64.encodeToString(mac.doFinal(content.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    private fun requireHttps(url: String) {
        require(url.startsWith("https://")) { "Webhook 必须使用 HTTPS" }
    }

    private fun requireHttpsOrAllowedHttp(url: String, allowHttp: Boolean) {
        ForwardingUrlPolicy.requireAllowed(url, allowHttp)
    }

    private fun sendBark(serverUrl: String, deviceKey: String, title: String, content: String, allowHttp: Boolean) {
        require(serverUrl.isNotBlank() && deviceKey.isNotBlank()) { "Bark 配置不完整" }
        val base = serverUrl.trim().trimEnd('/')
        requireHttpsOrAllowedHttp(base, allowHttp)
        val result = postJson(
            "$base/${URLEncoder.encode(deviceKey.trim(), "UTF-8")}",
            JSONObject()
                .put("title", title)
                .put("body", content)
        )
        check(result.optInt("code", -1) == 200) {
            result.optString("message", "Bark 请求失败")
        }
    }

    private fun sendGotify(serverUrl: String, token: String, title: String, content: String, allowHttp: Boolean) {
        require(serverUrl.isNotBlank() && token.isNotBlank()) { "Gotify 配置不完整" }
        val base = serverUrl.trim().trimEnd('/')
        requireHttpsOrAllowedHttp(base, allowHttp)
        val result = postJson(
            "$base/message?token=${URLEncoder.encode(token.trim(), "UTF-8")}",
            JSONObject()
                .put("title", title)
                .put("message", content)
                .put("priority", 5)
        )
        check(result.optLong("id", -1L) > 0L) { "Gotify 请求失败" }
    }

    private fun sendNtfy(
        serverUrl: String,
        topic: String,
        token: String,
        priority: String,
        tags: String,
        clickUrl: String,
        title: String,
        content: String
    ) {
        require(serverUrl.isNotBlank() && topic.isNotBlank()) { "ntfy 服务地址或 Topic 未配置" }
        val base = serverUrl.trim().trimEnd('/')
        requireHttpsOrAllowedHttp(base, base.startsWith("http://"))
        val connection = URL("$base/${URLEncoder.encode(topic.trim(), "UTF-8")}").openConnection() as HttpURLConnection
        connection.withDisconnect {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            setRequestProperty("Title", title)
            setRequestProperty("Priority", priority.ifBlank { "default" })
            if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer ${token.trim()}")
            if (tags.isNotBlank()) setRequestProperty("Tags", tags.trim())
            if (clickUrl.isNotBlank()) setRequestProperty("Click", clickUrl.trim())
            outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(content) }
            val statusCode = responseCode
            val response = (if (statusCode in 200..299) inputStream else errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(statusCode in 200..299) { "ntfy HTTP $statusCode: ${response.take(200)}" }
        }
    }

    private fun getJson(url: String) = requestJson(url, "GET", null)

    private fun postJson(
        url: String,
        payload: JSONObject,
        headers: Map<String, String> = emptyMap()
    ) = requestJson(url, "POST", payload, headers)

    private fun requestJson(
        url: String,
        method: String,
        payload: JSONObject?,
        headers: Map<String, String> = emptyMap()
    ): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return connection.withDisconnect {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (payload != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(payload.toString()) }
            }
            val statusCode = responseCode
            val response = (if (statusCode in 200..299) inputStream else errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            check(statusCode in 200..299) {
                val detail = runCatching {
                    JSONObject(response).optString("message")
                }.getOrDefault("").ifBlank { response.take(200) }
                "HTTP $statusCode${detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
            }
            if (response.isBlank()) {
                JSONObject().put("_httpStatus", statusCode)
            } else {
                runCatching { JSONObject(response) }
                    .getOrElse { JSONObject().put("_httpStatus", statusCode).put("raw", response) }
            }
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: run {
            Log.d(TAG, "network check: no active network")
            return false
        }
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: run {
            Log.d(TAG, "network check: null capabilities for $network")
            return false
        }
        val internet = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val validated = capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        Log.d(TAG, "network check: internet=$internet, validated=$validated")
        return internet || validated
    }

    private fun sendPushPlus(token: String, topic: String, title: String, content: String) {
        require(token.isNotBlank()) { "PushPlus Token 不能为空" }
        val payload = JSONObject()
            .put("token", token)
            .put("title", title)
            .put("content", content.replace("\n", "<br/>"))
            .put("template", "html")
        if (topic.isNotBlank()) payload.put("topic", topic)
        val result = postJson("https://www.pushplus.plus/send", payload)
        check(result.optInt("code", -1) == 200) {
            result.optString("msg", "PushPlus 推送失败")
        }
    }

    private fun sendWechatTest(appId: String, appSecret: String, templateId: String, openId: String, title: String, content: String) {
        require(appId.isNotBlank() && appSecret.isNotBlank() && templateId.isNotBlank() && openId.isNotBlank()) {
            "微信测试号配置不完整"
        }
        val tokenUrl = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=" +
            URLEncoder.encode(appId, "UTF-8") + "&secret=" + URLEncoder.encode(appSecret, "UTF-8")
        val tokenRes = getJson(tokenUrl)
        val token = tokenRes.optString("access_token")
        check(token.isNotBlank()) { tokenRes.optString("errmsg", "微信测试号获取 Token 失败") }

        val dataObj = JSONObject()
            .put("title", JSONObject().put("value", title))
            .put("content", JSONObject().put("value", content))
            .put("time", JSONObject().put("value", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())))

        val sendPayload = JSONObject()
            .put("touser", openId)
            .put("template_id", templateId)
            .put("data", dataObj)

        val sendRes = postJson("https://api.weixin.qq.com/cgi-bin/message/template/send?access_token=$token", sendPayload)
        check(sendRes.optInt("errcode", -1) == 0) { sendRes.optString("errmsg", "微信测试号模板消息发送失败") }
    }

    private fun sendQq(webhookOrKey: String, type: String, title: String, content: String) {
        require(webhookOrKey.isNotBlank()) { "QQ 消息配置不能为空" }
        val text = "【$title】\n$content"
        if (type == "qmsg" || !webhookOrKey.startsWith("http")) {
            val url = "https://qmsg.zendee.cn/send/$webhookOrKey"
            postJson(url, JSONObject().put("msg", text))
        } else {
            postJson(webhookOrKey, JSONObject().put("message", text))
        }
    }

    private fun sendFeishuApp(appId: String, appSecret: String, receiveId: String, title: String, content: String) {
        require(appId.isNotBlank() && appSecret.isNotBlank() && receiveId.isNotBlank()) { "飞书自建应用配置不完整" }
        val authUrl = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
        val authRes = postJson(authUrl, JSONObject().put("app_id", appId).put("app_secret", appSecret))
        val token = authRes.optString("tenant_access_token")
        check(token.isNotBlank()) { authRes.optString("msg", "获取飞书 tenant_access_token 失败") }

        val msgUrl = "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=open_id"
        val contentObj = JSONObject().put("text", "【$title】\n$content")
        val sendPayload = JSONObject()
            .put("receive_id", receiveId)
            .put("msg_type", "text")
            .put("content", contentObj.toString())

        val result = postJson(msgUrl, sendPayload, mapOf("Authorization" to "Bearer $token"))
        check(result.optInt("code", -1) == 0) {
            result.optString("msg", "飞书自建应用发送失败")
        }
    }

    private fun sendTelegram(botToken: String, chatId: String, title: String, content: String) {
        require(botToken.isNotBlank() && chatId.isNotBlank()) { "Telegram 配置不完整" }
        val text = "*$title*\n\n$content"
        val url = "https://api.telegram.org/bot$botToken/sendMessage"
        val payload = JSONObject()
            .put("chat_id", chatId)
            .put("text", text)
        val result = postJson(url, payload)
        check(result.optBoolean("ok", false)) { result.optString("description", "Telegram 发送失败") }
    }

    private fun sendDiscord(webhook: String, title: String, content: String) {
        requireHttps(webhook)
        val embed = JSONObject()
            .put("title", title)
            .put("description", content)
            .put("color", 5814783)
        val payload = JSONObject().put("embeds", org.json.JSONArray().put(embed))
        postJson(webhook, payload)
    }

    private fun sendTencentCloud(webhook: String, secret: String, content: String) {
        requireHttps(webhook)
        val payload = JSONObject()
            .put("text", content)
            .put("secret", secret)
        postJson(webhook, payload)
    }

    private fun sendWebsocket(serverUrl: String, token: String, title: String, content: String) {
        require(serverUrl.isNotBlank()) { "WebSocket 客户端配置不能为空" }
        val payload = JSONObject()
            .put("title", title)
            .put("content", content)
            .put("token", token)
            .put("time", System.currentTimeMillis())
        if (serverUrl.startsWith("http://") || serverUrl.startsWith("https://")) {
            ForwardingUrlPolicy.requireAllowed(serverUrl, serverUrl.startsWith("http://"))
            postJson(serverUrl, payload)
            return
        }

        require(serverUrl.startsWith("ws://") || serverUrl.startsWith("wss://")) {
            "WebSocket 地址必须使用 ws://、wss://、http:// 或 https://"
        }
        val policyUrl = if (serverUrl.startsWith("wss://")) {
            "https://${serverUrl.removePrefix("wss://")}"
        } else {
            "http://${serverUrl.removePrefix("ws://")}"
        }
        ForwardingUrlPolicy.requireAllowed(policyUrl, serverUrl.startsWith("ws://"))
        val latch = CountDownLatch(1)
        val sent = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder().url(serverUrl).apply {
            if (token.isNotBlank()) {
                header("Authorization", "Bearer ${token.trim()}")
                header("X-Token", token.trim())
            }
        }.build()
        client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sent.set(webSocket.send(payload.toString()))
                webSocket.close(1000, "sent")
                latch.countDown()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                failure.set(t)
                latch.countDown()
            }
        })
        val completed = latch.await(12, TimeUnit.SECONDS)
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        check(completed) { "WebSocket 连接超时" }
        failure.get()?.let { throw it }
        check(sent.get()) { "WebSocket 消息未能加入发送队列" }
    }

    private fun sendCustomWebhook(
        url: String,
        headersStr: String,
        methodValue: String,
        contentTypeValue: String,
        bodyTemplate: String,
        title: String,
        content: String,
        sender: String,
        receivedAt: Long,
        subscriptionId: Int
    ) {
        require(url.isNotBlank()) { "自定义 Webhook URL 不能为空" }
        val normalizedUrl = url.trim()
        ForwardingUrlPolicy.requireAllowed(normalizedUrl, normalizedUrl.startsWith("http://", ignoreCase = true))
        val method = methodValue.trim().uppercase().ifBlank { "POST" }
        require(method in setOf("GET", "POST", "PUT")) { "请求方式仅支持 GET、POST 或 PUT" }
        val contentType = contentTypeValue.trim().ifBlank { "application/json" }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(receivedAt))
        val sim = if (subscriptionId >= 0) {
            ForwardingMessageFormatter.getSimDescription(applicationContext, MultiForwardConfig(applicationContext), subscriptionId)
        } else ""
        fun encoded(value: String): String = when {
            method == "GET" -> URLEncoder.encode(value, "UTF-8")
            contentType.contains("json", ignoreCase = true) -> JSONObject.quote(value).removeSurrounding("\"")
            contentType.contains("x-www-form-urlencoded", ignoreCase = true) -> URLEncoder.encode(value, "UTF-8")
            else -> value
        }
        val rendered = WebhookTemplateRenderer.render(
            bodyTemplate.ifBlank { MultiForwardConfig.DEFAULT_CUSTOM_WEBHOOK_BODY },
            mapOf(
                "title" to encoded(title), "msg" to encoded(content),
                "from" to encoded(sender), "time" to encoded(time), "sim" to encoded(sim)
            )
        )
        val requestUrl = if (method == "GET" && rendered.isNotBlank()) {
            WebhookRequestUrl.appendQuery(normalizedUrl, rendered)
        } else normalizedUrl
        val connection = URL(requestUrl).openConnection() as HttpURLConnection
        connection.withDisconnect {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json, text/plain, */*")
            parseCustomHeaders(headersStr).forEach { (name, value) -> setRequestProperty(name, value) }
            if (method != "GET") {
                doOutput = true
                if (getRequestProperty("Content-Type").isNullOrBlank()) {
                    setRequestProperty("Content-Type", "$contentType; charset=utf-8")
                }
                outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(rendered) }
            }
            val statusCode = responseCode
            val response = (if (statusCode in 200..299) inputStream else errorStream)
                ?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
            check(statusCode in 200..299) { "HTTP $statusCode: ${response.take(200)}" }
        }
    }

    private fun parseCustomHeaders(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        val jsonHeaders = runCatching {
            val json = JSONObject(raw)
            buildMap {
                json.keys().forEach { key ->
                    val name = key.trim()
                    val value = json.optString(key).trim()
                    if (name.isNotBlank() && value.isNotBlank()) put(name, value)
                }
            }
        }.getOrNull()
        if (!jsonHeaders.isNullOrEmpty()) return jsonHeaders

        return buildMap {
            raw.lineSequence().forEach { line ->
                val separator = line.indexOf(':')
                if (separator > 0) {
                    val name = line.substring(0, separator).trim()
                    val value = line.substring(separator + 1).trim()
                    if (name.isNotBlank() && value.isNotBlank()) put(name, value)
                }
            }
        }
    }

    private fun sendSmsDirect(phone: String, content: String, receiveSubId: Int, isTest: Boolean = false) {
        require(phone.isNotBlank()) { "目标手机号不能为空" }
        val normalized = phone.trim()
        require(normalized.isNotEmpty()) { "目标手机号格式无效" }
        val triggerType = if (isTest) {
            org.fossify.messages.models.SmsSendTriggerType.SMS_DIRECT_TEST
        } else {
            org.fossify.messages.models.SmsSendTriggerType.FORWARDING_SMS_DIRECT
        }
        applicationContext.sendMessageCompat(
            text = content,
            addresses = listOf(normalized),
            subId = receiveSubId.takeIf { it >= 0 },
            attachments = emptyList(),
            propagateErrors = true,
            triggerType = triggerType
        )
    }

    companion object {
        private const val TAG = "MultiChannelForward"
        private const val KEY_SENDER = "sender"
        private const val KEY_BODY = "body"
        private const val KEY_RECEIVED_AT = "received_at"
        private const val KEY_SUBSCRIPTION_ID = "subscription_id"
        private const val KEY_TARGET_CHANNEL = "target_channel"
        private const val KEY_ALLOWED_CHANNELS = "allowed_channels"
        private const val KEY_IS_TEST = "is_test"
        private const val KEY_HISTORY_RECORD_ID = "history_record_id"
        private const val KEY_OPERATION_ID = "operation_id"
        const val KEY_INSTANCE_ID = "target_instance_id"
        const val KEY_RULE_ID = "rule_id"
        const val KEY_ACTION_ID = "action_id"
        private const val KEY_BODY_ALREADY_RENDERED = "body_already_rendered"
        private const val SMTP_TIMEOUT_MS = 12_000
        /** 规则启用但无任何渠道命中时，与 null（未启用规则）区分 */
        const val RULE_BLOCK_ALL = "__BLOCK_ALL__"

        private fun decodeRuleAllowedChannels(raw: String): Set<String>? = when {
            raw.isBlank() -> null
            raw == RULE_BLOCK_ALL -> emptySet()
            else -> raw.split(',').map(String::trim).filter(String::isNotBlank).toSet()
        }

        private fun encodeRuleAllowedChannels(allowedChannels: Set<String>?): String = when {
            allowedChannels == null -> ""
            allowedChannels.isEmpty() -> RULE_BLOCK_ALL
            else -> allowedChannels.joinToString(",")
        }

        fun enqueue(
            context: Context,
            sender: String,
            body: String,
            receivedAt: Long,
            subscriptionId: Int,
            uniqueId: String,
            targetChannel: String = "",
            allowedChannels: Set<String>? = null,
            operationId: String? = null
        ) {
            Log.d(TAG, "enqueue: uniqueId=$uniqueId, targetChannel=$targetChannel, allowedChannels=$allowedChannels")
            if (targetChannel.isBlank()) {
                val enabledChannels = MultiForwardConfig(context).enabledChannelIds()
                val selectedChannels = allowedChannels
                    ?.let(enabledChannels::intersect)
                    ?: enabledChannels
                Log.d(TAG, "enqueue: broadcasting to ${selectedChannels.size} channels")
                selectedChannels.forEach { channel ->
                    enqueueSingle(
                        context = context,
                        sender = sender,
                        body = body,
                        receivedAt = receivedAt,
                        subscriptionId = subscriptionId,
                        uniqueId = uniqueId,
                        targetChannel = channel,
                        allowedChannels = setOf(channel),
                        isTest = false,
                        operationId = operationId
                    )
                }
                return
            }
            enqueueSingle(
                context = context,
                sender = sender,
                body = body,
                receivedAt = receivedAt,
                subscriptionId = subscriptionId,
                uniqueId = uniqueId,
                targetChannel = targetChannel,
                allowedChannels = allowedChannels,
                isTest = false,
                operationId = operationId
            )
        }

        fun enqueueSingle(
            context: Context,
            sender: String,
            body: String,
            receivedAt: Long,
            subscriptionId: Int,
            uniqueId: String,
            targetChannel: String,
            allowedChannels: Set<String>?,
            isTest: Boolean,
            operationId: String? = null,
            targetInstanceId: String = "",
            ruleId: String = "",
            actionId: String = "",
            bodyAlreadyRendered: Boolean = false,
            visitedGroupIds: Set<String> = emptySet()
        ) {
            val multiConfig = MultiForwardConfig(context)
            val requestedInstance = targetInstanceId.takeIf(String::isNotBlank)
                ?.let(multiConfig::getChannelInstanceById)
            if (targetChannel == ForwardingChannels.CHANNEL_GROUP || requestedInstance?.channelType == ForwardingChannels.CHANNEL_GROUP) {
                val groupIdentity = targetInstanceId.ifBlank { ForwardingChannels.CHANNEL_GROUP }
                if (groupIdentity in visitedGroupIds) {
                    val history = ForwardingHistoryStore(context)
                    val historyId = history.registerQueued(
                        workId = uniqueId,
                        channel = ForwardingChannels.CHANNEL_GROUP,
                        sender = sender,
                        body = body,
                        receivedAt = receivedAt,
                        subscriptionId = subscriptionId,
                        isTest = isTest
                    )
                    history.markSkipped(historyId, "检测到通道组循环引用，已停止展开")
                    return
                }
                val instanceMembers = requestedInstance?.let { group ->
                    runCatching {
                        val array = JSONObject(group.configJson).optJSONArray("members") ?: return@runCatching emptySet<String>()
                        buildSet {
                            for (index in 0 until array.length()) {
                                array.optString(index).trim().takeIf(String::isNotBlank)?.let(::add)
                            }
                        }
                    }.getOrDefault(emptySet())
                }.orEmpty()
                val members = instanceMembers.ifEmpty { multiConfig.channelGroupMembers() }
                    .filterNot { it == ForwardingChannels.CHANNEL_GROUP || it == targetInstanceId }
                    .toSet()
                if (members.isEmpty()) {
                    val history = ForwardingHistoryStore(context)
                    val historyId = history.registerQueued(
                        workId = uniqueId,
                        channel = ForwardingChannels.CHANNEL_GROUP,
                        sender = sender,
                        body = body,
                        receivedAt = receivedAt,
                        subscriptionId = subscriptionId,
                        isTest = isTest
                    )
                    history.markSkipped(historyId, "通道组没有可发送的成员")
                    return
                }
                members.forEach { member ->
                    val memberInstance = multiConfig.getChannelInstanceById(member)
                    enqueueSingle(
                        context = context,
                        sender = sender,
                        body = body,
                        receivedAt = receivedAt,
                        subscriptionId = subscriptionId,
                        uniqueId = "$uniqueId-group-$member",
                        targetChannel = memberInstance?.channelType ?: member,
                        allowedChannels = setOf(member),
                        isTest = isTest,
                        operationId = operationId,
                        targetInstanceId = memberInstance?.id.orEmpty(),
                        ruleId = ruleId,
                        actionId = actionId,
                        bodyAlreadyRendered = bodyAlreadyRendered,
                        visitedGroupIds = visitedGroupIds + groupIdentity
                    )
                }
                return
            }
            val history = ForwardingHistoryStore(context)
            val effectiveChannel = if (targetInstanceId.isNotBlank()) "instance_$targetInstanceId" else targetChannel
            val constraintChannel = if (targetInstanceId.isNotBlank()) {
                MultiForwardConfig(context).getChannelInstanceById(targetInstanceId)?.channelType ?: targetChannel
            } else targetChannel
            val historyRecordId = history.registerQueued(
                workId = uniqueId,
                channel = effectiveChannel,
                sender = sender,
                body = body,
                receivedAt = receivedAt,
                subscriptionId = subscriptionId,
                isTest = isTest,
            )
            Log.d(TAG, "enqueueSingle: channel=$targetChannel, instance=$targetInstanceId, historyId=$historyRecordId, workId=$uniqueId")
            val safeBody = if (body.length > 4000) body.take(4000) + "…(内容过长已截断)" else body
            val request = OneTimeWorkRequestBuilder<MultiChannelForwardWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SENDER to sender,
                        KEY_BODY to safeBody,
                        KEY_RECEIVED_AT to receivedAt,
                        KEY_SUBSCRIPTION_ID to subscriptionId,
                        KEY_TARGET_CHANNEL to targetChannel,
                        KEY_ALLOWED_CHANNELS to encodeRuleAllowedChannels(allowedChannels),
                        KEY_IS_TEST to isTest,
                        KEY_HISTORY_RECORD_ID to historyRecordId,
                        KEY_OPERATION_ID to operationId,
                        KEY_INSTANCE_ID to targetInstanceId,
                        KEY_RULE_ID to ruleId,
                        KEY_ACTION_ID to actionId,
                        KEY_BODY_ALREADY_RENDERED to bodyAlreadyRendered
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (constraintChannel in ForwardingChannels.networkChannels) {
                                NetworkType.CONNECTED
                            } else {
                                NetworkType.NOT_REQUIRED
                            },
                        )
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            runCatching {
                WorkManager.getInstance(context)
                    .enqueueUniqueWork("multi-forward-$uniqueId-$effectiveChannel", ExistingWorkPolicy.KEEP, request)
            }.onFailure { error ->
                history.markFailed(historyRecordId, "发送任务入队失败：${error.message ?: error.javaClass.simpleName}")
            }
        }

        fun enqueueTest(context: Context, channel: String = ForwardingChannels.ALL, sender: String = "10086", body: String = "这是一条短信多渠道转发测试消息") {
            val channels = if (channel == ForwardingChannels.ALL) {
                MultiForwardConfig(context).enabledChannelIds()
            } else {
                setOf(channel)
            }
            val now = System.currentTimeMillis()
            channels.forEach { target ->
                enqueueSingle(
                    context = context,
                    sender = sender,
                    body = body,
                    receivedAt = now,
                    subscriptionId = -1,
                    uniqueId = "test-$now",
                    targetChannel = target,
                    allowedChannels = setOf(target),
                    isTest = true,
                )
            }
        }
    }
}
