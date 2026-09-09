package org.fossify.messages.ui.compose.forwarding

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import org.fossify.messages.ui.compose.rules.RuleStudioScreen
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
    ChannelTypeDefinition(ForwardingChannels.PUSHPLUS, "PushPlus 微信推送", "微信服务号一对一或群组推送", "💬", ChannelCategory.WECHAT),
    ChannelTypeDefinition(ForwardingChannels.WECHAT_TEST, "微信测试号", "微信公众平台测试号模板消息直推", "🟢", ChannelCategory.WECHAT),
    ChannelTypeDefinition(ForwardingChannels.WECOM_BOT, "企业微信群机器人", "企业微信内部群 Webhook 机器人", "🤖", ChannelCategory.WECHAT),
    ChannelTypeDefinition(ForwardingChannels.WECOM_STREAM, "企业微信智能机器人 (长连接)", "通过官方长连接主动推送消息（免公网IP）", "💬", ChannelCategory.WECHAT),
    ChannelTypeDefinition(ForwardingChannels.WECOM_APP, "企业微信应用号", "企业微信自建应用 Agent 卡片消息", "💼", ChannelCategory.WECHAT),
    ChannelTypeDefinition(ForwardingChannels.DINGTALK, "钉钉群机器人", "钉钉群自定义机器人 Webhook + 加签", "🤖", ChannelCategory.WORK),
    ChannelTypeDefinition(ForwardingChannels.FEISHU_BOT, "飞书群机器人", "飞书群自定义机器人 Webhook + 加签", "🕊️", ChannelCategory.WORK),
    ChannelTypeDefinition(ForwardingChannels.FEISHU_APP, "飞书自建应用", "飞书开放平台企业自建应用", "🏢", ChannelCategory.WORK),
    ChannelTypeDefinition(ForwardingChannels.QQ, "QQ 消息 (Qmsg/OneBot)", "支持 Qmsg 酱或 OneBot 协议推送", "🐧", ChannelCategory.INSTANT),
    ChannelTypeDefinition(ForwardingChannels.BARK, "Bark (iOS)", "苹果设备专属 APNs 极速低功耗推送", "🔔", ChannelCategory.INSTANT),
    ChannelTypeDefinition(ForwardingChannels.TELEGRAM, "Telegram 机器人", "Telegram Bot API 异步消息推送", "✈️", ChannelCategory.INSTANT),
    ChannelTypeDefinition(ForwardingChannels.DISCORD, "Discord 群机器人", "Discord Webhook 频道卡片推送", "🎮", ChannelCategory.INSTANT),
    ChannelTypeDefinition(ForwardingChannels.GOTIFY, "Gotify 消息推送", "自建 Gotify 服务即时推送", "🚀", ChannelCategory.INSTANT),
    ChannelTypeDefinition(ForwardingChannels.NTFY, "ntfy 推送", "支持官方或自建 ntfy 服务与独立 Topic", "📣", ChannelCategory.INSTANT),
    ChannelTypeDefinition(ForwardingChannels.WEBSOCKET, "WebSocket 客户端", "长连接实时推流，毫秒级响应", "🔌", ChannelCategory.INSTANT),
    ChannelTypeDefinition(ForwardingChannels.EMAIL, "邮件消息 (SMTP)", "标准 SMTP 协议发信 (SSL/STARTTLS)", "📧", ChannelCategory.CLOUD),
    ChannelTypeDefinition(ForwardingChannels.TENCENT_CLOUD, "腾讯云自定义告警", "腾讯云监控告警回调，触发短信与通知", "☁️", ChannelCategory.CLOUD),
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
        【OneBot模式】: 填入自建的 go-cqhttp / NapCat HTTP Webhook 地址
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
        1. 登录腾讯云控制台 -> 云监控 -> 告警回调设置
        2. 复制生成的告警 Webhook URL 与 Secret 填入，可触发免费短信提醒
    """.trimIndent()
    ForwardingChannels.EMAIL -> """
        以 QQ 邮箱为例:
        1. SMTP 服务器: smtp.qq.com (端口 465 SSL)
        2. 发件账号: 您的 QQ 邮箱
        3. 授权码: QQ邮箱网页版 ->【设置】->【账户】-> 开启 POP3/SMTP 生成的16位授权码
        4. 接收邮箱: 填入接收通知的目标邮箱
    """.trimIndent()
    ForwardingChannels.SMS_DIRECT -> """
        通过手机插入的备用 SIM 卡，直接以短信方式重发给指定的目标手机号。
        填入目标手机号码即可。
    """.trimIndent()
    ForwardingChannels.CUSTOM_WEBHOOK -> """
        1. 填写 HTTP 地址，并选择 GET、POST 或 PUT
        2. 可设置 Content-Type、Headers 和请求体模板
        3. 模板支持 [title]、[msg]、[from]、[time]、[sim]
        4. GET 模式将模板作为查询参数；公网地址应使用 HTTPS
        5. 保存后先点击测试，接收端返回 HTTP 2xx 才算成功
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
        ForwardingChannels.NTFY -> {
            val serverUrl = instance.optString("serverUrl")
            val topic = instance.optString("topic")
            if (topic.isNotBlank()) "${serverUrl.ifBlank { "https://ntfy.sh" }.take(20)} / $topic" else "未配置 Topic"
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

    var testingStates by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var testResults by remember { mutableStateOf(mapOf<String, String>()) }

    var editingInstance by remember { mutableStateOf<ForwardingChannelInstance?>(null) }
    var isAddingNew by remember { mutableStateOf(false) }
    var instanceToDelete by remember { mutableStateOf<ForwardingChannelInstance?>(null) }
    var showFullTutorialDialog by remember { mutableStateOf(false) }

    val allChannelItems = remember(instances) {
        val removedTypes = setOf(
            ForwardingChannels.WECHAT_TEST,
            ForwardingChannels.WECOM_BOT,
            ForwardingChannels.WECOM_APP
        )
        val supportedDefinitions = ALL_CHANNEL_TYPE_DEFINITIONS.filterNot { it.type in removedTypes }
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
            supportedDefinitions.none { it.type == instance.channelType } && instance.channelType !in removedTypes
        }
    }
    val visibleInstances = if (selectedSection == "custom") instances else allChannelItems

    val isDark = isSystemInDarkTheme()
    val pageBgColor = if (isDark) DarkBackground else AppBackground
    val primaryTextColor = if (isDark) Color.White else TextPrimary
    val secondaryTextColor = if (isDark) Color(0xFF9CA3AF) else TextSecondary

    Scaffold(
        containerColor = pageBgColor
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

                    item { Spacer(modifier = Modifier.height(100.dp)) }
                }
            }
        }
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
                else -> ""
            }
        )
    }

    var f2 by remember {
        mutableStateOf(
            when (selectedType) {
                ForwardingChannels.PUSHPLUS -> existingInstance?.optString("topic") ?: ""
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
                else -> ""
            }
        )
    }

    var f3 by remember {
        mutableStateOf(
            when (selectedType) {
                ForwardingChannels.WECHAT_TEST -> existingInstance?.optString("templateId") ?: ""
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
    var weComSourceId by remember {
        mutableStateOf(existingInstance?.optString("sourceInstanceId").orEmpty())
    }

    var typeMenuExpanded by remember { mutableStateOf(false) }
    var weComSourceMenuExpanded by remember { mutableStateOf(false) }
    var isTesting by remember { mutableStateOf(false) }
    var testFeedback by remember { mutableStateOf<String?>(null) }
    var testedConfiguration by remember { mutableStateOf<Pair<String, String>?>(null) }

    fun hasRequiredConfiguration(): Boolean = when (selectedType) {
        ForwardingChannels.PUSHPLUS -> f1.isNotBlank()
        ForwardingChannels.WECHAT_TEST -> listOf(f1, f2, f3, f4).all { it.isNotBlank() }
        ForwardingChannels.QQ -> f1.isNotBlank()
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
        ForwardingChannels.EMAIL -> listOf(f1, f2, f3, f4).all { it.isNotBlank() }
        ForwardingChannels.SMS_DIRECT -> f1.isNotBlank()
        ForwardingChannels.CUSTOM_WEBHOOK -> f1.isNotBlank() &&
            customWebhookMethod.uppercase() in setOf("GET", "POST", "PUT")
        ForwardingChannels.GOTIFY -> f1.isNotBlank() && f2.isNotBlank()
        ForwardingChannels.NTFY -> f1.isNotBlank() && f2.isNotBlank()
        else -> false
    }

    fun currentInstance(): ForwardingChannelInstance {
        // Keep persisted settings that this editor does not expose (for example SMTP port).
        val configJson = existingInstance?.takeIf { it.channelType == selectedType }?.let {
            runCatching { JSONObject(it.configJson) }.getOrNull()
        } ?: JSONObject()
        when (selectedType) {
            ForwardingChannels.PUSHPLUS -> configJson.put("token", f1).put("topic", f2)
            ForwardingChannels.WECHAT_TEST -> configJson.put("appId", f1).put("appSecret", f2).put("templateId", f3).put("openId", f4)
            ForwardingChannels.QQ -> {
                // Only one provider target may remain after changing the QQ provider.
                configJson.remove("qmsgKey")
                configJson.remove("onebotUrl")
                if (f2 == "qmsg" || !f1.startsWith("http")) configJson.put("qmsgKey", f1).put("type", "qmsg")
                else configJson.put("onebotUrl", f1).put("type", "onebot")
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
                if (!configJson.has("port")) configJson.put("port", 465)
                configJson.put("host", f1).put("user", f2).put("password", f3).put("recipients", f4)
            }
            ForwardingChannels.SMS_DIRECT -> configJson.put("phone", f1)
            ForwardingChannels.CUSTOM_WEBHOOK -> configJson.put("url", f1).put("headers", f2)
                .put("method", customWebhookMethod.uppercase())
                .put("contentType", customWebhookContentType)
                .put("bodyTemplate", customWebhookBody)
            ForwardingChannels.GOTIFY -> configJson.put("serverUrl", f1).put("token", f2)
            ForwardingChannels.NTFY -> configJson.put("serverUrl", f1).put("topic", f2).put("token", f3)
                .put("priority", f4).put("tags", f5).put("clickUrl", f6)
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
        onDismissRequest = onDismiss,
        title = {
            Text(if (isEditing) "⚙️ 编辑通道实例" else "✨ 添加新通道实例")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Token (一对一密钥)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("Topic (群组编码 选填)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.WECHAT_TEST -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("appID") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("appsecret") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f3, onValueChange = { f3 = it }, label = { Text("template_id (模板ID)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f4, onValueChange = { f4 = it }, label = { Text("openID (接收者微信号)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.QQ -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Qmsg Key 或 OneBot Webhook URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("协议类型 (qmsg / onebot)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("企业ID (corpid)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("应用ID (agentid)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f3, onValueChange = { f3 = it }, label = { Text("应用Secret (corpsecret)") }, modifier = Modifier.fillMaxWidth())
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
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("App Secret") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f3, onValueChange = { f3 = it }, label = { Text("接收人 receive_id (open_id)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.DINGTALK -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Webhook URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("加签密钥 Secret (SEC...)") }, modifier = Modifier.fillMaxWidth())
                        Text("远程发送（选填）", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(value = f3, onValueChange = { f3 = it }, label = { Text("Stream Client ID") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f4, onValueChange = { f4 = it }, label = { Text("Stream Client Secret") }, modifier = Modifier.fillMaxWidth())
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
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("签名密钥 Secret (选填)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.BARK -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("服务器 (默认 https://api.day.app)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("Device Key") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.WEBSOCKET -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("WebSocket URL (ws://... 或 http://...)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("客户端 Token / 频道号") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.TELEGRAM -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Bot Token (如 123456:ABC-DEF...)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("Chat ID (如 -100123456)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.DISCORD -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Webhook URL (https://discord.com/api/...)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.TENCENT_CLOUD -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("告警 Webhook URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("Secret 签名密钥 (选填)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.EMAIL -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("SMTP 服务器 (如 smtp.qq.com)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("发件账号 (如 xxx@qq.com)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f3, onValueChange = { f3 = it }, label = { Text("授权码 / 密码") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f4, onValueChange = { f4 = it }, label = { Text("接收邮箱 (多个用逗号隔开)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.SMS_DIRECT -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("目标接收手机号码") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.CUSTOM_WEBHOOK -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("请求地址") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            value = customWebhookMethod,
                            onValueChange = { customWebhookMethod = it.uppercase() },
                            label = { Text("请求方式（GET / POST / PUT）") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = customWebhookContentType,
                            onValueChange = { customWebhookContentType = it },
                            label = { Text("Content-Type") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("自定义 Headers（JSON 或每行 Key: Value）") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(
                            value = customWebhookBody,
                            onValueChange = { customWebhookBody = it },
                            label = { Text("请求体模板") },
                            supportingText = { Text("支持 [title] [msg] [from] [time] [sim]；GET 时作为查询参数模板") },
                            minLines = 5,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    ForwardingChannels.GOTIFY -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Gotify 服务地址 (http://... 或 https://...)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("App Token") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.NTFY -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("ntfy 服务地址") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("Topic") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f3, onValueChange = { f3 = it }, label = { Text("访问 Token（选填）") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f4, onValueChange = { f4 = it }, label = { Text("优先级（min/low/default/high/max）") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f5, onValueChange = { f5 = it }, label = { Text("标签 Tags（选填，逗号分隔）") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f6, onValueChange = { f6 = it }, label = { Text("点击打开链接（选填）") }, modifier = Modifier.fillMaxWidth())
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
                    enabled = !isTesting
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
            "先测试再启用" to "测试成功只代表凭据和网络可用；还需要开启通道实例，并在规则中选中它。"
        ),
        "转发通道" to listOf(
            "PushPlus" to "登录 pushplus.plus，在一对一推送中复制 Token；群组推送可再填写 Topic。",
            "钉钉 / 飞书机器人" to "在群聊中添加自定义机器人，复制 Webhook；开启加签时还要填写对应 Secret。",
            "飞书自建应用" to "在飞书开放平台创建企业自建应用，填写 App ID、App Secret 和接收人的 open_id。",
            "Bark" to "在 iPhone 的 Bark App 中复制 Device Key；自建服务可填写自己的 HTTPS 或局域网 HTTP 地址。",
            "Telegram / Discord" to "Telegram 通过 BotFather 获取 Token 和 Chat ID；Discord 从频道 Webhooks 中复制地址。",
            "Gotify" to "填写 Gotify 服务地址和应用 Token。公网服务使用 HTTPS，局域网可使用 HTTP。",
            "ntfy" to "填写 ntfy 服务地址与 Topic，私有主题再填写访问 Token。不要使用容易猜到的公开 Topic 传输验证码。",
            "QQ / OneBot" to "Qmsg 模式填写 Key；OneBot 模式填写自建 HTTP 接口地址。",
            "邮件 / WebSocket" to "邮件填写 SMTP 服务器、账号、授权码和收件人；WebSocket 填写服务地址及可选 Token。",
            "短信直发 / 自定义 Webhook" to "短信直发会产生运营商费用；Webhook 接收 JSON POST，可按需填写自定义 Headers。",
            "通道组" to "把多个已配置实例组合后并发发送。不要把通道组互相循环引用。"
        ),
        "远程发送" to listOf(
            "支持来源" to "短信指令、钉钉 Stream、飞书长连接、Telegram Bot、WebSocket 和邮箱 IMAP。",
            "指令格式" to "默认格式：/发信 [SIM1或SIM2] 目标号码 短信内容。自定义前缀后，请使用该实例自己的前缀。",
            "白名单" to "关闭时接受所有符合格式的用户；开启后必须填写授权用户，群聊还应填写授权群组。",
            "卡槽与限制" to "每个来源可设置默认卡槽、免打扰时段、每小时限额和每日限额。",
            "回执" to "钉钉、飞书、Telegram、WebSocket 支持原路回执；短信和邮箱来源需选择普通转发通道接收回执。",
            "安全提示" to "远程发送会真实调用本机 SIM 卡。请启用白名单、设置限额，并只在本人或明确授权的设备上使用。"
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
                    .height(420.dp)
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
