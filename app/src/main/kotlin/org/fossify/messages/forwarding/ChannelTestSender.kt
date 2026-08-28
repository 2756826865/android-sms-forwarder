package org.fossify.messages.forwarding

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.messages.messaging.sendMessageCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object ChannelTestSender {
    suspend fun sendTest(context: Context, channelId: String): Result<String> = withContext(Dispatchers.IO) {
        val config = MultiForwardConfig(context)
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val title = "【SMS Forwarder 测试通知】"
        val content = "这是一条来自 SMS Forwarder 的测试消息\n发送时间: $now\n如果您收到此消息，说明该通道已成功打通！"

        runCatching {
            when (channelId) {
                ForwardingChannels.PUSHPLUS -> {
                    val token = config.pushPlusToken()
                    require(token.isNotBlank()) { "PushPlus Token 不能为空，请先配置" }
                    val payload = JSONObject()
                        .put("token", token)
                        .put("title", title)
                        .put("content", content.replace("\n", "<br/>"))
                        .put("template", "html")
                    val topic = config.pushPlusTopic()
                    if (topic.isNotBlank()) payload.put("topic", topic)
                    val res = postJson("https://www.pushplus.plus/send", payload)
                    check(res.optInt("code", -1) == 200) { res.optString("msg", "PushPlus 响应错误") }
                    "PushPlus 微信推送成功！"
                }
                ForwardingChannels.WECHAT_TEST -> {
                    val appId = config.wechatTestAppId()
                    val appSecret = config.wechatTestAppSecret()
                    val templateId = config.wechatTestTemplateId()
                    val openId = config.wechatTestOpenId()
                    require(appId.isNotBlank() && appSecret.isNotBlank() && templateId.isNotBlank() && openId.isNotBlank()) {
                        "微信测试号配置不完整，请先配置"
                    }
                    val tokenUrl = "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=${URLEncoder.encode(appId, "UTF-8")}&secret=${URLEncoder.encode(appSecret, "UTF-8")}"
                    val tokenRes = getJson(tokenUrl)
                    val token = tokenRes.optString("access_token")
                    check(token.isNotBlank()) { tokenRes.optString("errmsg", "获取微信 Token 失败") }

                    val dataObj = JSONObject()
                        .put("title", JSONObject().put("value", title))
                        .put("content", JSONObject().put("value", content))
                        .put("time", JSONObject().put("value", now))
                    val sendPayload = JSONObject()
                        .put("touser", openId)
                        .put("template_id", templateId)
                        .put("data", dataObj)
                    val sendRes = postJson("https://api.weixin.qq.com/cgi-bin/message/template/send?access_token=$token", sendPayload)
                    check(sendRes.optInt("errcode", -1) == 0) { sendRes.optString("errmsg", "微信测试号模板发送失败") }
                    "微信测试号模板消息推送成功！"
                }
                ForwardingChannels.QQ -> {
                    val target = config.qqWebhook()
                    val type = config.qqType()
                    require(target.isNotBlank()) { "QQ 消息配置不能为空，请先配置" }
                    val text = "$title\n$content"
                    if (type == "qmsg" || !target.startsWith("http")) {
                        postJson("https://qmsg.zendee.cn/send/$target", JSONObject().put("msg", text))
                    } else {
                        postJson(target, JSONObject().put("message", text))
                    }
                    "QQ 消息已成功推送！"
                }
                ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> {
                    val corpId = config.weComCorpId()
                    val agentId = config.weComAgentId()
                    val secret = config.weComSecret()
                    val toUser = config.weComToUser()
                    require(corpId.isNotBlank() && agentId.isNotBlank() && secret.isNotBlank() && toUser.isNotBlank()) {
                        "企业微信应用号配置不完整，请先配置"
                    }
                    val tokenUrl = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=${URLEncoder.encode(corpId, "UTF-8")}&corpsecret=${URLEncoder.encode(secret, "UTF-8")}"
                    val tokenRes = getJson(tokenUrl)
                    val token = tokenRes.optString("access_token")
                    check(token.isNotBlank()) { tokenRes.optString("errmsg", "获取企微 Token 失败") }

                    val sendPayload = JSONObject()
                        .put("touser", toUser)
                        .put("msgtype", "text")
                        .put("agentid", agentId.toLong())
                        .put("text", JSONObject().put("content", "$title\n$content"))
                        .put("safe", 0)
                    val sendRes = postJson("https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=$token", sendPayload)
                    check(sendRes.optInt("errcode", -1) == 0) { sendRes.optString("errmsg", "企业微信发送失败") }
                    "企业微信应用号消息推送成功！"
                }
                ForwardingChannels.WECOM_BOT -> {
                    val webhook = config.weComBotWebhook()
                    require(webhook.isNotBlank()) { "企业微信群机器人 Webhook 不能为空，请先配置" }
                    val payload = JSONObject()
                        .put("msgtype", "text")
                        .put("text", JSONObject().put("content", "$title\n$content"))
                    val res = postJson(webhook, payload)
                    check(res.optInt("errcode", -1) == 0) { res.optString("errmsg", "企微群机器人响应失败") }
                    "企业微信群机器人推送成功！"
                }
                ForwardingChannels.FEISHU_APP -> {
                    val appId = config.feishuAppId()
                    val appSecret = config.feishuAppSecret()
                    val receiveId = config.feishuReceiveId()
                    require(appId.isNotBlank() && appSecret.isNotBlank() && receiveId.isNotBlank()) {
                        "飞书自建应用配置不完整，请先配置"
                    }
                    val authUrl = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal"
                    val authRes = postJson(authUrl, JSONObject().put("app_id", appId).put("app_secret", appSecret))
                    val token = authRes.optString("tenant_access_token")
                    check(token.isNotBlank()) { authRes.optString("msg", "获取飞书 Token 失败") }

                    val msgUrl = "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=open_id"
                    val sendPayload = JSONObject()
                        .put("receive_id", receiveId)
                        .put("msg_type", "text")
                        .put("content", JSONObject().put("text", "$title\n$content").toString())
                    val res = postJson(msgUrl, sendPayload, mapOf("Authorization" to "Bearer $token"))
                    check(res.optInt("code", -1) == 0 || res.has("data")) { res.optString("msg", "飞书发送失败") }
                    "飞书自建应用消息推送成功！"
                }
                ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT -> {
                    val webhook = config.feishuWebhook()
                    val secret = config.feishuSecret()
                    require(webhook.isNotBlank()) { "飞书群机器人 Webhook 不能为空，请先配置" }
                    val payload = JSONObject()
                        .put("msg_type", "text")
                        .put("content", JSONObject().put("text", "$title\n$content"))
                    if (secret.isNotBlank()) {
                        val timestamp = System.currentTimeMillis() / 1000
                        val stringToSign = "$timestamp\n$secret"
                        val mac = Mac.getInstance("HmacSHA256")
                        mac.init(SecretKeySpec(stringToSign.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
                        val sign = Base64.encodeToString(mac.doFinal(ByteArray(0)), Base64.NO_WRAP)
                        payload.put("timestamp", timestamp.toString()).put("sign", sign)
                    }
                    val res = postJson(webhook, payload)
                    val code = if (res.has("StatusCode")) res.optInt("StatusCode", -1) else res.optInt("code", -1)
                    check(code == 0) { res.optString("msg", res.optString("StatusMessage", "飞书群机器人拒绝请求")) }
                    "飞书群机器人推送成功！"
                }
                ForwardingChannels.DINGTALK -> {
                    val webhook = config.dingTalkWebhook()
                    val secret = config.dingTalkSecret()
                    require(webhook.isNotBlank()) { "钉钉群机器人 Webhook 不能为空，请先配置" }
                    val timestamp = System.currentTimeMillis()
                    val signedUrl = if (secret.isBlank()) webhook else {
                        val mac = Mac.getInstance("HmacSHA256")
                        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
                        val signData = "$timestamp\n$secret".toByteArray(StandardCharsets.UTF_8)
                        val sign = Base64.encodeToString(mac.doFinal(signData), Base64.NO_WRAP)
                        val sep = if (webhook.contains('?')) '&' else '?'
                        "$webhook${sep}timestamp=$timestamp&sign=${URLEncoder.encode(sign, "UTF-8")}"
                    }
                    val payload = JSONObject()
                        .put("msgtype", "text")
                        .put("text", JSONObject().put("content", "$title\n$content"))
                    val res = postJson(signedUrl, payload)
                    check(res.optInt("errcode", -1) == 0) { res.optString("errmsg", "钉钉群机器人拒绝请求") }
                    "钉钉群机器人推送成功！"
                }
                ForwardingChannels.BARK -> {
                    val server = config.barkServerUrl()
                    val key = config.barkDeviceKey()
                    require(key.isNotBlank()) { "Bark DeviceKey 不能为空，请先配置" }
                    val url = "${server.trimEnd('/')}/$key/${URLEncoder.encode(title, "UTF-8")}/${URLEncoder.encode(content, "UTF-8")}"
                    getJson(url)
                    "Bark 消息已推送至苹果 APNs！"
                }
                ForwardingChannels.TELEGRAM -> {
                    val token = config.telegramBotToken()
                    val chatId = config.telegramChatId()
                    require(token.isNotBlank() && chatId.isNotBlank()) { "Telegram 配置不完整，请先配置" }
                    val url = "https://api.telegram.org/bot$token/sendMessage"
                    val payload = JSONObject().put("chat_id", chatId).put("text", "$title\n\n$content")
                    val res = postJson(url, payload)
                    check(res.optBoolean("ok", false)) { res.optString("description", "Telegram 发送失败") }
                    "Telegram 机器人消息推送成功！"
                }
                ForwardingChannels.DISCORD -> {
                    val webhook = config.discordWebhook()
                    require(webhook.isNotBlank()) { "Discord Webhook 不能为空，请先配置" }
                    val embed = JSONObject().put("title", title).put("description", content).put("color", 5814783)
                    val payload = JSONObject().put("embeds", org.json.JSONArray().put(embed))
                    postJson(webhook, payload)
                    "Discord 频道消息推送成功！"
                }
                ForwardingChannels.TENCENT_CLOUD -> {
                    val webhook = config.tencentCloudWebhook()
                    require(webhook.isNotBlank()) { "腾讯云告警 Webhook 不能为空，请先配置" }
                    postJson(webhook, JSONObject().put("text", "$title\n$content"))
                    "腾讯云自定义告警触发成功！"
                }
                ForwardingChannels.CUSTOM_WEBHOOK -> {
                    val url = config.customWebhookUrl()
                    require(url.isNotBlank()) { "自定义 Webhook URL 不能为空，请先配置" }
                    postJson(url, JSONObject().put("title", title).put("content", content))
                    "自定义 Webhook 请求成功送达！"
                }
                ForwardingChannels.SMS_DIRECT -> {
                    val phone = config.smsDirectPhone()
                    require(phone.isNotBlank()) { "短信直发目标号码不能为空，请先配置" }
                    context.sendMessageCompat(
                        text = "$title $content",
                        addresses = listOf(phone),
                        subId = null,
                        attachments = emptyList(),
                        propagateErrors = true,
                        triggerType = org.fossify.messages.models.SmsSendTriggerType.SMS_DIRECT_TEST
                    )
                    "测试短信已通过本机 SIM 卡发送！"
                }
                ForwardingChannels.CHANNEL_GROUP -> {
                    val members = config.channelGroupMembers()
                    require(members.isNotEmpty()) { "群组中尚未添加任何通道成员，请先点击「⚙️ 配置」选择成员" }
                    val results = mutableListOf<String>()
                    members.forEach { memberId ->
                        val subRes = sendTest(context, memberId)
                        if (subRes.isSuccess) {
                            results.add("✅ ${ForwardingChannels.displayName(memberId)}")
                        } else {
                            results.add("❌ ${ForwardingChannels.displayName(memberId)}: ${subRes.exceptionOrNull()?.message}")
                        }
                    }
                    "群组分发完成:\n" + results.joinToString("\n")
                }
                else -> "通道测试已完成"
            }
        }
    }

    private fun postJson(urlString: String, payload: JSONObject, headers: Map<String, String> = emptyMap()): JSONObject {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000
            readTimeout = 8000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { it.write(payload.toString()) }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return runCatching { JSONObject(response) }.getOrDefault(JSONObject().put("code", code).put("raw", response))
    }

    private fun getJson(urlString: String): JSONObject {
        val url = URL(urlString)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Accept", "application/json")
        }
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
        val response = stream?.bufferedReader(StandardCharsets.UTF_8)?.use { it.readText() }.orEmpty()
        conn.disconnect()
        return runCatching { JSONObject(response) }.getOrDefault(JSONObject().put("code", code).put("raw", response))
    }
}
