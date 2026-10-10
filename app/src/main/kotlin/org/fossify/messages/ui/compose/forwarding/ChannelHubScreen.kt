package org.fossify.messages.ui.compose.forwarding

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Slider
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.res.painterResource
import org.fossify.messages.R
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.fossify.messages.forwarding.ChannelTestSender
import org.fossify.messages.forwarding.ForwardingChannelInstance
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.repository.ChannelRepository
import org.fossify.messages.remote.repository.RemoteSourceConnectionState
import org.fossify.messages.remote.repository.RemoteSourceInstance
import org.fossify.messages.remote.repository.RemoteSourceRepository
import org.fossify.messages.remote.repository.RemoteSourceType
import org.fossify.messages.security.root.RootEnhancementManager
import org.fossify.messages.security.shizuku.ShizukuEnhancementManager
import org.fossify.messages.ui.compose.rules.RuleStudioScreen
import org.fossify.messages.ui.compose.navigation.GatewayDockContentPadding
import org.fossify.messages.ui.compose.navigation.LocalGatewayBottomPadding
import org.fossify.messages.ui.compose.components.StatusBadge
import org.fossify.messages.ui.compose.theme.AppBackground
import org.fossify.messages.ui.compose.theme.BrandGreen
import org.fossify.messages.ui.compose.theme.BrandGreenSoft
import org.fossify.messages.ui.compose.theme.DarkBackground
import org.fossify.messages.ui.compose.theme.DarkOutline
import org.fossify.messages.ui.compose.theme.DarkSurface
import org.fossify.messages.ui.compose.theme.GatewayBlue
import org.fossify.messages.ui.compose.theme.GatewayGreen
import org.fossify.messages.ui.compose.theme.GatewayOrange
import org.fossify.messages.ui.compose.theme.GatewayPurple
import org.fossify.messages.ui.compose.theme.GatewayRed
import org.fossify.messages.ui.compose.theme.OutlineSoft
import org.fossify.messages.ui.compose.theme.SurfaceCard
import org.fossify.messages.ui.compose.theme.TextPrimary
import org.fossify.messages.ui.compose.theme.TextSecondary
import org.json.JSONObject
import java.util.UUID

enum class ChannelCategory(val title: String, val emoji: String) {
    ALL("全部", "🌐"),
    WECHAT("微信生态", "🟢"),
    WORK("办公协同", "🏢"),
    INSTANT("极客通讯", "⚡"),
    CLOUD("云与自定义", "☁️")
}

data class ChannelTypeDefinition(
    val type: String,
    val name: String,
    val description: String,
    val iconEmoji: String,
    val category: ChannelCategory
)

val ALL_CHANNEL_TYPE_DEFINITIONS = listOf(
    ChannelTypeDefinition(ForwardingChannels.WXPUSHER, "WxPusher 消息推送", "按 UID 或主题推送至已绑定设备", "📨", ChannelCategory.WECHAT),
    ChannelTypeDefinition(ForwardingChannels.PUSHPLUS, "PushPlus 微信推送", "微信服务号一对一或群组推送", "💬", ChannelCategory.WECHAT),
    ChannelTypeDefinition(ForwardingChannels.SERVERCHAN3, "Server酱³", "客户端与厂商通道通知推送", "🔔", ChannelCategory.WECHAT),
    ChannelTypeDefinition(ForwardingChannels.WECHAT_TEST, "微信测试号", "微信公众平台测试号模板消息直推", "🟢", ChannelCategory.WECHAT),
    ChannelTypeDefinition(ForwardingChannels.WECOM_BOT, "企业微信群机器人", "企业微信内部群 Webhook 机器人", "🤖", ChannelCategory.WECHAT),
    ChannelTypeDefinition(ForwardingChannels.WECOM_STREAM, "企业微信智能机器人 (长连接)", "通过官方长连接主动推送消息（免公网IP）", "💬", ChannelCategory.WECHAT),
    ChannelTypeDefinition(ForwardingChannels.WECOM_APP, "企业微信应用号", "企业微信自建应用 Agent 卡片消息", "💼", ChannelCategory.WECHAT),
    ChannelTypeDefinition(ForwardingChannels.DINGTALK, "钉钉群机器人", "钉钉群自定义机器人 Webhook + 加签", "🤖", ChannelCategory.WORK),
    ChannelTypeDefinition(ForwardingChannels.FEISHU_BOT, "飞书群机器人", "飞书群自定义机器人 Webhook + 加签", "🕊️", ChannelCategory.WORK),
    ChannelTypeDefinition(ForwardingChannels.FEISHU_APP, "飞书自建应用", "飞书开放平台企业自建应用", "🏢", ChannelCategory.WORK),
    ChannelTypeDefinition(ForwardingChannels.QQ, "QQ 消息 (Qmsg/OneBot/NapCat)", "支持 Qmsg 酱或 OneBot 11 / NapCat HTTP 推送", "🐧", ChannelCategory.INSTANT),
    ChannelTypeDefinition(ForwardingChannels.BARK, "Bark (iOS)", "苹果设备专属 APNs 极速低功耗推送", "🔔", ChannelCategory.INSTANT),
    ChannelTypeDefinition(ForwardingChannels.TELEGRAM, "Telegram 机器人", "Telegram Bot API 异步消息推送", "✈️", ChannelCategory.INSTANT),
    ChannelTypeDefinition(ForwardingChannels.DISCORD, "Discord 群机器人", "Discord Webhook 频道卡片推送", "🎮", ChannelCategory.INSTANT),
    ChannelTypeDefinition(ForwardingChannels.GOTIFY, "Gotify 消息推送", "自建 Gotify 服务即时推送", "🚀", ChannelCategory.INSTANT),
    ChannelTypeDefinition(ForwardingChannels.NTFY, "ntfy 推送", "支持官方或自建 ntfy 服务与独立 Topic", "📣", ChannelCategory.INSTANT),
    ChannelTypeDefinition(ForwardingChannels.WEBSOCKET, "WebSocket 客户端", "通过自建 WebSocket 或 HTTP 服务转发", "🔌", ChannelCategory.INSTANT),
    ChannelTypeDefinition(ForwardingChannels.EMAIL, "邮件消息 (SMTP)", "标准 SMTP 协议发信 (SSL/STARTTLS)", "📧", ChannelCategory.CLOUD),
    ChannelTypeDefinition(ForwardingChannels.TENCENT_CLOUD, "腾讯云自定义告警", "向配置的腾讯云告警 Webhook 提交消息", "☁️", ChannelCategory.CLOUD),
    ChannelTypeDefinition(ForwardingChannels.SMS_DIRECT, "短信直发 (SIM 转发)", "通过本机备用 SIM 卡向指定手机转发", "📱", ChannelCategory.CLOUD),
    ChannelTypeDefinition(ForwardingChannels.CUSTOM_WEBHOOK, "自定义 Webhook", "适配任意第三方 HTTP POST/GET 接口", "🌐", ChannelCategory.CLOUD)
)

fun getChannelTypeDefinition(type: String): ChannelTypeDefinition? =
    ALL_CHANNEL_TYPE_DEFINITIONS.firstOrNull { it.type == type }

private fun linkedDingTalkSourceId(channelInstanceId: String) = "linked-dingtalk-$channelInstanceId"

private fun splitAccessList(value: String): Set<String> = value
    .split(',', '\n', ';', '；', '，')
    .map(String::trim)
    .filter(String::isNotBlank)
    .toSet()

private fun syncLinkedDingTalkSource(context: Context, channel: ForwardingChannelInstance) {
    if (channel.channelType != ForwardingChannels.DINGTALK) return
    val clientId = channel.optString("clientId")
    val clientSecret = channel.optString("clientSecret")
    val repository = RemoteSourceRepository.getInstance(context)
    val sourceId = linkedDingTalkSourceId(channel.id)
    val existing = repository.getSourceById(sourceId)
    if (clientId.isBlank() || clientSecret.isBlank()) {
        if (existing != null) repository.deleteSource(sourceId)
        return
    }
    repository.saveSource(
        (existing ?: RemoteSourceInstance(
            id = sourceId,
            name = channel.name,
            type = RemoteSourceType.DINGTALK
        )).copy(
            name = channel.name,
            enabled = channel.enabled,
            connectionState = when {
                !channel.enabled -> RemoteSourceConnectionState.DISABLED
                existing == null || !existing.enabled -> RemoteSourceConnectionState.CONNECTING
                else -> existing.connectionState
            },
            customCommandPrefix = channel.optString("customCommandPrefix"),
            whitelistEnabled = channel.optBoolean("whitelistEnabled", false),
            authorizedUsers = splitAccessList(channel.optString("authorizedUsers")),
            authorizedGroups = splitAccessList(channel.optString("authorizedGroups")),
            requireMention = true,
            configJson = JSONObject()
                .put("clientId", clientId)
                .put("clientSecret", clientSecret)
                .put("linkedChannelInstanceId", channel.id)
                .toString()
        )
    )
}

private fun deleteLinkedDingTalkSource(context: Context, channel: ForwardingChannelInstance) {
    if (channel.channelType == ForwardingChannels.DINGTALK) {
        RemoteSourceRepository.getInstance(context).deleteSource(linkedDingTalkSourceId(channel.id))
    }
}

fun getChannelTutorial(channelId: String): String = when (channelId) {
    ForwardingChannels.PUSHPLUS -> """
        1. 微信搜索打开小程序或网站 pushplus.plus
        2. 微信一键扫码登录，在【一对一推送】中复制 Token
        3. 将 Token 粘贴保存即可；如需推送到微信群可填入 Topic
    """.trimIndent()
    ForwardingChannels.WECHAT_TEST -> """
        1. 访问微信公众平台测试账号申请页面 (mp.weixin.qq.com) 扫码登录
        2. 页面顶部复制 appID 与 appsecret
        3. 下方扫码关注测试号，获取您的 openID
        4. 新增测试模板 (标题: 短信通知, 内容: {{title.DATA}} {{time.DATA}} {{content.DATA}})，复制 template_id 填入
    """.trimIndent()
    ForwardingChannels.QQ -> """
        【Qmsg酱模式】:
        1. 访问 qmsg.zendee.cn 登录并添加 Qmsg 官方 QQ 机器人为好友
        2. 在后台复制您的 Qmsg Key 填入即可
        3. 使用 Qmsg v3 表单接口；单条最多 1800 字，同一 Key 每 5 秒最多提交一次
        4. “测试已受理”表示取得异步消息 ID，最终送达仍以 QQ 或 Qmsg 回执为准
        【OneBot 11 / NapCat 模式】:
        1. 在 NapCat 中开启 OneBot 11 HTTP 服务，填写服务地址（例如 http://设备IP:3000）
        2. NapCat 配置了 Token 时必须填写 Access Token；未配置则留空
        3. 选择私聊并填写 user_id（QQ号），或选择群聊并填写 group_id（群号）
    """.trimIndent()
    ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> """
        1. 登录企业微信管理后台 (work.weixin.qq.com)
        2. 【我的企业】底部复制「企业ID (corpid)」
        3. 【应用管理】->【自建】创建应用，获取 AgentId 与 Secret
        4. 接收人填 @all (全员) 或具体的企业微信账号 ID
    """.trimIndent()
    ForwardingChannels.WECOM_BOT -> """
        1. 电脑或手机企业微信群聊 -> 右上角设置 ->【添加群机器人】
        2. 复制生成的 Webhook URL 填入即可
    """.trimIndent()
    ForwardingChannels.WECOM_STREAM -> """
        1. 先在「远程发送」中添加并启用企业微信长连接来源
        2. 回到此处选择对应的连接来源；不要重复填写 Bot ID 和 Secret
        3. 填写接收推送的会话 ID：群聊用 Chat ID，单聊用成员 User ID
        4. 等连接状态显示“就绪”后再保存并测试；回复与主动推送共用连接，但使用不同协议
    """.trimIndent()
    ForwardingChannels.FEISHU_APP -> """
        1. 登录飞书开放平台 (open.feishu.cn) 创建“企业自建应用”
        2. 在【凭证与基础信息】复制 App ID 与 App Secret
        3. 开启单聊/群聊权限并发布，接收人填入您的飞书 open_id
    """.trimIndent()
    ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT -> """
        1. 飞书群聊 -> 右上角群设置 ->【群机器人】->【添加机器人】->【自定义机器人】
        2. 复制生成的 Webhook 地址 (如开启安全签名请一并填入 Secret)
    """.trimIndent()
    ForwardingChannels.DINGTALK -> """
        1. 电脑端钉钉群 -> 右上角群设置 ->【智能群助手】->【添加机器人】->【自定义】
        2. 安全设置勾选【加签】
        3. 填写 Webhook 与加签 Secret，可把手机短信推送到群
        4. 如需在群内远程发送短信，再填写企业内部应用的 Client ID 与 Client Secret
        5. 双向模式通过官方 Stream 长连接接收指令，无需公网回调地址
    """.trimIndent()
    ForwardingChannels.BARK -> """
        1. iPhone 在 App Store 搜索下载 Bark App
        2. 打开 Bark 首页复制您的专属 Device Key
        3. 填入 App 保存，苹果设备即可通过 APNs 极速低功耗弹窗
    """.trimIndent()
    ForwardingChannels.WXPUSHER -> """
        1. 在 WxPusher 后台创建应用，复制 AppToken
        2. 接收者关注应用后，填写 UID_ 开头的 UID；群发可填写数字 Topic ID
        3. 测试成功表示服务端创建发送任务，最终通知以接收设备为准
    """.trimIndent()
    ForwardingChannels.NTFY -> """
        1. 使用 ntfy.sh 或部署自己的 ntfy 服务
        2. 创建一个不易猜测的 Topic，并在接收设备订阅该 Topic
        3. 填写服务地址和 Topic；私有主题可填写访问 Token
        4. 不建议使用简单公开 Topic 传输短信或验证码
    """.trimIndent()
    ForwardingChannels.GOTIFY -> """
        1. 使用已有的 Gotify 服务，或在自己的服务器部署 Gotify
        2. 在 Gotify 后台创建 Application，并复制生成的 App Token
        3. 填写服务地址和 App Token 后保存并测试
        4. 公网服务应使用 HTTPS；HTTP 仅用于可信局域网服务
    """.trimIndent()
    ForwardingChannels.WEBSOCKET -> """
        1. 准备能够接收 JSON 消息的 WebSocket 服务或 HTTP POST 接口
        2. 填写 wss://、ws://、https:// 或 http:// 服务地址
        3. 服务需要鉴权时填写 Token，并先使用测试按钮验证连接
    """.trimIndent()
    ForwardingChannels.TELEGRAM -> """
        1. Telegram 搜索 @BotFather 发送 /newbot 创建机器人获取 Bot Token
        2. 搜索 @userinfobot 获取您的 Chat ID
        3. 填入 Token 与 Chat ID 即可实现海外极速推送
    """.trimIndent()
    ForwardingChannels.DISCORD -> """
        1. Discord 服务器频道设置 ->【整合】->【Webhooks】->【新建 Webhook】
        2. 点击【复制 Webhook URL】并填入 App 即可
    """.trimIndent()
    ForwardingChannels.TENCENT_CLOUD -> """
        1. 准备可接收本通道请求格式的告警 Webhook URL，并确认接收端的鉴权方式
        2. 填入 Webhook URL 与接收端约定的 Secret，保存后先测试
        3. 本通道只提交 HTTP 请求；短信通知是否触发及其资费由接收端配置决定
    """.trimIndent()
    ForwardingChannels.EMAIL -> """
        支持提供 SMTP + SSL/STARTTLS + 密码或应用授权码的邮箱，不限 QQ/163。
        仅允许 OAuth 登录的服务暂不支持。
        以 QQ 邮箱为例:
        1. SMTP 服务器: smtp.qq.com (端口 465 SSL)
        2. 发件账号: 您的 QQ 邮箱
        3. 授权码: QQ邮箱网页版 ->【设置】->【账户】-> 开启 POP3/SMTP 生成的16位授权码
        4. 接收邮箱: 多个邮箱用英文逗号或分号分隔
        5. 其他邮箱按服务商文档填写主机、端口与安全模式；通常 465 使用 SSL，587 使用 STARTTLS。
        测试成功表示邮件服务器已受理，仍需检查收件箱或垃圾邮件。
    """.trimIndent()
    ForwardingChannels.SMS_DIRECT -> """
        通过手机插入的备用 SIM 卡，直接以短信方式重发给指定的目标手机号。
        填入目标手机号码即可。
    """.trimIndent()
    ForwardingChannels.CUSTOM_WEBHOOK -> """
        1. 填写 HTTP 地址，并选择 GET、POST 或 PUT
        2. 可设置 Content-Type、Headers 和请求体模板
        3. 模板支持 [title]、[msg]、[from]、[time]、[sim]、[sim_slot]、[receiver]（支持大括号或中括号）
        4. GET 模式将模板作为查询参数；公网地址应使用 HTTPS
        5. 保存后先点击测试，接收端返回 HTTP 2xx 才算成功
    """.trimIndent()
    ForwardingChannels.SERVERCHAN3 -> """
        1. 前往方糖 Server酱³ 官网 (ft07.com) 扫码登录并获取 SendKey（例如 sctp123456t...）
        2. 手机安装 Server酱 客户端 App 并注册厂商通道（小米/华为/OPPO/vivo/iOS/FCM等）
        3. 填入 SendKey 即可实现免后台厂商原生推送，省电且无需后台常驻
        4. 可选配置消息标签 tags（使用竖线 | 分隔，客户端内分类过滤）
    """.trimIndent()
    ForwardingChannels.CHANNEL_GROUP -> """
        自由勾选多个已配置的通道组合为一个群组。
        收到短信后将一键并发推送到群组内的所有渠道！
    """.trimIndent()
    else -> "配置该通道所需的凭证参数即可。"
}

fun getInstanceSummary(instance: ForwardingChannelInstance): String {
    val type = instance.channelType
    return when (type) {
        ForwardingChannels.PUSHPLUS -> {
            val token = instance.optString("token")
            if (token.isNotBlank()) "Token: ${token.take(6)}***" else "未配置 Token"
        }
        ForwardingChannels.WECHAT_TEST -> {
            val appId = instance.optString("appId")
            if (appId.isNotBlank()) "AppID: $appId" else "未配置凭据"
        }
        ForwardingChannels.QQ -> {
            val key = instance.optString("qmsgKey").ifBlank { instance.optString("onebotUrl") }
            if (key.isNotBlank()) "目标: ${key.take(16)}..." else "未配置目标"
        }
        ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> {
            val corpId = instance.optString("corpId")
            if (corpId.isNotBlank()) "企业ID: $corpId" else "未配置应用"
        }
        ForwardingChannels.WECOM_STREAM -> {
            val chatId = instance.optString("chatId")
            if (chatId.isNotBlank()) "目标: $chatId" else "未配置推送目标"
        }
        ForwardingChannels.WECOM_BOT, ForwardingChannels.FEISHU_BOT, ForwardingChannels.FEISHU,
        ForwardingChannels.DISCORD, ForwardingChannels.TENCENT_CLOUD -> {
            val webhook = instance.optString("webhook")
            if (webhook.isNotBlank()) "Webhook: ${webhook.take(28)}..." else "未配置 Webhook"
        }
        ForwardingChannels.DINGTALK -> {
            val hasWebhook = instance.optString("webhook").isNotBlank()
            val hasStream = instance.optString("clientId").isNotBlank() &&
                instance.optString("clientSecret").isNotBlank() &&
                (!instance.optBoolean("whitelistEnabled", false) ||
                    instance.optString("authorizedUsers").isNotBlank())
            when {
                hasWebhook && hasStream -> "双向已配置 · 推送 + Stream"
                hasWebhook -> "仅短信推送"
                hasStream -> "仅远程发送"
                else -> "未配置"
            }
        }
        ForwardingChannels.FEISHU_APP -> {
            val appId = instance.optString("appId")
            if (appId.isNotBlank()) "AppID: $appId" else "未配置应用"
        }
        ForwardingChannels.BARK -> {
            val key = instance.optString("deviceKey")
            if (key.isNotBlank()) "Key: ${key.take(8)}***" else "未配置 DeviceKey"
        }
        ForwardingChannels.WEBSOCKET -> {
            val url = instance.optString("serverUrl")
            if (url.isNotBlank()) "URL: ${url.take(24)}..." else "未配置 URL"
        }
        ForwardingChannels.TELEGRAM -> {
            val chat = instance.optString("chatId")
            if (chat.isNotBlank()) "ChatID: $chat" else "未配置凭据"
        }
        ForwardingChannels.EMAIL -> {
            val host = instance.optString("host")
            val user = instance.optString("user")
            if (host.isNotBlank()) "$user @ $host" else "未配置 SMTP"
        }
        ForwardingChannels.SMS_DIRECT -> {
            val phone = instance.optString("phone")
            if (phone.isNotBlank()) "目标号: $phone" else "未配置目标号"
        }
        ForwardingChannels.CUSTOM_WEBHOOK -> {
            val url = instance.optString("url")
            if (url.isNotBlank()) "URL: ${url.take(28)}..." else "未配置 URL"
        }
        ForwardingChannels.GOTIFY -> {
            val serverUrl = instance.optString("serverUrl")
            if (serverUrl.isNotBlank()) "Server: ${serverUrl.take(20)}..." else "未配置服务"
        }
        ForwardingChannels.WXPUSHER -> instance.optString("targetId").ifBlank { "未配置 UID / Topic ID" }
        ForwardingChannels.NTFY -> {
            val serverUrl = instance.optString("serverUrl")
            val topic = instance.optString("topic")
            if (topic.isNotBlank()) "${serverUrl.ifBlank { "https://ntfy.sh" }.take(20)} / $topic" else "未配置 Topic"
        }
        ForwardingChannels.SERVERCHAN3 -> {
            val key = instance.optString("sendKey")
            if (key.isNotBlank()) "SendKey: ${key.take(8)}***" else "未配置 SendKey"
        }
        else -> "已配置"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HeaderPill(
    label: String,
    contentColor: Color,
    containerColor: Color,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = containerColor,
        modifier = Modifier.height(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp)) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = contentColor, maxLines = 1, softWrap = false)
        }
    }
}

@Composable
private fun ChannelNavChip(
    label: String,
    selected: Boolean,
    isDark: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = if (selected) {
            if (isDark) Color(0xFF1B3322) else BrandGreenSoft
        } else {
            if (isDark) DarkSurface else Color.White
        },
        border = BorderStroke(1.dp, if (selected) BrandGreen else if (isDark) DarkOutline else OutlineSoft),
        modifier = Modifier.height(36.dp)
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 13.dp)) {
            Text(
                label,
                fontSize = 12.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) BrandGreen else TextSecondary,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelHubScreen(
    onBack: (() -> Unit)? = null,
    onInlineEditorVisibilityChanged: (Boolean) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val channelRepo = remember { ChannelRepository.getInstance(context) }
    val instances by channelRepo.instancesFlow.collectAsState()

    // 兼容此前错误落库的 catalog: 占位 ID：进入页面即转换为可启停、可测试的真实实例。
    LaunchedEffect(instances) {
        instances.filter { it.id.startsWith("catalog:") }.forEach { staleInstance ->
            channelRepo.saveInstance(
                staleInstance.copy(
                    id = UUID.randomUUID().toString(),
                    enabled = true
                )
            )
            channelRepo.deleteInstance(staleInstance.id)
        }
    }
    var selectedSection by remember { mutableStateOf("all") }
    var inlineRuleId by remember { mutableStateOf<String?>(null) }
    var isEditingInlineRule by remember { mutableStateOf(false) }

    LaunchedEffect(isEditingInlineRule) {
        onInlineEditorVisibilityChanged(isEditingInlineRule)
    }
    LaunchedEffect(selectedSection) {
        if (selectedSection != "rules") {
            isEditingInlineRule = false
        }
    }
    DisposableEffect(Unit) {
        onDispose { onInlineEditorVisibilityChanged(false) }
    }

    var testingStates by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var testResults by remember { mutableStateOf(mapOf<String, String>()) }

    var editingInstance by remember { mutableStateOf<ForwardingChannelInstance?>(null) }
    var isAddingNew by remember { mutableStateOf(false) }
    var instanceToDelete by remember { mutableStateOf<ForwardingChannelInstance?>(null) }
    var showFullTutorialDialog by remember { mutableStateOf(false) }
    var showForwardSettingsDialog by remember { mutableStateOf(false) }

    val allChannelItems = remember(instances) {
        val supportedDefinitions = ALL_CHANNEL_TYPE_DEFINITIONS
        val catalogItems = supportedDefinitions.flatMap { definition ->
            instances.filter { it.channelType == definition.type }.ifEmpty {
                listOf(
                    ForwardingChannelInstance(
                        id = "catalog:${definition.type}",
                        channelType = definition.type,
                        name = definition.name,
                        enabled = false,
                        configJson = "{}"
                    )
                )
            }
        }
        catalogItems + instances.filter { instance ->
            supportedDefinitions.none { it.type == instance.channelType }
        }
    }
    val visibleInstances = if (selectedSection == "custom") instances else allChannelItems

    val isDark = isSystemInDarkTheme()
    val pageBgColor = if (isDark) DarkBackground else AppBackground
    val primaryTextColor = if (isDark) Color.White else TextPrimary
    val secondaryTextColor = if (isDark) Color(0xFF9CA3AF) else TextSecondary

    Scaffold(
        containerColor = pageBgColor,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
        ) {
            // 恢复经典标题区结构，列表仍只展示用户真实创建的实例。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(painterResource(R.drawable.ic_chevron_left), "返回", tint = primaryTextColor)
                            }
                        }
                        Text("通道管理", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = primaryTextColor)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        HeaderPill("⚙️ 转发设置", GatewayBlue, if (isDark) Color(0xFF1E293B) else Color(0xFFEFF6FF)) {
                            showForwardSettingsDialog = true
                        }
                        HeaderPill("📖 教程", BrandGreen, if (isDark) Color(0xFF1B3322) else BrandGreenSoft) {
                            showFullTutorialDialog = true
                        }
                        Button(
                            onClick = { isAddingNew = true },
                            shape = RoundedCornerShape(18.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                        ) {
                            Text("+ 自定义通道", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    "${instances.size} 个通道实例 · ${instances.count { it.enabled }} 个已启用",
                    fontSize = 12.sp,
                    color = secondaryTextColor
                )
            }

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    ChannelNavChip("🌐 全部", selectedSection == "all", isDark) { selectedSection = "all" }
                }
                item {
                    ChannelNavChip("🔧 自定义", selectedSection == "custom", isDark) { selectedSection = "custom" }
                }
                item {
                    ChannelNavChip("🎮 远程发送", selectedSection == "remote", isDark) { selectedSection = "remote" }
                }
                item {
                    ChannelNavChip("⚡ 规则", selectedSection == "rules", isDark) { selectedSection = "rules" }
                }
                item {
                    ChannelNavChip("📝 消息模板", selectedSection == "templates", isDark) { selectedSection = "templates" }
                }
                item {
                    ChannelNavChip("💬 自动回复", selectedSection == "auto_reply", isDark) { selectedSection = "auto_reply" }
                }
                item {
                    ChannelNavChip("📞 未接提醒", selectedSection == "missed_call", isDark) { selectedSection = "missed_call" }
                }
                item {
                    ChannelNavChip("🔋 电量提醒", selectedSection == "low_battery", isDark) { selectedSection = "low_battery" }
                }
                item {
                    ChannelNavChip("📋 验证码写入", selectedSection == "autofill", isDark) { selectedSection = "autofill" }
                }
                item {
                    ChannelNavChip("💚 定时心跳", selectedSection == "heartbeat", isDark) { selectedSection = "heartbeat" }
                }
                item {
                    ChannelNavChip("🔔 通知转发", selectedSection == "notification_forward", isDark) { selectedSection = "notification_forward" }
                }
                item {
                    ChannelNavChip("📨 批量发送", selectedSection == "bulk_send", isDark) { selectedSection = "bulk_send" }
                }
                item {
                    ChannelNavChip("⏰ 定时短信", selectedSection == "scheduled", isDark) { selectedSection = "scheduled" }
                }
            }

            // 通道实例列表
            if (selectedSection == "remote") {
                org.fossify.messages.ui.compose.remote.RemoteControlScreen(onBack = null)
            } else if (selectedSection == "rules") {
                if (isEditingInlineRule) {
                    org.fossify.messages.ui.compose.rules.RuleEditorScreen(
                        ruleId = inlineRuleId,
                        onNavigateBack = { isEditingInlineRule = false }
                    )
                } else {
                    org.fossify.messages.ui.compose.rules.RuleManagementScreen(
                        onNavigateBack = null,
                        onNavigateToEditor = { id ->
                            inlineRuleId = id
                            isEditingInlineRule = true
                        }
                    )
                }
            } else if (selectedSection == "templates") {
                RuleStudioScreen(embeddedTemplateOnly = true)
            } else if (selectedSection == "auto_reply") {
                AutoReplyEmbeddedScreen()
            } else if (selectedSection == "missed_call") {
                MissedCallEmbeddedScreen()
            } else if (selectedSection == "low_battery") {
                LowBatteryEmbeddedScreen()
            } else if (selectedSection == "autofill") {
                AutofillEmbeddedScreen()
            } else if (selectedSection == "heartbeat") {
                HeartbeatEmbeddedScreen()
            } else if (selectedSection == "notification_forward") {
                NotificationForwardEmbeddedScreen()
            } else if (selectedSection == "scheduled") {
                ClassicFeatureEntryScreen(
                    title = "定时短信",
                    description = "创建、查看和管理计划发送的短信。",
                    buttonText = "管理定时短信",
                    activityClass = org.fossify.messages.activities.ScheduledMessagesActivity::class.java
                )
            } else if (selectedSection == "bulk_send") {
                ClassicFeatureEntryScreen(
                    title = "批量发送",
                    description = "向多个号码批量发送短信，会产生运营商通信费用。",
                    buttonText = "打开批量发送",
                    activityClass = org.fossify.messages.activities.BulkSendActivity::class.java
                )
            } else if (visibleInstances.isEmpty()) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = if (isDark) DarkSurface else SurfaceCard,
                    border = BorderStroke(1.dp, if (isDark) DarkOutline else OutlineSoft),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 20.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("📭", fontSize = 40.sp)
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (selectedSection == "custom") "暂无自定义通道" else "暂无可用通道",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryTextColor
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "点击右上角「+ 添加自定义通道」创建通道实例；旧版已配置通道会在升级后自动导入。",
                            fontSize = 12.sp,
                            color = secondaryTextColor,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(visibleInstances, key = { it.id }) { instance ->
                        val def = getChannelTypeDefinition(instance.channelType)
                        val isCatalogPlaceholder = instance.id.startsWith("catalog:")
                        val isTesting = testingStates[instance.id] == true
                        val testMsg = testResults[instance.id]

                        Surface(
                            shape = RoundedCornerShape(20.dp),
                            color = if (isDark) DarkSurface else SurfaceCard,
                            shadowElevation = 2.dp,
                            border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (isDark) Color(0xFF1B3322) else BrandGreenSoft,
                                        modifier = Modifier.size(42.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                            Text(text = def?.iconEmoji ?: "📡", fontSize = 20.sp)
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = instance.name,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = primaryTextColor,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = "${def?.name ?: instance.channelType} · ${getInstanceSummary(instance)}",
                                            fontSize = 12.sp,
                                            color = secondaryTextColor,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Switch(
                                        checked = instance.enabled,
                                        onCheckedChange = { targetState ->
                                            if (isCatalogPlaceholder) {
                                                editingInstance = instance
                                            } else {
                                                channelRepo.toggleInstanceEnabled(instance.id, targetState)
                                                syncLinkedDingTalkSource(context, instance.copy(enabled = targetState))
                                                Toast.makeText(context, "【${instance.name}】已${if (targetState) "启用" else "停用"}", Toast.LENGTH_SHORT).show()
                                            }
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = BrandGreen
                                        )
                                    )
                                }

                                if (testMsg != null) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    StatusBadge(
                                        text = testMsg,
                                        color = if (testMsg.contains("失败")) GatewayRed else GatewayGreen
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = if (isDark) Color(0xFF2C1E3A) else Color(0xFFF3E8FF)
                                    ) {
                                        Text(
                                            text = def?.category?.title ?: "通道",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = GatewayPurple,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }

                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (!isCatalogPlaceholder) {
                                            OutlinedButton(
                                                onClick = { instanceToDelete = instance },
                                                shape = RoundedCornerShape(12.dp),
                                                border = BorderStroke(1.dp, GatewayRed.copy(alpha = 0.4f)),
                                                modifier = Modifier.height(32.dp),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                                            ) {
                                                Text("删除", fontSize = 11.5.sp, color = GatewayRed)
                                            }
                                        }

                                        OutlinedButton(
                                            onClick = { editingInstance = instance },
                                            shape = RoundedCornerShape(12.dp),
                                            border = BorderStroke(1.dp, if (isDark) DarkOutline else OutlineSoft),
                                            modifier = Modifier.height(32.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                                        ) {
                                            Text(if (isCatalogPlaceholder) "⚙️ 配置" else "⚙️ 编辑", fontSize = 11.5.sp, color = primaryTextColor)
                                        }

                                        if (!isCatalogPlaceholder) Button(
                                            onClick = {
                                                testingStates = testingStates + (instance.id to true)
                                                scope.launch {
                                                    val res = ChannelTestSender.sendTestInstance(context, instance)
                                                    testingStates = testingStates + (instance.id to false)
                                                    if (res.isSuccess) {
                                                        testResults = testResults + (instance.id to "✅ 测试成功")
                                                        Toast.makeText(context, "✅ [${instance.name}] 测试发送成功！", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        val err = res.exceptionOrNull()?.message ?: "未知错误"
                                                        testResults = testResults + (instance.id to "❌ 失败: ${err.take(12)}")
                                                        Toast.makeText(context, "❌ [${instance.name}] 失败: $err", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(12.dp),
                                            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                                            modifier = Modifier.height(32.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                            enabled = !isTesting
                                        ) {
                                            if (isTesting) {
                                                CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = Color.White)
                                            } else {
                                                Text("测试", fontSize = 11.5.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(LocalGatewayBottomPadding.current)) }
                }
            }
        }
    }

    // 转发高级设置弹窗
    if (showForwardSettingsDialog) {
        ForwardSettingsDialog(onDismiss = { showForwardSettingsDialog = false })
    }

    // 新增通道实例弹窗
    if (isAddingNew) {
        InstanceEditorDialog(
            existingInstance = null,
            onDismiss = { isAddingNew = false },
            onSaved = { newInst ->
                channelRepo.saveInstance(newInst)
                syncLinkedDingTalkSource(context, newInst)
                Toast.makeText(context, "通道实例【${newInst.name}】已成功添加！", Toast.LENGTH_SHORT).show()
                isAddingNew = false
            }
        )
    }

    // 编辑通道实例弹窗
    editingInstance?.let { target ->
        InstanceEditorDialog(
            existingInstance = target,
            onDismiss = { editingInstance = null },
            onSaved = { updatedInst ->
                // “全部通道”中的 catalog: 项只是未配置目录卡。首次保存时必须
                // 转换为真实实例，否则页面会继续把它当作占位项，隐藏开关和测试按钮。
                val savedInstance = if (target.id.startsWith("catalog:")) {
                    updatedInst.copy(
                        id = UUID.randomUUID().toString(),
                        enabled = true
                    )
                } else {
                    updatedInst
                }
                channelRepo.saveInstance(savedInstance)
                syncLinkedDingTalkSource(context, savedInstance)
                if (target.id.startsWith("catalog:")) {
                    channelRepo.deleteInstance(target.id)
                }
                Toast.makeText(context, "通道实例【${savedInstance.name}】配置已更新！", Toast.LENGTH_SHORT).show()
                editingInstance = null
            }
        )
    }

    // 删除确认弹窗 (检查规则引用)
    instanceToDelete?.let { inst ->
        val referencingRules = remember(inst.id) { channelRepo.getReferencingRules(inst.id) }
        AlertDialog(
            onDismissRequest = { instanceToDelete = null },
            title = { Text("删除通道？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("确定要删除通道实例【${inst.name}】吗？删除后不可恢复。")
                    if (referencingRules.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "⚠️ 警告：该实例当前已被以下 ${referencingRules.size} 条转发规则引用：\n" +
                                referencingRules.joinToString("、") { it.name } +
                                "\n删除后这些规则将无法再向该通道分发！",
                            color = GatewayRed,
                            fontSize = 12.sp
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        channelRepo.deleteInstance(inst.id)
                        deleteLinkedDingTalkSource(context, inst)
                        Toast.makeText(context, "已删除通道实例【${inst.name}】", Toast.LENGTH_SHORT).show()
                        instanceToDelete = null
                    },
                    enabled = referencingRules.isEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = GatewayRed)
                ) {
                    Text(
                        text = if (referencingRules.isEmpty()) "确认删除" else "请先解除规则引用",
                        color = Color.White
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { instanceToDelete = null }) { Text("取消") }
            }
        )
    }

    // 全量教程指南弹窗
    if (showFullTutorialDialog) {
        ChannelFullTutorialDialog(onDismiss = { showFullTutorialDialog = false })
    }
}

@Composable
private fun LegacyFeatureEntry(
    title: String,
    description: String,
    actionText: String,
    onClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .padding(bottom = 92.dp)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = if (isDark) DarkSurface else SurfaceCard,
            border = BorderStroke(1.dp, if (isDark) DarkOutline else OutlineSoft),
            shadowElevation = 2.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = title,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isDark) Color.White else TextPrimary
                )
                Text(
                    text = description,
                    fontSize = 12.sp,
                    lineHeight = 18.sp,
                    color = if (isDark) Color(0xFFB8C0CC) else TextSecondary
                )
                Button(
                    onClick = onClick,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                    modifier = Modifier.height(40.dp)
                ) {
                    Text(actionText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstanceEditorDialog(
    existingInstance: ForwardingChannelInstance?,
    onDismiss: () -> Unit,
    onSaved: (ForwardingChannelInstance) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val remoteSources by RemoteSourceRepository.getInstance(context).sourcesFlow.collectAsState()
    val weComSources = remoteSources.filter { it.type == RemoteSourceType.WECOM }
    val isEditing = existingInstance != null
    var selectedType by remember {
        mutableStateOf(existingInstance?.channelType ?: ForwardingChannels.WECOM_BOT)
    }
    var instanceName by remember {
        mutableStateOf(
            existingInstance?.name
                ?: "${getChannelTypeDefinition(selectedType)?.name ?: "通道"} 1"
        )
    }

    var f1 by remember {
        mutableStateOf(
            when (selectedType) {
                ForwardingChannels.PUSHPLUS -> existingInstance?.optString("token") ?: ""
                ForwardingChannels.WXPUSHER -> existingInstance?.optString("appToken") ?: ""
                ForwardingChannels.WECHAT_TEST -> existingInstance?.optString("appId") ?: ""
                ForwardingChannels.QQ -> existingInstance?.optString("qmsgKey")?.ifBlank { existingInstance.optString("onebotUrl") } ?: ""
                ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> existingInstance?.optString("corpId") ?: ""
                ForwardingChannels.WECOM_STREAM -> existingInstance?.optString("chatId") ?: ""
                ForwardingChannels.WECOM_BOT, ForwardingChannels.FEISHU_BOT, ForwardingChannels.FEISHU,
                ForwardingChannels.DINGTALK, ForwardingChannels.DISCORD, ForwardingChannels.TENCENT_CLOUD -> existingInstance?.optString("webhook") ?: ""
                ForwardingChannels.FEISHU_APP -> existingInstance?.optString("appId") ?: ""
                ForwardingChannels.BARK -> existingInstance?.optString("serverUrl") ?: "https://api.day.app"
                ForwardingChannels.WEBSOCKET -> existingInstance?.optString("serverUrl") ?: ""
                ForwardingChannels.TELEGRAM -> existingInstance?.optString("botToken") ?: ""
                ForwardingChannels.EMAIL -> existingInstance?.optString("host") ?: "smtp.qq.com"
                ForwardingChannels.SMS_DIRECT -> existingInstance?.optString("phone") ?: ""
                ForwardingChannels.CUSTOM_WEBHOOK -> existingInstance?.optString("url") ?: ""
                ForwardingChannels.GOTIFY -> existingInstance?.optString("serverUrl") ?: ""
                ForwardingChannels.NTFY -> existingInstance?.optString("serverUrl") ?: "https://ntfy.sh"
                ForwardingChannels.SERVERCHAN3 -> existingInstance?.optString("sendKey") ?: ""
                else -> ""
            }
        )
    }

    var f2 by remember {
        mutableStateOf(
            when (selectedType) {
                ForwardingChannels.PUSHPLUS -> existingInstance?.optString("topic") ?: ""
                ForwardingChannels.WXPUSHER -> existingInstance?.optString("targetId") ?: ""
                ForwardingChannels.WECHAT_TEST -> existingInstance?.optString("appSecret") ?: ""
                ForwardingChannels.QQ -> existingInstance?.optString("type") ?: "qmsg"
                ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> existingInstance?.optString("agentId") ?: ""
                ForwardingChannels.DINGTALK, ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT,
                ForwardingChannels.TENCENT_CLOUD -> existingInstance?.optString("secret") ?: ""
                ForwardingChannels.FEISHU_APP -> existingInstance?.optString("appSecret") ?: ""
                ForwardingChannels.BARK -> existingInstance?.optString("deviceKey") ?: ""
                ForwardingChannels.WEBSOCKET -> existingInstance?.optString("token") ?: ""
                ForwardingChannels.TELEGRAM -> existingInstance?.optString("chatId") ?: ""
                ForwardingChannels.EMAIL -> existingInstance?.optString("user") ?: ""
                ForwardingChannels.CUSTOM_WEBHOOK -> existingInstance?.optString("headers") ?: ""
                ForwardingChannels.GOTIFY -> existingInstance?.optString("token") ?: ""
                ForwardingChannels.NTFY -> existingInstance?.optString("topic") ?: ""
                ForwardingChannels.SERVERCHAN3 -> existingInstance?.optString("tags") ?: ""
                else -> ""
            }
        )
    }

    var f3 by remember {
        mutableStateOf(
            when (selectedType) {
                ForwardingChannels.WECHAT_TEST -> existingInstance?.optString("templateId") ?: ""
                ForwardingChannels.QQ -> existingInstance?.optString("accessToken") ?: ""
                ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> existingInstance?.optString("secret") ?: ""
                ForwardingChannels.FEISHU_APP -> existingInstance?.optString("receiveId") ?: ""
                ForwardingChannels.EMAIL -> existingInstance?.optString("password") ?: ""
                ForwardingChannels.NTFY -> existingInstance?.optString("token") ?: ""
                ForwardingChannels.DINGTALK -> existingInstance?.optString("clientId") ?: ""
                else -> ""
            }
        )
    }

    var f4 by remember {
        mutableStateOf(
            when (selectedType) {
                ForwardingChannels.WECHAT_TEST -> existingInstance?.optString("openId") ?: ""
                ForwardingChannels.QQ -> existingInstance?.optString("targetId")
                    ?.ifBlank { existingInstance.optString("userId") }
                    ?.ifBlank { existingInstance.optString("groupId") } ?: ""
                ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> existingInstance?.optString("toUser") ?: "@all"
                ForwardingChannels.EMAIL -> existingInstance?.optString("recipients") ?: ""
                ForwardingChannels.NTFY -> existingInstance?.optString("priority") ?: "default"
                ForwardingChannels.DINGTALK -> existingInstance?.optString("clientSecret") ?: ""
                else -> ""
            }
        )
    }

    var f5 by remember {
        mutableStateOf(
            if (selectedType == ForwardingChannels.DINGTALK) existingInstance?.optString("customCommandPrefix") ?: ""
            else existingInstance?.optString("tags") ?: ""
        )
    }
    var f6 by remember {
        mutableStateOf(
            if (selectedType == ForwardingChannels.DINGTALK) existingInstance?.optString("authorizedUsers") ?: ""
            else existingInstance?.optString("clickUrl") ?: ""
        )
    }
    var f7 by remember { mutableStateOf(existingInstance?.optString("authorizedGroups") ?: "") }
    var qqTargetType by remember {
        mutableStateOf(
            existingInstance?.optString("targetType")?.ifBlank {
                if (existingInstance.optString("groupId").isNotBlank()) "group" else "private"
            } ?: "private"
        )
    }
    var customWebhookMethod by remember {
        mutableStateOf(existingInstance?.optString("method")?.ifBlank { "POST" } ?: "POST")
    }
    var customWebhookContentType by remember {
        mutableStateOf(existingInstance?.optString("contentType")?.ifBlank { "application/json" } ?: "application/json")
    }
    var customWebhookBody by remember {
        mutableStateOf(
            existingInstance?.optString("bodyTemplate")?.ifBlank { MultiForwardConfig.DEFAULT_CUSTOM_WEBHOOK_BODY }
                ?: MultiForwardConfig.DEFAULT_CUSTOM_WEBHOOK_BODY
        )
    }
    var dingTalkWhitelistEnabled by remember {
        mutableStateOf(existingInstance?.optBoolean("whitelistEnabled", false) ?: false)
    }
    var smsDirectOnlyOnNoNetwork by remember {
        val legacyMode = MultiForwardConfig(context).smsDirectOnlyOnNoNetwork
        mutableStateOf(existingInstance?.optBoolean("onlyOnNoNetwork", legacyMode) ?: legacyMode)
    }
    var emailPort by remember { mutableStateOf(existingInstance?.optInt("port", 465)?.toString() ?: "465") }
    var emailSecurity by remember {
        val port = existingInstance?.optInt("port", 465) ?: 465
        mutableIntStateOf(existingInstance?.optInt("security", if (port == 587) MultiForwardConfig.EMAIL_SECURITY_STARTTLS else MultiForwardConfig.EMAIL_SECURITY_SSL)
            ?: MultiForwardConfig.EMAIL_SECURITY_SSL)
    }
    var weComSourceId by remember {
        mutableStateOf(existingInstance?.optString("sourceInstanceId").orEmpty())
    }

    var typeMenuExpanded by remember { mutableStateOf(false) }
    var weComSourceMenuExpanded by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var showSecrets by remember { mutableStateOf(false) }
    val secretVisualTransformation = if (showSecrets) VisualTransformation.None else PasswordVisualTransformation()
    var testFeedback by remember { mutableStateOf<String?>(null) }
    var testedConfiguration by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun hasRequiredConfiguration(): Boolean = when (selectedType) {
        ForwardingChannels.PUSHPLUS -> f1.isNotBlank()
        ForwardingChannels.WXPUSHER -> f1.isNotBlank() &&
            (f2.trim().startsWith("UID_") || f2.trim().toLongOrNull()?.let { it > 0 } == true)
        ForwardingChannels.WECHAT_TEST -> listOf(f1, f2, f3, f4).all { it.isNotBlank() }
        ForwardingChannels.QQ -> f1.isNotBlank() && (f2 == "qmsg" || f4.isNotBlank())
        ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> listOf(f1, f2, f3, f4).all { it.isNotBlank() }
        ForwardingChannels.WECOM_BOT -> f1.isNotBlank()
        ForwardingChannels.WECOM_STREAM -> weComSourceId.isNotBlank() && f1.isNotBlank()
        ForwardingChannels.FEISHU_APP -> listOf(f1, f2, f3).all { it.isNotBlank() }
        ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT -> f1.isNotBlank()
        ForwardingChannels.DINGTALK -> f1.isNotBlank() && (
            !dingTalkWhitelistEnabled || f3.isBlank() || f4.isBlank() || f6.isNotBlank()
            )
        ForwardingChannels.BARK -> f1.isNotBlank() && f2.isNotBlank()
        ForwardingChannels.WEBSOCKET -> f1.isNotBlank()
        ForwardingChannels.TELEGRAM -> f1.isNotBlank() && f2.isNotBlank()
        ForwardingChannels.DISCORD, ForwardingChannels.TENCENT_CLOUD -> f1.isNotBlank()
        ForwardingChannels.EMAIL -> listOf(f1, f2, f3, f4).all { it.isNotBlank() } &&
            emailPort.toIntOrNull()?.let { it in 1..65535 } == true
        ForwardingChannels.SMS_DIRECT -> f1.isNotBlank()
        ForwardingChannels.CUSTOM_WEBHOOK -> f1.isNotBlank() &&
            customWebhookMethod.uppercase() in setOf("GET", "POST", "PUT")
        ForwardingChannels.GOTIFY -> f1.isNotBlank() && f2.isNotBlank()
        ForwardingChannels.NTFY -> f1.isNotBlank() && f2.isNotBlank()
        ForwardingChannels.SERVERCHAN3 -> f1.isNotBlank()
        else -> false
    }

    fun currentInstance(): ForwardingChannelInstance {
        // Keep persisted settings that this editor does not expose.
        val configJson = existingInstance?.takeIf { it.channelType == selectedType }?.let {
            runCatching { JSONObject(it.configJson) }.getOrNull()
        } ?: JSONObject()
        when (selectedType) {
            ForwardingChannels.PUSHPLUS -> configJson.put("token", f1).put("topic", f2)
            ForwardingChannels.WXPUSHER -> configJson.put("appToken", f1).put("targetId", f2.trim())
            ForwardingChannels.WECHAT_TEST -> configJson.put("appId", f1).put("appSecret", f2).put("templateId", f3).put("openId", f4)
            ForwardingChannels.QQ -> {
                // Only one provider target may remain after changing the QQ provider.
                configJson.remove("qmsgKey")
                configJson.remove("onebotUrl")
                configJson.remove("userId")
                configJson.remove("groupId")
                if (f2 == "qmsg") {
                    configJson.put("qmsgKey", f1).put("type", "qmsg")
                    configJson.remove("accessToken")
                    configJson.remove("targetType")
                    configJson.remove("targetId")
                } else {
                    configJson.put("onebotUrl", f1)
                        .put("type", f2)
                        .put("accessToken", f3)
                        .put("targetType", qqTargetType)
                        .put("targetId", f4)
                }
            }
            ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> configJson.put("corpId", f1).put("agentId", f2).put("secret", f3).put("toUser", f4)
            ForwardingChannels.WECOM_BOT -> configJson.put("webhook", f1)
            ForwardingChannels.WECOM_STREAM -> configJson.put("sourceInstanceId", weComSourceId).put("chatId", f1)
            ForwardingChannels.FEISHU_APP -> configJson.put("appId", f1).put("appSecret", f2).put("receiveId", f3)
            ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT -> configJson.put("webhook", f1).put("secret", f2)
            ForwardingChannels.DINGTALK -> configJson.put("webhook", f1).put("secret", f2)
                .put("clientId", f3).put("clientSecret", f4).put("customCommandPrefix", f5)
                .put("whitelistEnabled", dingTalkWhitelistEnabled)
                .put("authorizedUsers", f6).put("authorizedGroups", f7)
            ForwardingChannels.BARK -> configJson.put("serverUrl", f1).put("deviceKey", f2)
            ForwardingChannels.WEBSOCKET -> configJson.put("serverUrl", f1).put("token", f2)
            ForwardingChannels.TELEGRAM -> configJson.put("botToken", f1).put("chatId", f2)
            ForwardingChannels.DISCORD -> configJson.put("webhook", f1)
            ForwardingChannels.TENCENT_CLOUD -> configJson.put("webhook", f1).put("secret", f2)
            ForwardingChannels.EMAIL -> {
                configJson.put("port", emailPort.toIntOrNull() ?: 465).put("security", emailSecurity)
                    .put("host", f1).put("user", f2).put("password", f3).put("recipients", f4)
            }
            ForwardingChannels.SMS_DIRECT -> configJson.put("phone", f1)
                .put("onlyOnNoNetwork", smsDirectOnlyOnNoNetwork)
            ForwardingChannels.CUSTOM_WEBHOOK -> configJson.put("url", f1).put("headers", f2)
                .put("method", customWebhookMethod.uppercase())
                .put("contentType", customWebhookContentType)
                .put("bodyTemplate", customWebhookBody)
            ForwardingChannels.GOTIFY -> configJson.put("serverUrl", f1).put("token", f2)
            ForwardingChannels.NTFY -> configJson.put("serverUrl", f1).put("topic", f2).put("token", f3)
                .put("priority", f4).put("tags", f5).put("clickUrl", f6)
            ForwardingChannels.SERVERCHAN3 -> configJson.put("sendKey", f1).put("tags", f2)
            else -> configJson.put("webhook", f1)
        }
        return ForwardingChannelInstance(
            id = existingInstance?.id ?: UUID.randomUUID().toString(),
            channelType = selectedType,
            name = instanceName.ifBlank { getChannelTypeDefinition(selectedType)?.name ?: "通道" },
            enabled = existingInstance?.enabled ?: true,
            configJson = configJson.toString()
        )
    }

    AlertDialog(
        modifier = Modifier.navigationBarsPadding(),
        onDismissRequest = onDismiss,
        title = {
            Text(if (isEditing) "⚙️ 编辑通道实例" else "✨ 添加新通道实例")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TextButton(onClick = { showSecrets = !showSecrets }) {
                    Text(if (showSecrets) "隐藏密钥与授权码" else "显示密钥与授权码")
                }
                // 通道类型选择器 (仅新增时可选)
                if (!isEditing) {
                    Text("选择通道类型：", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    ExposedDropdownMenuBox(
                        expanded = typeMenuExpanded,
                        onExpandedChange = { typeMenuExpanded = !typeMenuExpanded }
                    ) {
                        val currentDef = getChannelTypeDefinition(selectedType)
                        OutlinedTextField(
                            value = "${currentDef?.iconEmoji} ${currentDef?.name}",
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = typeMenuExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = typeMenuExpanded,
                            onDismissRequest = { typeMenuExpanded = false }
                        ) {
                            ALL_CHANNEL_TYPE_DEFINITIONS.forEach { def ->
                                DropdownMenuItem(
                                    text = { Text("${def.iconEmoji} ${def.name} (${def.category.title})") },
                                    onClick = selectType@{
                                        if (selectedType == def.type) {
                                            typeMenuExpanded = false
                                            return@selectType
                                        }
                                        selectedType = def.type
                                        instanceName = "${def.name} 1"
                                        // 新增时切换类型必须清空上一类型的输入，避免凭据串入其它通道。
                                        f1 = when (def.type) {
                                            ForwardingChannels.BARK -> "https://api.day.app"
                                            ForwardingChannels.EMAIL -> "smtp.qq.com"
                                            ForwardingChannels.NTFY -> "https://ntfy.sh"
                                            else -> ""
                                        }
                                        f2 = ""
                                        f3 = ""
                                        f4 = when (def.type) {
                                            ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> "@all"
                                            ForwardingChannels.NTFY -> "default"
                                            else -> ""
                                        }
                                        f5 = ""
                                        f6 = ""
                                        f7 = ""
                                        emailPort = "465"
                                        emailSecurity = MultiForwardConfig.EMAIL_SECURITY_SSL
                                        dingTalkWhitelistEnabled = false
                                        weComSourceId = if (def.type == ForwardingChannels.WECOM_STREAM && weComSources.size == 1) {
                                            weComSources.single().id
                                        } else ""
                                        customWebhookMethod = "POST"
                                        customWebhookContentType = "application/json"
                                        customWebhookBody = MultiForwardConfig.DEFAULT_CUSTOM_WEBHOOK_BODY
                                        testFeedback = null
                                        typeMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = instanceName,
                    onValueChange = { instanceName = it },
                    label = { Text("实例名称 (自定义备注)") },
                    modifier = Modifier.fillMaxWidth()
                )

                // 极速指引小卡片
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("💡 快速配置指引：", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = getChannelTutorial(selectedType),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }

                // 根据类型显示对应输入字段
                when (selectedType) {
                    ForwardingChannels.PUSHPLUS -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Token (一对一密钥)") }, visualTransformation = secretVisualTransformation, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("Topic (群组编码 选填)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.WECHAT_TEST -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("appID") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("appsecret") }, visualTransformation = secretVisualTransformation, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f3, onValueChange = { f3 = it }, label = { Text("template_id (模板ID)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f4, onValueChange = { f4 = it }, label = { Text("openID (接收者微信号)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.QQ -> {
                        Text("接入方式", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            listOf("qmsg" to "Qmsg酱", "onebot" to "OneBot 11", "napcat" to "NapCat").forEach { (value, label) ->
                                FilterChip(
                                    selected = f2 == value,
                                    onClick = { f2 = value; testFeedback = null },
                                    label = { Text(label) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                        if (f2 == "qmsg") {
                            OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Qmsg Key") }, visualTransformation = secretVisualTransformation, modifier = Modifier.fillMaxWidth())
                        } else {
                            OutlinedTextField(
                                value = f1,
                                onValueChange = { f1 = it },
                                label = { Text("OneBot 11 HTTP 地址") },
                                placeholder = { Text("http://192.168.1.10:3000") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            OutlinedTextField(
                                value = f3,
                                onValueChange = { f3 = it },
                                label = { Text("Access Token（未启用鉴权可留空）") },
                                visualTransformation = secretVisualTransformation,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text("消息目标", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                FilterChip(
                                    selected = qqTargetType == "private",
                                    onClick = { qqTargetType = "private" },
                                    label = { Text("私聊（user_id）") },
                                    modifier = Modifier.weight(1f)
                                )
                                FilterChip(
                                    selected = qqTargetType == "group",
                                    onClick = { qqTargetType = "group" },
                                    label = { Text("群聊（group_id）") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            OutlinedTextField(
                                value = f4,
                                onValueChange = { f4 = it.filter(Char::isDigit) },
                                label = { Text(if (qqTargetType == "group") "group_id（QQ群号）" else "user_id（QQ号）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                    ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("企业ID (corpid)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("应用ID (agentid)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f3, onValueChange = { f3 = it }, label = { Text("应用Secret (corpsecret)") }, visualTransformation = secretVisualTransformation, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f4, onValueChange = { f4 = it }, label = { Text("接收人 (touser 如 @all)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.WECOM_STREAM -> {
                        ExposedDropdownMenuBox(
                            expanded = weComSourceMenuExpanded,
                            onExpandedChange = { weComSourceMenuExpanded = !weComSourceMenuExpanded }
                        ) {
                            val source = weComSources.firstOrNull { it.id == weComSourceId }
                            OutlinedTextField(
                                value = source?.let { "${it.name} · ${it.connectionState.label}" }
                                    ?: if (weComSourceId.isBlank()) "请选择企业微信远程来源" else "关联来源已失效",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("连接来源") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = weComSourceMenuExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = weComSourceMenuExpanded,
                                onDismissRequest = { weComSourceMenuExpanded = false }
                            ) {
                                if (weComSources.isEmpty()) {
                                    DropdownMenuItem(
                                        text = { Text("暂无企业微信远程来源，请先在远程发送中添加") },
                                        onClick = { weComSourceMenuExpanded = false },
                                        enabled = false
                                    )
                                }
                                weComSources.forEach { source ->
                                    DropdownMenuItem(
                                        text = { Text("${source.name} · ${source.connectionState.label}") },
                                        onClick = {
                                            weComSourceId = source.id
                                            testFeedback = null
                                            weComSourceMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = f1,
                            onValueChange = { f1 = it },
                            label = { Text("会话 ID") },
                            placeholder = { Text("群聊填 Chat ID，单聊填成员 User ID") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        val lastCaptured = MultiForwardConfig(context).lastCapturedWeComChatId.trim()
                        if (lastCaptured.isNotBlank() && f1 != lastCaptured) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = BrandGreen.copy(alpha = 0.1f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { f1 = lastCaptured }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "💡 检测到最近远程发信会话：$lastCaptured",
                                        fontSize = 11.sp,
                                        color = BrandGreen,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = "点击填入 ↵",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = BrandGreen
                                    )
                                }
                            }
                        }
                        val selectedSource = weComSources.firstOrNull { it.id == weComSourceId }
                        Text(
                            text = when {
                                selectedSource == null && weComSourceId.isNotBlank() -> "关联来源已失效，请重新选择"
                                selectedSource == null -> "推送通道必须关联一条企业微信远程来源"
                                !selectedSource.enabled -> "关联来源已停用，请先在远程发送中开启"
                                else -> "连接状态：${selectedSource.connectionState.label}"
                            },
                            fontSize = 11.sp,
                            color = if (selectedSource?.enabled == true) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
                        )
                    }
                    ForwardingChannels.FEISHU_APP -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("App ID (cli_xxx)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("App Secret") }, visualTransformation = secretVisualTransformation, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f3, onValueChange = { f3 = it }, label = { Text("接收人 receive_id (open_id)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.DINGTALK -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Webhook URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("加签密钥 Secret (SEC...)") }, visualTransformation = secretVisualTransformation, modifier = Modifier.fillMaxWidth())
                        Text("远程发送（选填）", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(value = f3, onValueChange = { f3 = it }, label = { Text("Stream Client ID") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f4, onValueChange = { f4 = it }, label = { Text("Stream Client Secret") }, visualTransformation = secretVisualTransformation, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f5, onValueChange = { f5 = it }, label = { Text("指令前缀（选填，默认 /发信）") }, modifier = Modifier.fillMaxWidth())
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("启用用户白名单", fontSize = 13.sp)
                                Text(
                                    if (dingTalkWhitelistEnabled) "仅接受名单内用户" else "已关闭：接受所有用户的有效指令",
                                    fontSize = 11.sp,
                                    color = if (dingTalkWhitelistEnabled) MaterialTheme.colorScheme.onSurfaceVariant else GatewayOrange
                                )
                            }
                            Switch(
                                checked = dingTalkWhitelistEnabled,
                                onCheckedChange = { dingTalkWhitelistEnabled = it }
                            )
                        }
                        if (dingTalkWhitelistEnabled) {
                            OutlinedTextField(
                                value = f6,
                                onValueChange = { f6 = it },
                                label = { Text("授权用户 ID（必填）") },
                                isError = f3.isNotBlank() && f4.isNotBlank() && f6.isBlank(),
                                supportingText = {
                                    if (f3.isNotBlank() && f4.isNotBlank() && f6.isBlank()) Text("开启白名单后至少填写一个用户 ID")
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (dingTalkWhitelistEnabled) {
                            OutlinedTextField(value = f7, onValueChange = { f7 = it }, label = { Text("授权群 ID（群内使用必填）") }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                    ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Webhook URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("签名密钥 Secret (选填)") }, visualTransformation = secretVisualTransformation, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.BARK -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("服务器 (默认 https://api.day.app)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("Device Key") }, visualTransformation = secretVisualTransformation, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.WEBSOCKET -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("WebSocket URL (ws://... 或 http://...)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("客户端 Token / 频道号") }, visualTransformation = secretVisualTransformation, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.TELEGRAM -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Bot Token (如 123456:ABC-DEF...)") }, visualTransformation = secretVisualTransformation, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("Chat ID (如 -100123456)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.DISCORD -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Webhook URL (https://discord.com/api/...)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.TENCENT_CLOUD -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("告警 Webhook URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("Secret 签名密钥 (选填)") }, visualTransformation = secretVisualTransformation, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.EMAIL -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("SMTP 服务器 (如 smtp.qq.com)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = emailPort, onValueChange = { emailPort = it.filter(Char::isDigit) }, label = { Text("SMTP 端口 (465 或 587)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(selected = emailSecurity == MultiForwardConfig.EMAIL_SECURITY_SSL, onClick = {
                                emailSecurity = MultiForwardConfig.EMAIL_SECURITY_SSL
                                if (emailPort == "587") emailPort = "465"
                            }, label = { Text("SSL/TLS") })
                            FilterChip(selected = emailSecurity == MultiForwardConfig.EMAIL_SECURITY_STARTTLS, onClick = {
                                emailSecurity = MultiForwardConfig.EMAIL_SECURITY_STARTTLS
                                if (emailPort == "465") emailPort = "587"
                            }, label = { Text("STARTTLS") })
                        }
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("发件账号 (如 xxx@qq.com)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f3, onValueChange = { f3 = it }, label = { Text("授权码 / 密码") }, visualTransformation = secretVisualTransformation, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f4, onValueChange = { f4 = it }, label = { Text("接收邮箱 (多个用逗号隔开)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.SMS_DIRECT -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("目标接收手机号码") }, modifier = Modifier.fillMaxWidth())
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("仅断网时发送", modifier = Modifier.weight(1f))
                            Switch(
                                checked = smsDirectOnlyOnNoNetwork,
                                onCheckedChange = { smsDirectOnlyOnNoNetwork = it }
                            )
                        }
                        Text(
                            "开启后，仅在当前网络不可用时自动转发；通道测试仍会发送短信。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ForwardingChannels.CUSTOM_WEBHOOK -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("请求地址") }, modifier = Modifier.fillMaxWidth())
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text("接口转发方式", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf("GET", "POST", "PUT").forEach { method ->
                                    FilterChip(
                                        selected = customWebhookMethod.equals(method, ignoreCase = true),
                                        onClick = { customWebhookMethod = method },
                                        label = { Text(method) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }
                            Text(
                                if (customWebhookMethod.equals("GET", ignoreCase = true)) {
                                    "GET：请求体模板会编码后追加到 URL 查询参数"
                                } else {
                                    "${customWebhookMethod.uppercase()}：按下方 Content-Type 发送请求体"
                                },
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedTextField(
                            value = customWebhookContentType,
                            onValueChange = { customWebhookContentType = it },
                            label = { Text("Content-Type") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = f2,
                            onValueChange = { f2 = it },
                            label = { Text("自定义 Headers（JSON 或每行 Key: Value）") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = customWebhookBody,
                            onValueChange = { customWebhookBody = it },
                            label = { Text("请求体模板") },
                            supportingText = { Text("支持 {receiver} [receiver] {sim} [sim] {from} [from] {msg} [msg] {time} [time]；{receiver} 为接收卡槽本机号码") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            minLines = 5,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    ForwardingChannels.GOTIFY -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Gotify 服务地址 (http://... 或 https://...)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("App Token") }, visualTransformation = secretVisualTransformation, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.WXPUSHER -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("WxPusher AppToken") }, visualTransformation = secretVisualTransformation, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("接收 UID_... 或数字 Topic ID") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.NTFY -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("ntfy 服务地址") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            value = f2,
                            onValueChange = { f2 = it },
                            label = { Text("Topic") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = f3,
                            onValueChange = { f3 = it },
                            label = { Text("访问 Token（选填）") },
                            visualTransformation = secretVisualTransformation,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(value = f4, onValueChange = { f4 = it }, label = { Text("优先级（min/low/default/high/max）") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            value = f5,
                            onValueChange = { f5 = it },
                            label = { Text("标签 Tags（选填，逗号分隔）") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(value = f6, onValueChange = { f6 = it }, label = { Text("点击打开链接（选填）") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.SERVERCHAN3 -> {
                        OutlinedTextField(
                            value = f1,
                            onValueChange = { f1 = it },
                            label = { Text("SendKey (例如 sctp123456t...)") },
                            visualTransformation = secretVisualTransformation,
                            placeholder = { Text("从方糖 Server酱³ 控制台获取") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = f2,
                            onValueChange = { f2 = it },
                            label = { Text("标签 Tags（选填，用 | 分隔）") },
                            placeholder = { Text("例如 验证码|重要通知") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    else -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Webhook 地址") }, modifier = Modifier.fillMaxWidth())
                    }
                }

                if (!hasRequiredConfiguration()) {
                    Text(
                        text = "请先填写当前通道的必填配置，再保存实例",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                testFeedback?.let { feedback ->
                    val current = currentInstance()
                    val matchesTest = testedConfiguration == (current.channelType to current.configJson)
                    Text(
                        text = if (matchesTest) feedback else "配置已变更，请重新测试；上次结果仅适用于测试时的配置",
                        fontSize = 11.sp,
                        color = if (!matchesTest) MaterialTheme.colorScheme.onSurfaceVariant
                            else if (feedback.startsWith("测试成功")) BrandGreen else MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaved(currentInstance()) },
                enabled = !isTesting && hasRequiredConfiguration()
            ) {
                Text("保存实例")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss, enabled = !isTesting) { Text("取消") }
                OutlinedButton(
                    onClick = {
                        val snapshot = currentInstance()
                        isTesting = true
                        testFeedback = null
                        testedConfiguration = snapshot.channelType to snapshot.configJson
                        scope.launch {
                            try {
                                val result = ChannelTestSender.sendTestInstance(context, snapshot)
                                testFeedback = result.fold(
                                    onSuccess = { "测试成功：$it" },
                                    onFailure = { "测试失败：${it.message ?: "未知错误"}" }
                                )
                            } finally {
                                isTesting = false
                            }
                        }
                    },
                    enabled = !isTesting && hasRequiredConfiguration()
                ) {
                    if (isTesting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Text("测试")
                    }
                }
            }
        }
    )
}

@Composable
fun ChannelFullTutorialDialog(onDismiss: () -> Unit) {
    val tutorialPages = linkedMapOf(
        "快速开始" to listOf(
            "推荐流程" to "添加并保存通道 → 点击测试 → 新建规则 → 选择具体通道实例 → 开启规则。",
            "多实例" to "同一种通道可以创建多份配置，例如 Bark A、Bark B。规则可以只发给其中一个，也可以同时选择多个实例。",
            "先测试再启用" to "测试成功通常只代表服务端已受理请求，不保证接收设备已弹出通知；还需开启实例，并在规则中选中它。"
        ),
        "转发通道" to listOf(
            "PushPlus" to "登录 pushplus.plus，在一对一推送中复制 Token；群组推送可再填写 Topic。",
            "WxPusher / 微信测试号" to "WxPusher 填 AppToken 和接收者 UID 或 Topic ID；微信测试号填 App ID、App Secret、关注者 openID 和模板 ID。",
            "企业微信应用 / 群机器人" to "应用消息填写企业 ID、AgentId、Secret 和成员 User ID；群机器人填写群聊 Webhook。两者是不同的接口。",
            "企业微信智能机器人" to "先在远程发送页启用长连接来源，再选择对应来源与接收会话 ID；连接就绪后测试推送。",
            "钉钉 / 飞书机器人" to "在群聊中添加自定义机器人，复制 Webhook；开启加签时还要填写对应 Secret。",
            "飞书自建应用" to "在飞书开放平台创建企业自建应用，填写 App ID、App Secret 和接收人的 open_id。",
            "Bark" to "在 iPhone 的 Bark App 中复制 Device Key；自建服务可填写自己的 HTTPS 或局域网 HTTP 地址。",
            "Telegram / Discord" to "Telegram 通过 BotFather 获取 Token 和 Chat ID；Discord 从频道 Webhooks 中复制地址。",
            "Gotify" to "填写 Gotify 服务地址和应用 Token。公网服务使用 HTTPS，局域网可使用 HTTP。",
            "ntfy" to "填写 ntfy 服务地址与 Topic，私有主题再填写访问 Token。不要使用容易猜到的公开 Topic 传输验证码。",
            "QQ / OneBot / NapCat" to "Qmsg 模式填写 Key；OneBot 11 / NapCat 填写 HTTP 地址、Access Token 和 user_id 或 group_id。",
            "邮件 / WebSocket" to "邮件使用通用 SMTP：填写服务器、端口、SSL 或 STARTTLS、账号、密码或应用授权码及收件人；仅允许 OAuth2 登录的账号暂不支持。WebSocket 填写服务地址及可选 Token。",
            "腾讯云自定义告警" to "填写云监控告警回调地址及 Secret；测试受理不等于短信最终送达，是否产生短信由云端告警配置决定。",
            "短信直发 / 自定义 Webhook" to "短信直发会产生运营商费用；Webhook 支持 GET、POST、PUT，可配置 Content-Type、Headers 与请求体模板，并使用 [receiver] 获取卡槽本机号码。",
            "Server酱³" to "官网登录获取 SendKey；手机安装 Server酱 App 授权厂商通道后即可收到免后台推送，省电无常驻。",
            "通道组" to "把多个已配置实例组合后并发发送。不要把通道组互相循环引用。"
        ),
        "远程发送" to listOf(
            "支持来源" to "共 7 类：短信指令、钉钉 Stream、飞书长连接、企业微信智能机器人长连接、Telegram Bot、WebSocket 和邮箱 IMAP。QQ / OneBot 目前仅用于转发，不是远程来源；企业微信自建应用转发也不是智能机器人远程接入。",
            "指令格式" to "示例：/发信 10086 1；指定卡槽：/发信 SIM1 10086 1 或 /发信 SIM2 10086 1；使用系统默认卡：/发信 默认 10086 1。省略卡槽时按该来源的发送卡设置解析。自定义前缀请使用该实例设置的前缀。",
            "白名单" to "关闭时接受所有符合格式的用户；开启后必须填写授权用户，群聊还应填写授权群组。",
            "卡槽与限制" to "每个来源可设置默认卡槽、免打扰和限额。SIM1 / SIM2 指物理卡槽，不是订阅 ID；换卡可能改变订阅 ID。指定卡失效时应停止并提示，不能静默换卡。自定义名称和号码只影响显示。",
            "回执" to "钉钉、飞书、企业微信智能机器人、Telegram、WebSocket 支持原路回执；短信和邮箱来源需配置回执转发目标。已提交表示系统 API 接收；发送成功来自短信发送回调；送达依赖运营商报告，不能把前两项当作对方已收到。",
            "安全提示" to "远程发送会真实调用本机 SIM 卡。请启用白名单、设置限额，并只在本人或明确授权的设备上使用。"
        ),
        "长连接逐步配置" to listOf(
            "钉钉 Stream" to "1. 在钉钉开放平台创建组织内部应用，取得 Client ID / Client Secret。2. 添加机器人能力，消息接收选择 Stream，发布并确认可见范围。3. 在远程发送创建钉钉来源，填写凭据、保存、启用，等连接就绪。4. 私聊发 /发信 10086 1；群聊先添加并 @机器人。5. 在目标群 @机器人 发送“申请用户白名单”或“申请群白名单”，复制回复的用户 ID 与群会话 ID 到对应白名单。申请仅展示 ID，不自动授权、不发短信；不能填手机号或群名。群 Webhook 仅发通知，不能接收 Stream 指令。",
            "飞书长连接" to "1. 创建企业自建应用，开启机器人，取得 App ID / App Secret。2. 在事件与回调订阅接收消息 v2.0（im.message.receive_v1），选择使用长连接接收事件；配置接收消息及机器人发送消息所需权限。3. 创建来源并启用连接；如平台提示尚未建立连接，保持本应用连接后再保存平台配置。4. 发布应用版本并确认成员可用范围。5. 私聊测试；群聊添加机器人并 @它。可在群内 @机器人 发送“申请用户白名单”或“申请群白名单”，复制回复的 open_id 与 chat_id 到对应白名单；申请不自动授权。WebHook 群机器人不能代替自建应用。",
            "企业微信智能机器人" to "在企业微信创建支持 API 长连接的智能机器人，取得 bot_id 与 secret；在远程发送新建企业微信来源，保存并启用，确认鉴权和连接状态后私聊 /发信 10086 1，群聊需 @机器人。在群内 @机器人 发送“申请用户白名单”或“申请群白名单”，复制回复的 from.userid 与 chatid 到对应白名单；申请不自动授权、不发短信。企业 ID / AgentId / 应用 Secret 是另一套应用消息凭据，不可混填。",
            "Telegram" to "通过 BotFather 创建 Bot 并取得 Token，用户先与 Bot 对话；创建来源启用长轮询。已有 Webhook 需在平台删除后再轮询；避免多个程序同时 getUpdates。在群内 @机器人 发送“申请用户白名单”或“申请群白名单”，复制回复的数字 user_id / chat_id 到对应白名单；申请不自动授权、不发短信。群聊建议使用指令并确认 Bot 隐私设置。",
            "WebSocket" to "填写自己的 ws:// 或 wss:// 服务端地址和接口约定的 Token；公网优先 wss://。它是自定义服务器客户端，不是 OneBot 11 适配器。先确认服务端与本应用消息字段、鉴权和回执协议一致，再测试 send_sms。",
            "邮箱 IMAP" to "开启邮箱 IMAP，填写主机、端口、账号和密码或应用授权码；隐式 TLS 常用 993，STARTTLS 端口由服务商提供。启用后轮询 INBOX，未读邮件的主题或正文使用 /发信 10086 1；发件人白名单填邮箱地址。只支持现有账号密码认证，不是 OAuth2；正文编码和附件不能当作均已支持，先用纯文本邮件验证。",
            "排障顺序" to "先看连接 / 鉴权，再看事件是否到达，再看白名单、规则、限额、卡槽与权限，最后看发送状态和回执。连接就绪不代表平台已发布事件权限。不要把同一来源凭据交给多台同时监听的设备，以免消息被别的客户端消费。"
        ),
        "邮件转发与备份" to listOf(
            "SMTP 配置" to "邮件转发是发邮件；IMAP 远程来源是收邮件执行指令。SMTP 不限 QQ / 163，服务商允许账号密码或应用密码即可填写自定义主机。SSL/TLS 常用 465，STARTTLS 常用 587；账号和发件地址应符合服务商授权。多个收件人用英文逗号或分号分隔。测试后查收件箱、垃圾箱与退信，SMTP 受理不等于送达。",
            "文件备份" to "经典版：设置 → 备份与恢复；开发版：运维 → 备份与恢复。可导出 JSON 文件、选择文件导入及剪贴板导入导出。导入先查看预览，并在恢复前生成本机回滚快照。备份包含通道、规则等配置，不含系统短信历史或系统授予的权限；普通导出文件含凭据，请妥善保存。",
            "SIM 名称" to "在转发设置分别填写 SIM1 / SIM2 名称和号码。运维链路诊断可查看物理卡槽与订阅 ID 映射；号码可能无法自动读取。未知接收卡不会按订阅 ID 猜成 SIM3。旧历史记录不会自动重写名称。"
        ),
        "交流与反馈" to listOf(
            "QQ 交流群" to "569321348；也可在关于页面点击群号复制。",
            "提交 Bug" to "GitHub Issues：2756826865/android-sms-forwarder。请附版本、经典 / 开发版、机型系统、复现步骤、错误状态与脱敏日志；不要公开 Token、授权码、备份文件、完整号码和验证码。"
        ),
        "规则" to listOf(
            "匹配内容" to "可以按发件人、正文关键词、正则表达式、接收卡槽等条件筛选短信。",
            "选择实例" to "规则关联的是具体通道实例，不只是通道类型。同一渠道的多个用户可以分别选择。",
            "多个目标" to "一条规则可同时选择多个实例，例如验证码发给 Bark A，账单同时发给 Bark A 和 Bark B。",
            "优先级" to "优先级高的规则先匹配；相同优先级按规则列表顺序执行。是否继续匹配决定后续规则还会不会执行。",
            "测试建议" to "先用精确关键词测试，再逐步增加正则条件，避免规则过宽导致无关短信外发。"
        ),
        "消息模板" to listOf(
            "模板作用" to "控制转发消息的标题和正文格式，不改变原短信内容。",
            "常用变量" to "可插入发件人、短信正文、接收时间、卡槽等变量；保存前可使用预览确认结果。",
            "正则替换" to "用于隐藏号码、验证码或替换固定文本。替换规则按列表顺序执行。"
        ),
        "自动回复" to listOf(
            "使用方式" to "收到符合条件的短信后，由本机 SIM 卡自动回复预设内容。",
            "注意事项" to "自动回复会产生运营商短信费用，应限制联系人或关键词，并避免与其他自动化形成循环。"
        ),
        "来电提醒" to listOf(
            "提醒范围" to "可发送未接来电提醒，也可按设置发送已接来电记录。",
            "选择通道" to "提醒可以选择一个或多个已配置的通道实例，不会自动发送到全部渠道。",
            "模板" to "自定义模板可使用来电类型、号码、联系人、时长、时间和卡槽变量。"
        ),
        "电量提醒" to listOf(
            "触发条件" to "电量低于设定值时发送提醒，恢复充电或电量恢复时可按设置再次通知。",
            "选择通道" to "请明确选择接收提醒的通道实例，并先完成通道测试。"
        ),
        "验证码写入" to listOf(
            "剪贴板" to "识别短信验证码后自动写入系统剪贴板，方便在其他应用粘贴。",
            "悬浮胶囊" to "需要显示悬浮提示时，请按系统要求授予显示在其他应用上层权限。"
        ),
        "定时心跳" to listOf(
            "用途" to "按设定周期发送设备、电量和 SIM 卡状态，用于确认手机仍在正常运行。",
            "测试" to "启用前先测试发送，并确保至少有一个可用的转发通道。"
        ),
        "定时短信" to listOf(
            "计划发送" to "设置目标号码、内容和发送时间后，系统将在计划时间调用本机 SIM 卡发送。",
            "注意" to "请允许应用后台运行，并确认发送短信权限和卡槽状态正常。"
        ),
        "批量发送" to listOf(
            "使用方式" to "填写多个目标号码和短信内容，由本机 SIM 卡依次提交发送。",
            "费用与合规" to "批量短信可能产生较多运营商费用，只能向已授权的接收人发送。"
        ),
        "常见问题" to listOf(
            "测试成功但没有转发" to "检查实例开关、规则开关、规则所选实例和系统后台运行权限。",
            "升级后找不到旧通道" to "应用启动时会自动迁移真实已配置的旧凭据，不需要手动点击导入。",
            "远程来源无法连接" to "检查凭据、网络、白名单和平台后台设置；Telegram Webhook 与长轮询不能同时使用。",
            "收不到送达回执" to "运营商和设备必须支持送达报告，并在设置中开启送达回执。"
        )
    )
    var selectedPage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        modifier = Modifier.navigationBarsPadding(),
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (selectedPage != null) {
                    TextButton(onClick = { selectedPage = null }) { Text("返回") }
                }
                Text(
                    text = selectedPage ?: "使用教程",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val page = selectedPage
                if (page == null) {
                    Text(
                        "选择需要了解的功能，教程会留在当前页面内显示。",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    tutorialPages.keys.forEach { name ->
                        Surface(
                            onClick = { selectedPage = name },
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Text("查看", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                } else {
                    TutorialSection(title = page, items = tutorialPages[page].orEmpty())
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("我已了解")
            }
        }
    )
}

@Composable
private fun TutorialSection(title: String, items: List<Pair<String, String>>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
        items.forEach { (name, desc) ->
            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    Text(desc, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 15.sp)
                }
            }
        }
    }
}

@Composable
fun ForwardSettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember { MultiForwardConfig(context) }
    var markAsRead by remember { mutableStateOf(config.markAsReadAfterForward) }
    var simOneLabel by remember { mutableStateOf(config.simOneLabel) }
    var simTwoLabel by remember { mutableStateOf(config.simTwoLabel) }
    var rootEnhancementEnabled by remember { mutableStateOf(config.rootEnhancementEnabled) }
    var rootCheckRunning by remember { mutableStateOf(false) }
    var rootStatusText by remember { mutableStateOf("尚未检测 Root") }
    var shizukuStatusText by remember { mutableStateOf(ShizukuEnhancementManager.checkStatus(context).detail) }
    var rootDiagnosticText by remember { mutableStateOf("") }
    var delayEnabled by remember { mutableStateOf(config.forwardingDelaySeconds > 0) }
    var delaySeconds by remember { mutableStateOf(if (config.forwardingDelaySeconds > 0) config.forwardingDelaySeconds else 5) }

    AlertDialog(
        modifier = Modifier.navigationBarsPadding(),
        onDismissRequest = onDismiss,
        title = {
            Text("⚙️ 转发高级设置", fontWeight = FontWeight.Bold, fontSize = 18.sp)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. 转发后标记为已读
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                        Text("转发成功后标记为已读", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text(
                            "短信成功转发到所有配置通道后，自动将系统及应用内的该条短信标为已读并消除通知",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = markAsRead,
                        onCheckedChange = {
                            markAsRead = it
                            config.markAsReadAfterForward = it
                        }
                    )
                }

                // 2. 双卡显示名称（复用经典版配置，仅影响显示，不改变发送卡解析）
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("双卡显示名称", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(
                        "用于转发内容、卡槽选择和状态显示；留空时跟随系统 SIM 与运营商名称",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = simOneLabel,
                        onValueChange = { simOneLabel = it.take(24) },
                        label = { Text("卡一名称") },
                        placeholder = { Text("例如：工作主卡") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = simTwoLabel,
                        onValueChange = { simTwoLabel = it.take(24) },
                        label = { Text("卡二名称") },
                        placeholder = { Text("例如：生活副卡") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 3. Root 增强模式（开发版实验总开关；当前不执行任何 su 命令）
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("Root 增强模式（实验）", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                "开启后检查 Root、补同步近一天短信，必要时尝试恢复 READ_SMS 权限。同时开启后台保活时启动实验守护；不补转发历史短信。",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = rootEnhancementEnabled,
                            onCheckedChange = {
                                rootEnhancementEnabled = it
                                config.rootEnhancementEnabled = it
                                org.fossify.messages.security.root.RootMaintenanceWorker.sync(context)
                            }
                        )
                    }

                    if (rootEnhancementEnabled) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text("🛡️ 特权增强双引擎 (Root / Shizuku 免 Root)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                Text(
                                    "用于强制绑定默认短信角色、放行 WRITE_SMS / RECEIVE_SMS 及注入电池白名单。支持 Root 或 Shizuku(免Root无线调试)。",
                                    fontSize = 11.5.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // === 引擎 A: Root 模式 ===
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Text("【Root 引擎 (uid=0)】", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text(rootStatusText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        enabled = !rootCheckRunning,
                                        onClick = {
                                            rootCheckRunning = true
                                            scope.launch {
                                                val status = RootEnhancementManager.checkRoot()
                                                rootStatusText = status.detail
                                                rootCheckRunning = false
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text(if (rootCheckRunning) "检测…" else "检测 Root", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        enabled = !rootCheckRunning,
                                        onClick = {
                                            rootCheckRunning = true
                                            scope.launch {
                                                val report = RootEnhancementManager.collectReadOnlyDiagnostics(context)
                                                rootStatusText = report.rootStatus.detail
                                                rootDiagnosticText = "--- Root 底层只读诊断 ---\n" + report.lines.joinToString("\n")
                                                rootCheckRunning = false
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text("只读诊断", fontSize = 11.sp)
                                    }
                                    Button(
                                        enabled = !rootCheckRunning,
                                        onClick = {
                                            rootCheckRunning = true
                                            scope.launch {
                                                val fixResult = RootEnhancementManager.applyRootFix(context)
                                                RootEnhancementManager.restoreSmsReadAccess(context)
                                                val report = RootEnhancementManager.collectReadOnlyDiagnostics(context)
                                                rootStatusText = "Root 修复完成 (${fixResult.successCount}/${fixResult.totalCount})"
                                                val fixLog = fixResult.details.joinToString("\n") { (k, v) -> if (v) "✓ $k: 成功" else "✗ $k: 失败" }
                                                rootDiagnosticText = "--- 🚀 Root 一键修复结果 ---\n" + fixLog + "\n\n--- 修复后底层实时状态 ---\n" + report.lines.joinToString("\n")
                                                rootCheckRunning = false
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text("🚀 Root 强制修复", fontSize = 11.sp)
                                    }
                                }

                                // === 引擎 B: Shizuku 免 Root 模式 ===
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                                Text("【Shizuku 引擎 (免 Root · ADB 级)】", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                                Text(shizukuStatusText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        enabled = !rootCheckRunning,
                                        onClick = {
                                            val status = ShizukuEnhancementManager.checkStatus(context)
                                            shizukuStatusText = status.detail
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text("检查状态", fontSize = 11.sp)
                                    }
                                    OutlinedButton(
                                        enabled = !rootCheckRunning,
                                        onClick = {
                                            ShizukuEnhancementManager.requestPermission()
                                            val status = ShizukuEnhancementManager.checkStatus(context)
                                            shizukuStatusText = status.detail
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text("申请授权", fontSize = 11.sp)
                                    }
                                    Button(
                                        enabled = !rootCheckRunning,
                                        onClick = {
                                            rootCheckRunning = true
                                            scope.launch {
                                                val status = ShizukuEnhancementManager.checkStatus(context)
                                                if (status.state != ShizukuEnhancementManager.ShizukuState.READY) {
                                                    shizukuStatusText = status.detail
                                                    rootDiagnosticText = "Shizuku 尚未就绪，请先启动无线调试并授权本应用。"
                                                    rootCheckRunning = false
                                                    return@launch
                                                }
                                                val fixResult = ShizukuEnhancementManager.applyShizukuFix(context)
                                                val report = ShizukuEnhancementManager.collectShizukuDiagnostics(context)
                                                shizukuStatusText = "Shizuku 修复完成 (${fixResult.successCount}/${fixResult.totalCount})"
                                                val fixLog = fixResult.details.joinToString("\n") { (k, v) -> if (v) "✓ $k: 成功" else "✗ $k: 失败" }
                                                rootDiagnosticText = "--- ⚡ Shizuku 一键修复结果 ---\n" + fixLog + "\n\n--- 修复后底层状态 ---\n" + report.lines.joinToString("\n")
                                                rootCheckRunning = false
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Text("⚡ Shizuku 强制修复", fontSize = 11.sp)
                                    }
                                }

                                if (rootDiagnosticText.isNotBlank()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text(
                                            rootDiagnosticText,
                                            fontSize = 10.sp,
                                            lineHeight = 14.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(8.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 4. 延时后台转发任务
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                            Text("延时后台转发任务", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            Text(
                                "开启后可在接收短信后延时指定秒数再执行转发（防瞬时风控或等待本地同步）",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = delayEnabled,
                            onCheckedChange = { enabled ->
                                delayEnabled = enabled
                                if (!enabled) {
                                    config.forwardingDelaySeconds = 0
                                } else {
                                    config.forwardingDelaySeconds = delaySeconds
                                }
                            }
                        )
                    }

                    if (delayEnabled) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp, vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("延时时长", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${delaySeconds} 秒", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = BrandGreen)
                            }
                            Slider(
                                value = delaySeconds.toFloat(),
                                onValueChange = {
                                    val sec = it.toInt().coerceIn(1, 60)
                                    delaySeconds = sec
                                    config.forwardingDelaySeconds = sec
                                },
                                valueRange = 1f..60f,
                                steps = 59,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "支持 1~60 秒自由调节（默认 5 秒）",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    config.simOneLabel = simOneLabel
                    config.simTwoLabel = simTwoLabel
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
            ) {
                Text("完成")
            }
        }
    )
}
