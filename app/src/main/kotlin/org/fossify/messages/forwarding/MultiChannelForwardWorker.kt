package org.fossify.messages.forwarding

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
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
import java.net.HttpURLConnection
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
import org.fossify.commons.extensions.notificationManager
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.markThreadMessagesRead
import org.fossify.messages.helpers.refreshConversations
import org.fossify.messages.helpers.ShadowRepository
import org.fossify.messages.models.ForwardingShadowAttempt

class MultiChannelForwardWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private var smsDirectAttempted = false
    private var smsDirectPreviouslyStarted = false
    private var smsDirectSource = ""

    private fun receivedSimSnapshot(): ForwardingSimSnapshot? {
        if (!inputData.getBoolean(KEY_SIM_CAPTURED, false)) return null // Older queued work stays compatible.
        return ForwardingSimSnapshot(
            inputData.getInt(KEY_SIM_SLOT, -1), inputData.getString(KEY_SIM_LABEL).orEmpty(),
            inputData.getString(KEY_SIM_NUMBER).orEmpty(), inputData.getString(KEY_SIM_CUSTOM).orEmpty()
        )
    }

    override suspend fun getForegroundInfo() = ForwardingForegroundInfo.create(applicationContext)

    override suspend fun doWork(): Result {
        val store = ForwardingHistoryStore(applicationContext)
        val historyId = inputData.getString(KEY_HISTORY_RECORD_ID).orEmpty()
        val safeRetry = runAttemptCount > 0 && (store.record(historyId)?.status == ForwardingHistoryStore.STATUS_RETRY ||
            (inputData.getString(KEY_TARGET_CHANNEL) == ForwardingChannels.SMS_DIRECT &&
                SmsDirectBillingPolicy.canResumePreparation(store.smsPhase(historyId))))
        if (!store.claimSubmissionExecution(id.toString(), safeRetry)) {
            if (store.record(historyId)?.status !in setOf(ForwardingHistoryStore.STATUS_SUCCESS,
                    ForwardingHistoryStore.STATUS_FAILED, ForwardingHistoryStore.STATUS_SKIPPED))
                store.markUnknown(historyId, "重试请求已经开始过执行，未再次提交；请核对接收端")
            return Result.success()
        }
        val planText = inputData.getString(KEY_GROUP_PLAN).orEmpty()
        if (planText.isBlank()) return performWork()
        val history = ForwardingHistoryStore(applicationContext)
        val recordId = inputData.getString(KEY_HISTORY_RECORD_ID).orEmpty()
        val previous = history.groupStatus(recordId)
        val plan = JSONObject(planText)
        val group = MultiForwardConfig(applicationContext).getChannelInstanceById(plan.getString("groupId"))
        if (group == null || !group.enabled) {
            history.markSkipped(recordId, "通道组已停用或删除，停止后续执行")
            return Result.success()
        }
        val terminal = setOf(ForwardingHistoryStore.STATUS_SUCCESS, ForwardingHistoryStore.STATUS_FAILED,
            ForwardingHistoryStore.STATUS_SKIPPED, ForwardingHistoryStore.STATUS_UNKNOWN)
        // A restarted group member must not resubmit a request whose outcome was not persisted.
        val result = when {
            previous in terminal -> Result.success()
            previous == ForwardingHistoryStore.STATUS_RUNNING -> {
                history.setGroupStatus(recordId, ForwardingHistoryStore.STATUS_UNKNOWN)
                history.markUnknown(recordId, "任务中断，上一请求结果未知；已停止通道组，避免重复发送")
                Result.success()
            }
            else -> {
                history.setGroupStatus(recordId, ForwardingHistoryStore.STATUS_RUNNING)
                val outcome = performWork()
                val status = if (outcome == Result.retry()) ForwardingHistoryStore.STATUS_QUEUED
                    else history.record(recordId)?.status ?: ForwardingHistoryStore.STATUS_UNKNOWN
                history.setGroupStatus(recordId, status)
                outcome
            }
        }
        if (result != Result.retry()) advanceGroup(planText, history.groupStatus(recordId))
        return result
    }

    private fun advanceGroup(planText: String, completed: String?) {
        if (completed == null) return
        val plan = JSONObject(planText)
        if (!GroupExecutionPolicy.shouldContinue(plan.getString("mode"), completed)) return
        val members = plan.getJSONArray("members")
        val next = plan.optInt("index") + 1
        if (next >= members.length()) return
        plan.put("index", next)
        val instanceId = members.getString(next)
        val config = MultiForwardConfig(applicationContext)
        val instance = config.getChannelInstanceById(instanceId)
        val type = instance?.channelType ?: plan.getJSONArray("types").getString(next)
        enqueueSingle(
            context = applicationContext,
            sender = inputData.getString(KEY_SENDER).orEmpty(),
            body = inputData.getString(KEY_BODY).orEmpty(),
            receivedAt = inputData.getLong(KEY_RECEIVED_AT, 0),
            subscriptionId = inputData.getInt(KEY_SUBSCRIPTION_ID, -1),
            uniqueId = plan.getString("run") + "-step-$next",
            targetChannel = type, allowedChannels = setOf(instanceId),
            isTest = inputData.getBoolean(KEY_IS_TEST, false),
            operationId = inputData.getString(KEY_OPERATION_ID),
            targetInstanceId = instanceId,
            ruleId = inputData.getString(KEY_RULE_ID).orEmpty(),
            actionId = inputData.getString(KEY_ACTION_ID).orEmpty(),
            bodyAlreadyRendered = inputData.getBoolean(KEY_BODY_ALREADY_RENDERED, false),
            threadId = inputData.getLong(KEY_THREAD_ID, 0),
            groupPlan = plan.toString(),
            originalSim = receivedSimSnapshot(),
            allowSimLabelPrefix = inputData.getBoolean(KEY_ALLOW_SIM_LABEL_PREFIX, false),
            originalSmsDirectSource = inputData.getString(KEY_SMS_DIRECT_SOURCE).orEmpty(),
        )
    }

    private suspend fun performWork(): Result = withContext(Dispatchers.IO) {
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
        val threadId = inputData.getLong(KEY_THREAD_ID, 0L)
        val operationId = inputData.getString(KEY_OPERATION_ID)

        // Shadow observation: Worker started
        operationId?.let { ShadowRepository.recordStep(applicationContext, it, "MULTICHANNEL_WORKER", "STARTED", "target=$targetChannel") }

        val previousRecord = historyRecordId.takeIf(String::isNotBlank)?.let(history::record)
        smsDirectSource = inputData.getString(KEY_SMS_DIRECT_SOURCE).orEmpty().ifBlank {
            SmsDirectBillingPolicy.workerSource(previousRecord?.workId.orEmpty(), receivedAt,
                historyRecordId.ifBlank { id.toString() })
        }
        if (historyRecordId.isNotBlank()) {
            smsDirectPreviouslyStarted = (previousRecord?.attempts ?: 0) > 0
            history.markRunning(historyRecordId)
        }
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
        val instanceManagedTypes = config.managedLegacyChannelTypes()
        fun shouldRun(channel: String, enabled: Boolean): Boolean {
            if (!enabled && !(isTest && targetChannel == channel)) return false
            // 已迁移、已停用或已删除的实例类型均不得回退到旧凭据发送。
            if (channel in instanceManagedTypes) return false
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
            simSnapshot = receivedSimSnapshot(),
        )
        val payload = if (inputData.getBoolean(KEY_BODY_ALREADY_RENDERED, false)) {
            formattedPayload.copy(content = body)
        } else formattedPayload
        val title = payload.title
        val content = payload.content
        // Some providers only accept a text body. Their payload otherwise drops the
        // SIM name, which the common formatter places in the separate title field.
        // Keep explicit global/rule templates intact and avoid adding any SIM when
        // the exact subscription cannot be resolved.
        val textOnlyContent = if (SimLabelPresentation.preserveBody(
                inputData.getBoolean(KEY_BODY_ALREADY_RENDERED, false),
                inputData.getBoolean(KEY_ALLOW_SIM_LABEL_PREFIX, false),
                config.templateMode == MultiForwardConfig.TEMPLATE_CUSTOM && config.customTemplate.isNotBlank()
            )) content
        else ForwardingMessageFormatter.withCustomSimLabel(
            applicationContext, config, subscriptionId, content, receivedSimSnapshot()
        )

        val targetInstanceId = inputData.getString(KEY_INSTANCE_ID).orEmpty()
        val ruleId = inputData.getString(KEY_RULE_ID).orEmpty()
        val actionId = inputData.getString(KEY_ACTION_ID).orEmpty()

        var outcomeUnknown = false
        val successes = mutableListOf<String>()
        val failures = mutableListOf<String>()
        val skippedReasons = mutableListOf<String>()

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
                                state = "SUCCESS"
                            )
                        )
                    }
                    Log.d(TAG, "channel success: $name")
                }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    outcomeUnknown = outcomeUnknown || DeliveryFailurePolicy.isUnknown(it)
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
            val originalTargetOnly = inputData.getBoolean("queue_repair", false) ||
                inputData.getBoolean("manual_retry", false) || previousRecord?.workId?.startsWith("manual-retry-") == true
            if (originalTargetOnly && instance != null && instance.channelType != targetChannel) {
                history.markSkipped(historyRecordId, "原目标类型已改变，停止本次补偿或重试")
                return@withContext Result.success()
            }
            // Safe one-hop fallback: only missing/disabled/unconfigured primary, before API submission.
            if (!isTest && !originalTargetOnly &&
                (instance == null || !instance.enabled || !instance.hasDispatchConfiguration()) &&
                !inputData.getBoolean("fallback_hop", false) && inputData.getString(KEY_GROUP_PLAN).isNullOrBlank()) {
                val backup = ForwardingFallbackConfig(applicationContext).resolve(targetInstanceId, config.channelInstances())
                if (backup != null) {
                    val fallbackHistoryId = history.registerQueued(
                        "fallback-${id}", "instance_${backup.id}", sender, body, receivedAt, subscriptionId, false)
                    val fallbackWork = OneTimeWorkRequestBuilder<MultiChannelForwardWorker>()
                        .addTag("forwarding-history:$fallbackHistoryId")
                        .setInputData(androidx.work.Data.Builder().putAll(inputData)
                            .putString(KEY_INSTANCE_ID, backup.id)
                            .putString(KEY_TARGET_CHANNEL, backup.channelType)
                            .putBoolean("fallback_hop", true)
                            .putString(KEY_HISTORY_RECORD_ID, fallbackHistoryId)
                            .build())
                        .setConstraints(androidx.work.Constraints.Builder()
                            .setRequiredNetworkType(if (backup.channelType in ForwardingChannels.networkChannels) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
                            .build()).build()
                    ForwardingWorkScheduler.enqueue(applicationContext, "fallback-${id}", fallbackWork, fallbackHistoryId)
                    if (historyRecordId.isNotBlank()) history.markSkipped(historyRecordId, "主通道发送前不可用，已交给备用通道")
                    return@withContext Result.success()
                }
            }
            if (instance == null) {
                history.markSkipped(historyRecordId, "目标实例不存在 (ID: $targetInstanceId)")
                return@withContext Result.success()
            }
            if (!instance.enabled) {
                history.markSkipped(historyRecordId, "目标通道实例已停用 (${instance.name})")
                return@withContext Result.success()
            }
            if (!isTest && instance.channelType == ForwardingChannels.SMS_DIRECT &&
                instance.optBoolean("onlyOnNoNetwork", config.smsDirectOnlyOnNoNetwork) && isNetworkAvailable()
            ) {
                history.markSkipped(historyRecordId, "网络已通过系统验证，短信直发实例仅在断网时发送")
                return@withContext Result.success()
            }
            runChannel(instance.name, instance.id) {
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
                        sendWeCom(corpId, agentId, secret, toUser, textOnlyContent)
                    }
                    ForwardingChannels.WECOM_BOT -> {
                        val webhook = instance.optString("webhook")
                        check(webhook.isNotBlank()) { "企微群机器人 Webhook 未配置" }
                        sendWeComBot(webhook, textOnlyContent)
                    }
                    ForwardingChannels.WECOM_STREAM -> {
                        val chatId = instance.optString("chatId")
                        val sourceInstanceId = instance.optString("sourceInstanceId")
                        check(chatId.isNotBlank()) { "企微智能机器人长连接推送目标 ChatID / UserID 未配置" }
                        val result = org.fossify.messages.remote.runtime.RemoteSourceRuntimeManager
                            .getInstance(applicationContext)
                            .sendWeComPush(sourceInstanceId, chatId, "$title\n$content")
                        DeliveryFailurePolicy.requireRemoteAccepted(result.isSuccess, result.code,
                            result.message, result.weComErrorCode)
                    }
                    ForwardingChannels.DINGTALK -> {
                        val webhook = instance.optString("webhook")
                        val secret = instance.optString("secret")
                        check(webhook.isNotBlank()) { "钉钉群机器人 Webhook 未配置" }
                        sendDingTalk(webhook, secret, textOnlyContent)
                    }
                    ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT -> {
                        val webhook = instance.optString("webhook")
                        val secret = instance.optString("secret")
                        check(webhook.isNotBlank()) { "飞书群机器人 Webhook 未配置" }
                        sendFeishu(webhook, secret, textOnlyContent)
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
                            title, content, sender, receivedAt, subscriptionId,
                            instance.optString("successMode", "http"),
                            instance.optString("successField"), instance.optString("successValue")
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
                        sendTencentCloud(webhook, instance.optString("secret"), textOnlyContent)
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
                    ForwardingChannels.WXPUSHER -> WxPusherSender.send(
                        instance.optString("appToken"), instance.optString("targetId"), title, content
                    )
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
                    ForwardingChannels.SERVERCHAN3 -> {
                        val sendKey = instance.optString("sendKey")
                        check(sendKey.isNotBlank()) { "Server酱³ SendKey 未配置" }
                        sendServerChan3(
                            sendKey = sendKey,
                            tags = instance.optString("tags"),
                            title = title,
                            content = content
                        )
                    }
                    ForwardingChannels.EMAIL -> {
                        val host = instance.optString("host")
                        val port = instance.optInt("port", 465)
                        val user = instance.optString("user")
                        val pass = instance.optString("password")
                        val recipients = instance.optString("recipients")
                        val security = instance.optInt(
                            "security",
                            if (port == 587) MultiForwardConfig.EMAIL_SECURITY_STARTTLS else MultiForwardConfig.EMAIL_SECURITY_SSL
                        )
                        check(host.isNotBlank() && user.isNotBlank()) { "邮件配置不完整" }
                        sendEmail(host, port, security, user, pass, recipients, title, content)
                    }
                    ForwardingChannels.QQ -> {
                        val qmsgKey = instance.optString("qmsgKey")
                        val onebotUrl = instance.optString("onebotUrl")
                        val target = if (qmsgKey.isNotBlank()) qmsgKey else onebotUrl
                        val type = if (qmsgKey.isNotBlank()) "qmsg" else "onebot"
                        check(target.isNotBlank()) { "QQ 机器人未配置 Key 或 Webhook" }
                        sendQq(
                            target, type, title, content,
                            instance.optString("accessToken"),
                            instance.optString("targetType").ifBlank { "private" },
                            instance.optString("targetId")
                        )
                    }
                    ForwardingChannels.SMS_DIRECT -> {
                        val phone = instance.optString("phone")
                        check(phone.isNotBlank()) { "目标手机号未配置" }
                        sendSmsDirect(phone, textOnlyContent, subscriptionId, isTest = isTest)
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
            if (onlyOnNoNetwork && !isTest) {
                // 仅断网时发送模式
                if (!networkAvailable) {
                    runChannel("短信直发", ForwardingChannels.SMS_DIRECT) {
                        sendSmsDirect(config.smsDirectPhone(), textOnlyContent, subscriptionId, isTest = isTest)
                    }
                } else {
                    skippedReasons += "网络已通过系统验证，跳过仅断网短信直发"
                }
            } else {
                // 始终发送模式
                runChannel("短信直发", ForwardingChannels.SMS_DIRECT) {
                    sendSmsDirect(config.smsDirectPhone(), textOnlyContent, subscriptionId, isTest = isTest)
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
                textOnlyContent
            )
        }
        if (shouldRun(ForwardingChannels.WECOM_BOT, config.weComBotEnabled)) runChannel("企业微信群机器人", ForwardingChannels.WECOM_BOT) {
            sendWeComBot(config.weComBotWebhook(), textOnlyContent)
        }
        if (shouldRun(ForwardingChannels.FEISHU_APP, config.feishuAppEnabled)) runChannel("飞书自建应用", ForwardingChannels.FEISHU_APP) {
            sendFeishuApp(config.feishuAppId(), config.feishuAppSecret(), config.feishuReceiveId(), title, content)
        }
        if (shouldRun(ForwardingChannels.FEISHU_BOT, config.feishuEnabled)) runChannel("飞书群机器人", ForwardingChannels.FEISHU_BOT) {
            sendFeishu(config.feishuWebhook(), config.feishuSecret(), textOnlyContent)
        }
        if (shouldRun(ForwardingChannels.DINGTALK, config.dingTalkEnabled)) runChannel("钉钉群机器人", ForwardingChannels.DINGTALK) {
            sendDingTalk(config.dingTalkWebhook(), config.dingTalkSecret(), textOnlyContent)
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
            sendTencentCloud(config.tencentCloudWebhook(), config.tencentCloudSecret(), textOnlyContent)
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
            if (!isTest && instance.channelType == ForwardingChannels.SMS_DIRECT &&
                instance.optBoolean("onlyOnNoNetwork", config.smsDirectOnlyOnNoNetwork) && isNetworkAvailable()
            ) {
                skippedReasons += "${instance.name}：网络已通过系统验证，跳过仅断网短信直发"
                continue
            }

            runChannel(instance.name, instance.id) {
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
                        textOnlyContent
                    )
                    ForwardingChannels.WECOM_BOT -> {
                        val webhook = instance.optString("webhook")
                        check(webhook.isNotBlank()) { "企微群机器人 Webhook 未配置" }
                        sendWeComBot(webhook, textOnlyContent)
                    }
                    ForwardingChannels.WECOM_STREAM -> {
                        val chatId = instance.optString("chatId")
                        val sourceInstanceId = instance.optString("sourceInstanceId")
                        check(chatId.isNotBlank()) { "企微智能机器人长连接推送目标 ChatID / UserID 未配置" }
                        val result = org.fossify.messages.remote.runtime.RemoteSourceRuntimeManager
                            .getInstance(applicationContext)
                            .sendWeComPush(sourceInstanceId, chatId, "$title\n$content")
                        DeliveryFailurePolicy.requireRemoteAccepted(result.isSuccess, result.code,
                            result.message, result.weComErrorCode)
                    }
                    ForwardingChannels.DINGTALK -> {
                        val webhook = instance.optString("webhook")
                        val secret = instance.optString("secret")
                        check(webhook.isNotBlank()) { "钉钉群机器人 Webhook 未配置" }
                        sendDingTalk(webhook, secret, textOnlyContent)
                    }
                    ForwardingChannels.FEISHU_BOT -> {
                        val webhook = instance.optString("webhook")
                        val secret = instance.optString("secret")
                        check(webhook.isNotBlank()) { "飞书群机器人 Webhook 未配置" }
                        sendFeishu(webhook, secret, textOnlyContent)
                    }
                    ForwardingChannels.FEISHU -> {
                        val webhook = instance.optString("webhook")
                        check(webhook.isNotBlank()) { "飞书群机器人 Webhook 未配置" }
                        sendFeishu(webhook, instance.optString("secret"), textOnlyContent)
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
                        textOnlyContent
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
                    ForwardingChannels.WXPUSHER -> WxPusherSender.send(
                        instance.optString("appToken"), instance.optString("targetId"), title, content
                    )
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
                    ForwardingChannels.EMAIL -> {
                        val port = instance.optInt("port", 465)
                        val security = instance.optInt(
                            "security",
                            if (port == 587) MultiForwardConfig.EMAIL_SECURITY_STARTTLS else MultiForwardConfig.EMAIL_SECURITY_SSL
                        )
                        sendEmail(
                            instance.optString("host"), port, security,
                            instance.optString("user"), instance.optString("password"),
                            instance.optString("recipients"), title, content
                        )
                    }
                    ForwardingChannels.SERVERCHAN3 -> sendServerChan3(
                        instance.optString("sendKey"), instance.optString("tags"), title, content
                    )
                    ForwardingChannels.QQ -> {
                        val qmsgKey = instance.optString("qmsgKey")
                        val onebotUrl = instance.optString("onebotUrl")
                        val target = qmsgKey.ifBlank { onebotUrl }
                        check(target.isNotBlank()) { "QQ 机器人未配置 Key 或 Webhook" }
                        sendQq(
                            target, if (qmsgKey.isNotBlank()) "qmsg" else instance.optString("type").ifBlank { "onebot" }, title, content,
                            instance.optString("accessToken"),
                            instance.optString("targetType").ifBlank { "private" },
                            instance.optString("targetId")
                        )
                    }
                    ForwardingChannels.SMS_DIRECT -> sendSmsDirect(
                        instance.optString("phone"), textOnlyContent, subscriptionId, isTest
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
            if (skippedReasons.isNotEmpty()) append(" 跳过：${skippedReasons.joinToString("；")}")
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
        // Retrying the whole Worker after one channel succeeded would submit that channel again.
        val canRetry = !smsDirectAttempted && !isTest && !outcomeUnknown && inputData.getString(KEY_GROUP_PLAN).isNullOrBlank() && successes.isEmpty() && !allFailuresArePermanent && runAttemptCount < 2

        when {
            outcomeUnknown -> {
                history.markUnknown(historyRecordId, "发送结果未知，为避免重复投递不自动重试或切换通道；" + failures.joinToString("；"))
                Result.failure()
            }
            failures.isEmpty() && successes.isNotEmpty() -> {
                if (historyRecordId.isNotBlank()) {
                    val skippedDetail = if (skippedReasons.isEmpty()) "" else
                        "；跳过：${skippedReasons.joinToString("；")}"
                    history.markSuccess(historyRecordId, "已提交：${successes.joinToString("、")}$skippedDetail")
                }
                if (!isTest && config.markAsReadAfterForward && sender.isNotBlank()) {
                    markSmsAsRead(applicationContext, threadId, sender)
                }
                Log.d(TAG, "result: success")
                Result.success()
            }
            failures.isEmpty() -> {
                if (historyRecordId.isNotBlank()) {
                    history.markSkipped(historyRecordId, skippedReasons.joinToString("；")
                        .ifBlank { "渠道已关闭、规则未允许或发送条件未满足" })
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
                val detail = if (successes.isNotEmpty()) {
                    "部分通道已提交（${successes.joinToString("、")}），为避免重复投递不自动重试；失败：${failures.joinToString("；")}"
                } else failures.joinToString("；")
                if (historyRecordId.isNotBlank()) history.markFailed(historyRecordId, detail)
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
        host: String, port: Int, security: Int, user: String, password: String,
        recipientsText: String, subject: String, content: String
    ) = SmtpSender.send(host, port, security, user, password, recipientsText, subject, content)

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
        val connection = URL("$base/${NtfyProtocol.requireTopic(topic)}").openConnection() as HttpURLConnection
        connection.withDisconnect {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 12_000
            doOutput = true
            setRequestProperty("Content-Type", "text/plain; charset=utf-8")
            setRequestProperty("Title", NtfyProtocol.titleHeader(title))
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

    private fun sendServerChan3(
        sendKey: String,
        tags: String,
        title: String,
        content: String
    ) {
        val trimmedKey = sendKey.trim()
        val url = if (trimmedKey.startsWith("http://") || trimmedKey.startsWith("https://")) {
            trimmedKey
        } else {
            val match = Regex("""^sctp(\d+)t""").find(trimmedKey)
            val uid = match?.groupValues?.get(1)
            if (uid != null) {
                "https://$uid.push.ft07.com/send/$trimmedKey.send"
            } else {
                "https://push.ft07.com/send/$trimmedKey.send"
            }
        }
        val payload = JSONObject()
            .put("title", title)
            .put("desp", content)
        if (tags.isNotBlank()) {
            payload.put("tags", tags.trim())
        }
        val res = postJson(url, payload)
        ServerChan3Protocol.requireAccepted(res)
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
        return internet && validated
    }

    private fun sendPushPlus(token: String, topic: String, title: String, content: String) {
        require(token.isNotBlank()) { "PushPlus Token 不能为空" }
        val payload = PushPlusPayload.create(token, topic, title, content)
        val result = postJson("https://www.pushplus.plus/send", payload)
        PushPlusPayload.requireAccepted(result)
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

    private fun sendQq(
        webhookOrKey: String,
        type: String,
        title: String,
        content: String,
        accessToken: String = "",
        targetType: String = "private",
        targetId: String = ""
    ) {
        require(webhookOrKey.isNotBlank()) { "QQ 消息配置不能为空" }
        val text = "【$title】\n$content"
        if (type == "qmsg" || !webhookOrKey.startsWith("http")) {
            QmsgSender.send(webhookOrKey, text)
        } else {
            require(targetId.isNotBlank()) { "OneBot 11 必须配置 user_id 或 group_id" }
            val isGroup = targetType == "group"
            val action = if (isGroup) "send_group_msg" else "send_private_msg"
            val idField = if (isGroup) "group_id" else "user_id"
            val baseUrl = webhookOrKey.trim().trimEnd('/')
                .removeSuffix("/send_private_msg")
                .removeSuffix("/send_group_msg")
            val payload = JSONObject()
                .put(idField, targetId.toLongOrNull() ?: targetId)
                .put("message", text)
                .put("auto_escape", false)
            val headers = if (accessToken.isBlank()) emptyMap() else {
                mapOf("Authorization" to "Bearer ${accessToken.trim()}")
            }
            val response = postJson("$baseUrl/$action", payload, headers)
            check(response.optInt("retcode", -1) == 0 && response.optString("status") == "ok") {
                response.optString("message").ifBlank { response.optString("wording", "OneBot 11 发送失败") }
            }
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
        subscriptionId: Int,
        successMode: String = "http",
        successField: String = "",
        successValue: String = "",
    ) {
        val successRule = WebhookSuccessRule(successMode, successField, successValue)
        successRule.validate()
        require(url.isNotBlank()) { "自定义 Webhook URL 不能为空" }
        val normalizedUrl = url.trim()
        ForwardingUrlPolicy.requireAllowed(normalizedUrl, normalizedUrl.startsWith("http://", ignoreCase = true))
        val method = methodValue.trim().uppercase().ifBlank { "POST" }
        require(method in setOf("GET", "POST", "PUT")) { "请求方式仅支持 GET、POST 或 PUT" }
        val contentType = contentTypeValue.trim().ifBlank { "application/json" }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(receivedAt))
        val sim = if (subscriptionId >= 0) {
            receivedSimSnapshot()?.let(ForwardingMessageFormatter::describeSim) ?: ForwardingMessageFormatter.getSimDescription(applicationContext, MultiForwardConfig(applicationContext), subscriptionId)
        } else ""
        val simSlot = if (subscriptionId >= 0) {
            receivedSimSnapshot()?.let { if (it.slotIndex >= 0) "SIM${it.slotIndex + 1}" else "未知接收卡" }
                ?: ForwardingMessageFormatter.getSimSlotName(applicationContext, MultiForwardConfig(applicationContext), subscriptionId)
        } else ""
        val receiver = if (subscriptionId >= 0) {
            receivedSimSnapshot()?.receiverNumber ?: ForwardingMessageFormatter.getReceiverNumber(applicationContext, MultiForwardConfig(applicationContext), subscriptionId)
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
                "from" to encoded(sender), "time" to encoded(time),
                "sim" to encoded(sim), "sim_slot" to encoded(simSlot),
                "receiver" to encoded(receiver)
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
            successRule.requireAccepted(statusCode, response)
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
        smsDirectAttempted = true
        require(phone.isNotBlank()) { "目标手机号不能为空" }
        val normalized = phone.trim()
        require(normalized.isNotEmpty()) { "目标手机号格式无效" }
        val recordId = inputData.getString(KEY_HISTORY_RECORD_ID).orEmpty()
        val pureSms = inputData.getString(KEY_TARGET_CHANNEL) == ForwardingChannels.SMS_DIRECT
        val preparing = pureSms && SmsDirectBillingPolicy.canResumePreparation(ForwardingHistoryStore(applicationContext).smsPhase(recordId))
        if ((runAttemptCount > 0 || smsDirectPreviouslyStarted) && !preparing) throw SmsDirectOutcomeUnknown("这是系统重新执行的短信直发任务，先前结果未确认；已阻止自动重发")
        if (SmsDirectBillingPolicy.isUnidentifiedLegacyDegradedSource(smsDirectSource))
            throw SmsDirectOutcomeUnknown("旧降级任务无法确认原短信身份，已阻止收费发送，请先核对收件端")
        require(isTest || !SmsDirectBillingPolicy.isEcho(inputData.getString(KEY_SENDER).orEmpty(), normalized)) {
            "原发件人与短信直发目标相同，已拦截回传，避免循环扣费"
        }
        val triggerType = if (isTest) {
            org.fossify.messages.models.SmsSendTriggerType.SMS_DIRECT_TEST
        } else {
            org.fossify.messages.models.SmsSendTriggerType.FORWARDING_SMS_DIRECT
        }
        SmsDirectSendGuard.sendPrepared(applicationContext, smsDirectSource, normalized, recordId,
            receiptRecordId = recordId.takeIf { pureSms }.orEmpty()) { before ->
            applicationContext.sendMessageCompat(
                text = content,
                addresses = listOf(normalized),
                subId = receiveSubId.takeIf { it >= 0 },
                attachments = emptyList(),
                propagateErrors = true,
                triggerType = triggerType,
                beforeBillableSubmit = before
            )
        }
    }

    private fun markSmsAsRead(context: Context, threadId: Long, sender: String) {
        try {
            var targetThreadId = threadId
            if (targetThreadId <= 0L && sender.isNotBlank()) {
                targetThreadId = runCatching {
                    android.provider.Telephony.Threads.getOrCreateThreadId(context, sender)
                }.getOrDefault(0L)
            }
            if (targetThreadId > 0L) {
                context.notificationManager.cancel(targetThreadId.hashCode())
                context.markThreadMessagesRead(targetThreadId)
                context.conversationsDB.markRead(targetThreadId)
                refreshConversations()
                Log.d(TAG, "markSmsAsRead: thread $targetThreadId marked as read")
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to mark SMS as read for sender $sender, threadId $threadId", t)
        }
    }

    companion object {
        private const val KEY_GROUP_PLAN = "group_execution_plan"
        private const val TAG = "MultiChannelForward"
        private const val KEY_SENDER = "sender"
        private const val KEY_BODY = "body"
        private const val KEY_RECEIVED_AT = "received_at"
        private const val KEY_SUBSCRIPTION_ID = "subscription_id"
        private const val KEY_SIM_CAPTURED = "sim_captured"
        private const val KEY_SIM_SLOT = "received_sim_slot"
        private const val KEY_SIM_LABEL = "received_sim_label"
        private const val KEY_SIM_NUMBER = "received_sim_number"
        private const val KEY_SIM_CUSTOM = "received_sim_custom"
        private const val KEY_THREAD_ID = "thread_id"
        private const val KEY_TARGET_CHANNEL = "target_channel"
        private const val KEY_ALLOWED_CHANNELS = "allowed_channels"
        private const val KEY_IS_TEST = "is_test"
        private const val KEY_HISTORY_RECORD_ID = "history_record_id"
        private const val KEY_SMS_DIRECT_SOURCE = "sms_direct_source"
        private const val KEY_OPERATION_ID = "operation_id"
        const val KEY_INSTANCE_ID = "target_instance_id"
        const val KEY_RULE_ID = "rule_id"
        const val KEY_ACTION_ID = "action_id"
        private const val KEY_BODY_ALREADY_RENDERED = "body_already_rendered"
        private const val KEY_ALLOW_SIM_LABEL_PREFIX = "allow_sim_label_prefix"
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
            isTest: Boolean = false,
            operationId: String? = null,
            targetInstanceId: String = "",
            ruleId: String = "",
            actionId: String = "",
            bodyAlreadyRendered: Boolean = false,
            visitedGroupIds: Set<String> = emptySet(),
            threadId: Long = 0L,
            groupPlan: String = "",
            originalSim: ForwardingSimSnapshot? = null,
            allowSimLabelPrefix: Boolean = false,
            originalSmsDirectSource: String = "",
            retryOf: ForwardingHistoryRecord? = null,
        ) {
            val stableSmsDirectSource = originalSmsDirectSource.ifBlank {
                SmsDirectBillingPolicy.workerSource(uniqueId, receivedAt, uniqueId)
            }
            val multiConfig = MultiForwardConfig(context)
            val requestedInstance = targetInstanceId.takeIf(String::isNotBlank)
                ?.let(multiConfig::getChannelInstanceById)
            val isManualRetry = uniqueId.startsWith("manual-retry-")
            require(!isManualRetry || (targetChannel != ForwardingChannels.CHANNEL_GROUP &&
                requestedInstance?.channelType != ForwardingChannels.CHANNEL_GROUP)) { "手动重试不能展开通道组" }
            if (targetChannel == ForwardingChannels.CHANNEL_GROUP || requestedInstance?.channelType == ForwardingChannels.CHANNEL_GROUP) {
                val groupIdentity = targetInstanceId.ifBlank { ForwardingChannels.CHANNEL_GROUP }
                if (requestedInstance != null && !requestedInstance.enabled) return
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
                val members = (if (requestedInstance != null) instanceMembers else multiConfig.channelGroupMembers())
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
                val mode = requestedInstance?.optString("executionMode", GroupExecutionPolicy.ALL) ?: GroupExecutionPolicy.ALL
                if (mode != GroupExecutionPolicy.ALL) {
                    require(mode in setOf(GroupExecutionPolicy.UNTIL_SUCCESS, GroupExecutionPolicy.UNTIL_FAIL)) { "未知通道组执行策略" }
                    val selected = members.map { id ->
                        multiConfig.getChannelInstanceById(id) ?: error("顺序通道组仅支持已配置的通道实例")
                    }
                    require(selected.size <= 16 && selected.none { it.channelType == ForwardingChannels.CHANNEL_GROUP }) {
                        "顺序通道组最多16个成员，暂不支持嵌套组"
                    }
                    val first = selected.first()
                    val plan = JSONObject().put("run", uniqueId).put("mode", mode).put("index", 0).put("groupId", groupIdentity)
                        .put("members", org.json.JSONArray(selected.map { it.id }))
                        .put("types", org.json.JSONArray(selected.map { it.channelType }))
                    enqueueSingle(context, sender, body, receivedAt, subscriptionId,
                        "$uniqueId-step-0", first.channelType, setOf(first.id), isTest, operationId,
                        first.id, ruleId, actionId, bodyAlreadyRendered, visitedGroupIds + groupIdentity,
                        threadId, plan.toString(), originalSim, allowSimLabelPrefix, stableSmsDirectSource)
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
                        visitedGroupIds = visitedGroupIds + groupIdentity,
                        threadId = threadId,
                        groupPlan = if (requestedInstance != null) JSONObject()
                            .put("mode", "parallel").put("groupId", groupIdentity).toString() else "",
                        originalSim = originalSim,
                        allowSimLabelPrefix = allowSimLabelPrefix,
                        originalSmsDirectSource = stableSmsDirectSource,
                    )
                }
                return
            }
            val history = ForwardingHistoryStore(context)
            val effectiveChannel = if (targetInstanceId.isNotBlank()) "instance_$targetInstanceId" else targetChannel
            val constraintChannel = if (targetInstanceId.isNotBlank()) {
                MultiForwardConfig(context).getChannelInstanceById(targetInstanceId)?.channelType ?: targetChannel
            } else targetChannel
            if (groupPlan.isNotBlank()) {
                val executionStatus = history.groupStatus(ForwardingHistoryStore.recordId(uniqueId, effectiveChannel))
                if (executionStatus in setOf(ForwardingHistoryStore.STATUS_RUNNING,
                    ForwardingHistoryStore.STATUS_SUCCESS, ForwardingHistoryStore.STATUS_FAILED,
                    ForwardingHistoryStore.STATUS_SKIPPED, ForwardingHistoryStore.STATUS_UNKNOWN)) return
                val existing = history.record(ForwardingHistoryStore.recordId(uniqueId, effectiveChannel))
                if (existing?.status in setOf(ForwardingHistoryStore.STATUS_SUCCESS, ForwardingHistoryStore.STATUS_FAILED,
                    ForwardingHistoryStore.STATUS_SKIPPED, ForwardingHistoryStore.STATUS_UNKNOWN)) return
            }
            val historyRecordId = (if (groupPlan.isNotBlank()) history.record(ForwardingHistoryStore.recordId(uniqueId, effectiveChannel))?.recordId else null) ?: history.registerQueued(
                workId = uniqueId,
                channel = effectiveChannel,
                sender = sender,
                body = body,
                receivedAt = receivedAt,
                subscriptionId = subscriptionId,
                isTest = isTest,
            )
            // Existing delivery owners keep their state; duplicate events must not reopen a finished task.
            if (history.record(historyRecordId)?.let { it.status != ForwardingHistoryStore.STATUS_QUEUED || it.attempts > 0 } == true) return
            Log.d(TAG, "enqueueSingle: channel=$targetChannel, instance=$targetInstanceId, historyId=$historyRecordId, workId=$uniqueId")
            val safeBody = WorkPayloadText.fitUtf8(body, 6000)
            val delaySeconds = if (!isTest) multiConfig.forwardingDelaySeconds else 0
            val capturedSim = originalSim ?: ForwardingMessageFormatter.captureSim(context, subscriptionId)
            val retryPayload = JSONObject()
                .put("sender", sender.take(256)).put("body", safeBody).put("receivedAt", receivedAt)
                .put("subscriptionId", subscriptionId).put("channel", targetChannel)
                .put("instanceId", targetInstanceId).put("ruleId", ruleId).put("actionId", actionId)
                .put("bodyAlreadyRendered", bodyAlreadyRendered).put("threadId", threadId)
                .put("allowSimLabelPrefix", allowSimLabelPrefix)
                .put("smsDirectSource", stableSmsDirectSource)
                .put("slot", capturedSim.slotIndex).put("sim", capturedSim.description)
                .put("receiver", capturedSim.receiverNumber).put("label", capturedSim.customLabel)
            val requestData = runCatching {
                workDataOf(
                    KEY_SENDER to sender.take(256),
                    KEY_BODY to safeBody,
                    KEY_RECEIVED_AT to receivedAt,
                    KEY_SUBSCRIPTION_ID to subscriptionId,
                    KEY_SIM_CAPTURED to true,
                    KEY_SIM_SLOT to capturedSim.slotIndex,
                    KEY_SIM_LABEL to capturedSim.description.take(100),
                    KEY_SIM_NUMBER to capturedSim.receiverNumber.take(64),
                    KEY_SIM_CUSTOM to capturedSim.customLabel.take(100),
                    KEY_TARGET_CHANNEL to targetChannel,
                    KEY_ALLOWED_CHANNELS to encodeRuleAllowedChannels(allowedChannels),
                    KEY_IS_TEST to isTest,
                    KEY_HISTORY_RECORD_ID to historyRecordId,
                    KEY_SMS_DIRECT_SOURCE to stableSmsDirectSource,
                    "manual_retry" to isManualRetry,
                    KEY_OPERATION_ID to operationId,
                    KEY_INSTANCE_ID to targetInstanceId,
                    KEY_RULE_ID to ruleId,
                    KEY_ACTION_ID to actionId,
                    KEY_BODY_ALREADY_RENDERED to bodyAlreadyRendered,
                    KEY_ALLOW_SIM_LABEL_PREFIX to allowSimLabelPrefix,
                    KEY_THREAD_ID to threadId,
                    KEY_GROUP_PLAN to groupPlan,
                )
            }.getOrElse { error ->
                history.markFailed(historyRecordId, "发送任务数据构建失败：${error.message ?: error.javaClass.simpleName}")
                return
            }
            val requestBuilder = OneTimeWorkRequestBuilder<MultiChannelForwardWorker>()
                .addTag("forwarding-history:$historyRecordId")
                .setInputData(requestData)
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

            if (delaySeconds > 0) {
                requestBuilder.setInitialDelay(delaySeconds.toLong(), TimeUnit.SECONDS)
            } else {
                requestBuilder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            val request = requestBuilder.build()
            ForwardingWorkScheduler.enqueue(context, "multi-forward-$uniqueId-$effectiveChannel", request, historyRecordId,
                input = requestData, retryPayload = retryPayload, notBefore = System.currentTimeMillis() + delaySeconds * 1000L,
                repairAllowed = !isTest && groupPlan.isBlank() && constraintChannel in ForwardingChannels.networkChannels,
                retryOf = retryOf)
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
