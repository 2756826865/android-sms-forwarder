package org.fossify.messages.forwarding

import android.content.Context
import android.util.Base64
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
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class MultiChannelForwardWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = MultiForwardConfig(applicationContext)
        val targetChannel = inputData.getString(KEY_TARGET_CHANNEL).orEmpty()
        val isTest = inputData.getBoolean(KEY_IS_TEST, false)
        val history = ForwardingHistoryStore(applicationContext)
        val historyRecordId = inputData.getString(KEY_HISTORY_RECORD_ID).orEmpty()
        if (historyRecordId.isNotBlank()) history.markRunning(historyRecordId)
        if (targetChannel.isBlank() && !config.anyEnabled()) return@withContext Result.success()

        val ruleAllowedChannels = decodeRuleAllowedChannels(
            inputData.getString(KEY_ALLOWED_CHANNELS).orEmpty(),
        )
        fun shouldRun(channel: String, enabled: Boolean): Boolean {
            if (!enabled && !(isTest && targetChannel == channel)) return false
            if (ruleAllowedChannels != null && channel !in ruleAllowedChannels) return false
            return when (targetChannel) {
                "" -> true
                ForwardingChannels.ALL -> true
                else -> targetChannel == channel
            }
        }

        val sender = inputData.getString(KEY_SENDER).orEmpty()
        val body = inputData.getString(KEY_BODY).orEmpty()
        val receivedAt = inputData.getLong(KEY_RECEIVED_AT, System.currentTimeMillis())
        val subscriptionId = inputData.getInt(KEY_SUBSCRIPTION_ID, -1)
        val payload = ForwardingMessageFormatter.format(
            context = applicationContext,
            sender = sender,
            body = body,
            receivedAt = receivedAt,
            subscriptionId = subscriptionId,
        )
        val title = payload.title
        val content = payload.content

        val successes = mutableListOf<String>()
        val failures = mutableListOf<String>()

        suspend fun runChannel(name: String, action: suspend () -> Unit) {
            runCatching { action() }
                .onSuccess { successes += name }
                .onFailure { failures += "$name：${it.message ?: it.javaClass.simpleName}" }
        }

        // 检测网络状态
        val networkAvailable = isNetworkAvailable()
        val onlyOnNoNetwork = config.smsDirectOnlyOnNoNetwork
        if (targetChannel in ForwardingChannels.networkChannels && !networkAvailable) {
            if (historyRecordId.isNotBlank()) history.markRetry(historyRecordId, "网络连接中断，等待重试")
            return@withContext Result.retry()
        }

        // 短信直发逻辑
        if (shouldRun(ForwardingChannels.SMS_DIRECT, config.smsDirectEnabled)) {
            if (onlyOnNoNetwork) {
                // 仅断网时发送模式
                if (!networkAvailable) {
                    runChannel("短信直发") {
                        sendSmsDirect(config.smsDirectPhone(), content, subscriptionId)
                    }
                }
            } else {
                // 始终发送模式
                runChannel("短信直发") {
                    sendSmsDirect(config.smsDirectPhone(), content, subscriptionId)
                }
            }
        }

        // 网络可用时发送其他渠道
        if (networkAvailable) {
            if (shouldRun(ForwardingChannels.DINGTALK, config.dingTalkEnabled)) runChannel("钉钉") {
                sendDingTalk(config.dingTalkWebhook(), config.dingTalkSecret(), content)
            }
            if (shouldRun(ForwardingChannels.FEISHU, config.feishuEnabled)) runChannel("飞书") {
                sendFeishu(config.feishuWebhook(), config.feishuSecret(), content)
            }
            if (shouldRun(ForwardingChannels.WECOM, config.weComEnabled)) runChannel("企业微信") {
                sendWeCom(
                    config.weComCorpId(),
                    config.weComAgentId(),
                    config.weComSecret(),
                    config.weComToUser(),
                    content
                )
            }
            if (shouldRun(ForwardingChannels.WECOM_BOT, config.weComBotEnabled)) runChannel("企业微信群机器人") {
                sendWeComBot(config.weComBotWebhook(), content)
            }
            if (shouldRun(ForwardingChannels.EMAIL, config.emailEnabled)) runChannel("邮箱") {
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
            if (shouldRun(ForwardingChannels.BARK, config.barkEnabled)) runChannel("Bark") {
                sendBark(config.barkServerUrl(), config.barkDeviceKey(), title, content, config.barkAllowHttp)
            }
            if (shouldRun(ForwardingChannels.GOTIFY, config.gotifyEnabled)) runChannel("Gotify") {
                sendGotify(config.gotifyServerUrl(), config.gotifyToken(), title, content, config.gotifyAllowHttp)
            }
        }

        val now = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        config.lastStatus = buildString {
            append(now)
            if (successes.isNotEmpty()) append(" 成功：${successes.joinToString("、")}")
            if (failures.isNotEmpty()) append(" 失败：${failures.joinToString("；")}")
        }

        when {
            failures.isEmpty() && successes.isNotEmpty() -> {
                if (historyRecordId.isNotBlank()) {
                    history.markSuccess(historyRecordId, "发送成功：${successes.joinToString("、")}")
                }
                Result.success()
            }
            failures.isEmpty() -> {
                if (historyRecordId.isNotBlank()) {
                    history.markSkipped(historyRecordId, "渠道已关闭、规则未允许或发送条件未满足")
                }
                Result.success()
            }
            runAttemptCount < 2 -> {
                if (historyRecordId.isNotBlank()) history.markRetry(historyRecordId, failures.joinToString("；"))
                Result.retry()
            }
            else -> {
                if (historyRecordId.isNotBlank()) history.markFailed(historyRecordId, failures.joinToString("；"))
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
                .put("agentid", agentId.toLong())
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

    private fun getJson(url: String) = requestJson(url, "GET", null)

    private fun postJson(url: String, payload: JSONObject) = requestJson(url, "POST", payload)

    private fun requestJson(url: String, method: String, payload: JSONObject?): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        return connection.run {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
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
            disconnect()
            check(statusCode in 200..299) {
                val detail = runCatching {
                    JSONObject(response).optString("message")
                }.getOrDefault("").ifBlank { response.take(200) }
                "HTTP $statusCode${detail.takeIf(String::isNotBlank)?.let { ": $it" }.orEmpty()}"
            }
            check(response.isNotBlank()) { "接口返回空响应" }
            JSONObject(response)
        }
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun sendSmsDirect(phone: String, content: String, receiveSubId: Int) {
        require(phone.isNotBlank()) { "目标手机号不能为空" }
        val normalized = phone.trim()
        require(normalized.isNotEmpty()) { "目标手机号格式无效" }
        applicationContext.sendMessageCompat(
            text = content,
            addresses = listOf(normalized),
            subId = receiveSubId.takeIf { it >= 0 },
            attachments = emptyList(),
            propagateErrors = true,
        )
    }

    companion object {
        private const val KEY_SENDER = "sender"
        private const val KEY_BODY = "body"
        private const val KEY_RECEIVED_AT = "received_at"
        private const val KEY_SUBSCRIPTION_ID = "subscription_id"
        private const val KEY_TARGET_CHANNEL = "target_channel"
        private const val KEY_ALLOWED_CHANNELS = "allowed_channels"
        private const val KEY_IS_TEST = "is_test"
        private const val KEY_HISTORY_RECORD_ID = "history_record_id"
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
        ) {
            if (targetChannel.isBlank()) {
                val enabledChannels = MultiForwardConfig(context).enabledChannelIds()
                val selectedChannels = allowedChannels
                    ?.let(enabledChannels::intersect)
                    ?: enabledChannels
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
            )
        }

        private fun enqueueSingle(
            context: Context,
            sender: String,
            body: String,
            receivedAt: Long,
            subscriptionId: Int,
            uniqueId: String,
            targetChannel: String,
            allowedChannels: Set<String>?,
            isTest: Boolean,
        ) {
            val historyRecordId = ForwardingHistoryStore(context).registerQueued(
                workId = uniqueId,
                channel = targetChannel,
                sender = sender,
                body = body,
                receivedAt = receivedAt,
                subscriptionId = subscriptionId,
                isTest = isTest,
            )
            val request = OneTimeWorkRequestBuilder<MultiChannelForwardWorker>()
                .setInputData(
                    workDataOf(
                        KEY_SENDER to sender,
                        KEY_BODY to body,
                        KEY_RECEIVED_AT to receivedAt,
                        KEY_SUBSCRIPTION_ID to subscriptionId,
                        KEY_TARGET_CHANNEL to targetChannel,
                        KEY_ALLOWED_CHANNELS to encodeRuleAllowedChannels(allowedChannels),
                        KEY_IS_TEST to isTest,
                        KEY_HISTORY_RECORD_ID to historyRecordId,
                    )
                )
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(
                            if (targetChannel in ForwardingChannels.networkChannels) {
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
            WorkManager.getInstance(context)
                .enqueueUniqueWork("multi-forward-$uniqueId-$targetChannel", ExistingWorkPolicy.KEEP, request)
        }

        fun enqueueTest(context: Context, channel: String = "test", sender: String = "10086", body: String = "这是一条短信多渠道转发测试消息") {
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
