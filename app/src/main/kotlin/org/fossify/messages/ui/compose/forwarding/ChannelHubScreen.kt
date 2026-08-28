package org.fossify.messages.ui.compose.forwarding

import android.annotation.SuppressLint
import android.content.Context
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.messages.forwarding.ChannelTestSender
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.ui.compose.components.GatewayCard
import org.fossify.messages.ui.compose.components.StatusBadge
import org.fossify.messages.ui.compose.theme.GatewayGreen
import org.fossify.messages.ui.compose.theme.GatewayOrange
import org.fossify.messages.ui.compose.theme.GatewayPurple
import org.fossify.messages.ui.compose.theme.GatewayRed
import java.net.HttpURLConnection
import java.net.URL

enum class ChannelCategory(val title: String, val emoji: String) {
    ALL("全部", "🌐"),
    WECHAT("微信生态", "🟢"),
    WORK("办公协同", "🏢"),
    INSTANT("极客通讯", "⚡"),
    CLOUD("云与自定义", "☁️")
}

data class ChannelDefinition(
    val id: String,
    val name: String,
    val description: String,
    val iconEmoji: String,
    val category: ChannelCategory,
    val isSmsChannel: Boolean = false,
    val isGroupChannel: Boolean = false
)

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
        3. 复制生成的 Webhook URL 与加签 Secret 填入即可
    """.trimIndent()
    ForwardingChannels.BARK -> """
        1. iPhone 在 App Store 搜索下载 Bark App
        2. 打开 Bark 首页复制您的专属 Device Key
        3. 填入 App 保存，苹果设备即可通过 APNs 极速低功耗弹窗
    """.trimIndent()
    ForwardingChannels.WEBSOCKET -> """
        1. 部署运行 personal-assistant 或标准 WebSocket 服务
        2. 填入 ws://IP:端口 或 HTTP 推送网关地址，实现毫秒级桌面端推流
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
        填入任意已有系统或第三方平台的 HTTP POST 接收接口 URL，系统将自动将短信转换为 JSON 发送，无需修改原有平台代码。
    """.trimIndent()
    ForwardingChannels.CHANNEL_GROUP -> """
        自由勾选多个已配置的通道组合为一个群组。
        收到短信后将一键并发推送到群组内的所有渠道！
    """.trimIndent()
    else -> "配置该通道所需的凭证参数即可。"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelHubScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val config = remember { MultiForwardConfig(context) }

    var selectedCategory by remember { mutableStateOf(ChannelCategory.ALL) }

    // 15 大全生态通道定义
    val channelDefs = remember {
        listOf(
            // 微信生态
            ChannelDefinition(ForwardingChannels.PUSHPLUS, "PushPlus 微信推送", "通过 PushPlus 机器人推送到个人/群微信", "💬", ChannelCategory.WECHAT),
            ChannelDefinition(ForwardingChannels.WECHAT_TEST, "微信测试号", "微信公众平台测试号模板消息直推", "🟢", ChannelCategory.WECHAT),
            ChannelDefinition(ForwardingChannels.WECOM_APP, "企业微信应用号", "官方企业微信 Agent 应用自建卡片消息", "💼", ChannelCategory.WECHAT),
            ChannelDefinition(ForwardingChannels.WECOM_BOT, "企业微信群机器人", "企业微信内部群 Webhook 机器人", "🤖", ChannelCategory.WECHAT),

            // 办公协同
            ChannelDefinition(ForwardingChannels.FEISHU_APP, "飞书自建应用", "飞书开放平台自建应用直推，支持富文本", "🏢", ChannelCategory.WORK),
            ChannelDefinition(ForwardingChannels.FEISHU_BOT, "飞书群机器人", "飞书自定义群机器人签名消息", "🕊️", ChannelCategory.WORK),
            ChannelDefinition(ForwardingChannels.DINGTALK, "钉钉群机器人", "钉钉自定义群机器人加签推送", "🤖", ChannelCategory.WORK),

            // 极客通讯
            ChannelDefinition(ForwardingChannels.QQ, "QQ 消息 (Qmsg/OneBot)", "支持 Qmsg 酱或 OneBot(go-cqhttp) 协议", "🐧", ChannelCategory.INSTANT),
            ChannelDefinition(ForwardingChannels.BARK, "Bark (iOS)", "苹果设备专属 APNs 极速低功耗推送", "🔔", ChannelCategory.INSTANT),
            ChannelDefinition(ForwardingChannels.WEBSOCKET, "WebSocket 客户端", "长连接实时推送，支持 [personal-assistant]", "🔌", ChannelCategory.INSTANT),
            ChannelDefinition(ForwardingChannels.TELEGRAM, "Telegram 机器人", "Telegram Bot API 异步消息推送", "✈️", ChannelCategory.INSTANT),
            ChannelDefinition(ForwardingChannels.DISCORD, "Discord 群机器人", "Discord Webhook 频道卡片推送", "🎮", ChannelCategory.INSTANT),

            // 云与自定义
            ChannelDefinition(ForwardingChannels.EMAIL, "邮件消息 (SMTP)", "标准 SMTP 协议直连发信 (SSL/STARTTLS)", "📧", ChannelCategory.CLOUD),
            ChannelDefinition(ForwardingChannels.TENCENT_CLOUD, "腾讯云自定义告警", "腾讯云监控告警回调，触发免费短信与通知", "☁️", ChannelCategory.CLOUD),
            ChannelDefinition(ForwardingChannels.SMS_DIRECT, "短信直发 (SIM 转发)", "通过本机备用 SIM 卡向指定手机号转发短信", "📱", ChannelCategory.CLOUD, isSmsChannel = true),
            ChannelDefinition(ForwardingChannels.CUSTOM_WEBHOOK, "自定义 Webhook", "反向适配任意第三方系统 HTTP POST/GET", "🌐", ChannelCategory.CLOUD),
            ChannelDefinition(ForwardingChannels.CHANNEL_GROUP, "群组聚合消息", "组合多个通道为一个群组，一次性并发推送", "👥", ChannelCategory.CLOUD, isGroupChannel = true)
        )
    }

    val filteredChannels = remember(selectedCategory) {
        if (selectedCategory == ChannelCategory.ALL) channelDefs
        else channelDefs.filter { it.category == selectedCategory }
    }

    // 响应式开关状态映射
    var channelStates by remember {
        mutableStateOf(
            mapOf(
                ForwardingChannels.PUSHPLUS to config.pushPlusEnabled,
                ForwardingChannels.WECHAT_TEST to config.wechatTestEnabled,
                ForwardingChannels.QQ to config.qqEnabled,
                ForwardingChannels.WECOM_APP to config.weComEnabled,
                ForwardingChannels.WECOM_BOT to config.weComBotEnabled,
                ForwardingChannels.FEISHU_APP to config.feishuAppEnabled,
                ForwardingChannels.FEISHU_BOT to config.feishuEnabled,
                ForwardingChannels.DINGTALK to config.dingTalkEnabled,
                ForwardingChannels.BARK to config.barkEnabled,
                ForwardingChannels.WEBSOCKET to config.websocketEnabled,
                ForwardingChannels.TELEGRAM to config.telegramEnabled,
                ForwardingChannels.DISCORD to config.discordEnabled,
                ForwardingChannels.TENCENT_CLOUD to config.tencentCloudEnabled,
                ForwardingChannels.EMAIL to config.emailEnabled,
                ForwardingChannels.SMS_DIRECT to config.smsDirectEnabled,
                ForwardingChannels.CUSTOM_WEBHOOK to config.customWebhookEnabled,
                ForwardingChannels.CHANNEL_GROUP to config.channelGroupEnabled
            )
        )
    }

    var pingResults by remember { mutableStateOf(mapOf<String, String>()) }
    var testingStates by remember { mutableStateOf(mapOf<String, Boolean>()) }
    var editingChannel by remember { mutableStateOf<ChannelDefinition?>(null) }
    var showGroupDialog by remember { mutableStateOf(false) }
    var showFullTutorialDialog by remember { mutableStateOf(false) }

    fun isChannelConfigured(id: String): Boolean = when (id) {
        ForwardingChannels.PUSHPLUS -> config.pushPlusToken().isNotBlank()
        ForwardingChannels.WECHAT_TEST -> config.wechatTestAppId().isNotBlank() && config.wechatTestAppSecret().isNotBlank()
        ForwardingChannels.QQ -> config.qqWebhook().isNotBlank()
        ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> config.weComCorpId().isNotBlank() && config.weComAgentId().isNotBlank()
        ForwardingChannels.WECOM_BOT -> config.weComBotWebhook().isNotBlank()
        ForwardingChannels.FEISHU_APP -> config.feishuAppId().isNotBlank() && config.feishuAppSecret().isNotBlank()
        ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT -> config.feishuWebhook().isNotBlank()
        ForwardingChannels.DINGTALK -> config.dingTalkWebhook().isNotBlank()
        ForwardingChannels.BARK -> config.barkDeviceKey().isNotBlank()
        ForwardingChannels.WEBSOCKET -> config.websocketUrl().isNotBlank()
        ForwardingChannels.TELEGRAM -> config.telegramBotToken().isNotBlank() && config.telegramChatId().isNotBlank()
        ForwardingChannels.DISCORD -> config.discordWebhook().isNotBlank()
        ForwardingChannels.TENCENT_CLOUD -> config.tencentCloudWebhook().isNotBlank()
        ForwardingChannels.EMAIL -> config.emailHost().isNotBlank() && config.emailUser().isNotBlank()
        ForwardingChannels.SMS_DIRECT -> config.smsDirectPhone().isNotBlank()
        ForwardingChannels.CUSTOM_WEBHOOK -> config.customWebhookUrl().isNotBlank()
        ForwardingChannels.CHANNEL_GROUP -> config.channelGroupMembers().isNotEmpty()
        else -> false
    }

    fun setChannelEnabled(id: String, enabled: Boolean) {
        when (id) {
            ForwardingChannels.PUSHPLUS -> config.pushPlusEnabled = enabled
            ForwardingChannels.WECHAT_TEST -> config.wechatTestEnabled = enabled
            ForwardingChannels.QQ -> config.qqEnabled = enabled
            ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> config.weComEnabled = enabled
            ForwardingChannels.WECOM_BOT -> config.weComBotEnabled = enabled
            ForwardingChannels.FEISHU_APP -> config.feishuAppEnabled = enabled
            ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT -> config.feishuEnabled = enabled
            ForwardingChannels.DINGTALK -> config.dingTalkEnabled = enabled
            ForwardingChannels.BARK -> config.barkEnabled = enabled
            ForwardingChannels.WEBSOCKET -> config.websocketEnabled = enabled
            ForwardingChannels.TELEGRAM -> config.telegramEnabled = enabled
            ForwardingChannels.DISCORD -> config.discordEnabled = enabled
            ForwardingChannels.TENCENT_CLOUD -> config.tencentCloudEnabled = enabled
            ForwardingChannels.EMAIL -> config.emailEnabled = enabled
            ForwardingChannels.SMS_DIRECT -> config.smsDirectEnabled = enabled
            ForwardingChannels.CUSTOM_WEBHOOK -> config.customWebhookEnabled = enabled
            ForwardingChannels.CHANNEL_GROUP -> config.channelGroupEnabled = enabled
        }
        channelStates = channelStates + (id to enabled)
    }

    fun getChannelConfigSummary(id: String): String = when (id) {
        ForwardingChannels.PUSHPLUS -> if (config.pushPlusToken().isNotBlank()) "Token: 已配置" else "未配置 Token"
        ForwardingChannels.WECHAT_TEST -> if (config.wechatTestAppId().isNotBlank()) "AppID: 已配置" else "未配置 AppID/Secret"
        ForwardingChannels.QQ -> if (config.qqWebhook().isNotBlank()) "QQ: 已配置" else "未配置 Token/Webhook"
        ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> if (config.weComCorpId().isNotBlank()) "企业ID: 已配置" else "未配置 CorpID/Secret"
        ForwardingChannels.WECOM_BOT -> if (config.weComBotWebhook().isNotBlank()) "Webhook: 已配置" else "未配置 Webhook"
        ForwardingChannels.FEISHU_APP -> if (config.feishuAppId().isNotBlank()) "AppID: 已配置" else "未配置 AppID/Secret"
        ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT -> if (config.feishuWebhook().isNotBlank()) "Webhook: 已配置" else "未配置 Webhook"
        ForwardingChannels.DINGTALK -> if (config.dingTalkWebhook().isNotBlank()) "Webhook: 已配置" else "未配置 Webhook"
        ForwardingChannels.BARK -> if (config.barkDeviceKey().isNotBlank()) "Key: 已配置" else "未配置 DeviceKey"
        ForwardingChannels.WEBSOCKET -> if (config.websocketUrl().isNotBlank()) "URL: 已配置" else "未配置 WebSocket URL"
        ForwardingChannels.TELEGRAM -> if (config.telegramBotToken().isNotBlank()) "Token: 已配置" else "未配置 BotToken"
        ForwardingChannels.DISCORD -> if (config.discordWebhook().isNotBlank()) "Webhook: 已配置" else "未配置 Webhook"
        ForwardingChannels.TENCENT_CLOUD -> if (config.tencentCloudWebhook().isNotBlank()) "URL: 已配置" else "未配置 Webhook"
        ForwardingChannels.EMAIL -> if (config.emailHost().isNotBlank()) "SMTP: ${config.emailHost()}" else "未配置 SMTP"
        ForwardingChannels.SMS_DIRECT -> if (config.smsDirectPhone().isNotBlank()) "目标号: ${config.smsDirectPhone()}" else "未配置目标号"
        ForwardingChannels.CUSTOM_WEBHOOK -> if (config.customWebhookUrl().isNotBlank()) "URL: 已配置" else "未配置 URL"
        ForwardingChannels.CHANNEL_GROUP -> "已包含 ${config.channelGroupMembers().size} 个聚合通道"
        else -> "就绪"
    }

    fun performSendTestMessage(channel: ChannelDefinition) {
        if (channel.isGroupChannel) {
            val members = config.channelGroupMembers()
            if (members.isEmpty()) {
                Toast.makeText(context, "请先点击「⚙️ 配置」为群组选择至少一个通道成员", Toast.LENGTH_SHORT).show()
                showGroupDialog = true
                return
            }
        } else if (!isChannelConfigured(channel.id)) {
            Toast.makeText(context, "请先完成【${channel.name}】的参数配置", Toast.LENGTH_SHORT).show()
            editingChannel = channel
            return
        }

        testingStates = testingStates + (channel.id to true)
        scope.launch {
            val result = ChannelTestSender.sendTest(context, channel.id)
            testingStates = testingStates + (channel.id to false)
            if (result.isSuccess) {
                val msg = result.getOrNull() ?: "测试消息发送成功！"
                pingResults = pingResults + (channel.id to "测试送达成功")
                Toast.makeText(context, "✅ [${channel.name}] $msg", Toast.LENGTH_LONG).show()
            } else {
                val err = result.exceptionOrNull()?.message ?: "未知错误"
                pingResults = pingResults + (channel.id to "发送失败: ${err.take(15)}")
                Toast.makeText(context, "❌ [${channel.name}] 推送失败: $err", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "多渠道推送中心 (对标 message-pusher)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "15 种全生态通道 · 真实测试发信 · KeyStore 硬件加密",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showFullTutorialDialog = true }
                    ) {
                        Text("📖 教程指南", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 分类选择 Tab
            ScrollableTabRow(
                selectedTabIndex = ChannelCategory.values().indexOf(selectedCategory),
                edgePadding = 16.dp,
                containerColor = MaterialTheme.colorScheme.background
            ) {
                ChannelCategory.values().forEach { cat ->
                    Tab(
                        selected = selectedCategory == cat,
                        onClick = { selectedCategory = cat },
                        text = { Text("${cat.emoji} ${cat.title}", fontSize = 12.sp) }
                    )
                }
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item { Spacer(modifier = Modifier.height(4.dp)) }

                items(filteredChannels, key = { it.id }) { channel ->
                    val isChecked = channelStates[channel.id] == true
                    val isTesting = testingStates[channel.id] == true
                    val pingText = pingResults[channel.id]

                    GatewayCard {
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.surface,
                                    modifier = Modifier.size(40.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(text = channel.iconEmoji, fontSize = 20.sp)
                                    }
                                }

                                Spacer(modifier = Modifier.width(12.dp))

                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = channel.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = getChannelConfigSummary(channel.id),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (getChannelConfigSummary(channel.id).contains("未配置")) GatewayOrange else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }

                                Switch(
                                    checked = isChecked,
                                    onCheckedChange = { targetState ->
                                        if (targetState && !isChannelConfigured(channel.id)) {
                                            Toast.makeText(context, "请先完成【${channel.name}】的参数配置后再开启", Toast.LENGTH_SHORT).show()
                                            if (channel.isGroupChannel) showGroupDialog = true
                                            else editingChannel = channel
                                        } else {
                                            setChannelEnabled(channel.id, targetState)
                                            Toast.makeText(context, "${channel.name} 已${if (targetState) "启用" else "禁用"}", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }

                            if (pingText != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                StatusBadge(
                                    text = pingText,
                                    color = if (pingText.contains("失败")) GatewayRed else GatewayGreen
                                )
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                StatusBadge(
                                    text = if (channel.isSmsChannel) "SIM直发" else if (channel.isGroupChannel) "群组聚合" else "KEYSTORE加密",
                                    color = GatewayPurple
                                )

                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(
                                        onClick = {
                                            if (channel.isGroupChannel) showGroupDialog = true
                                            else editingChannel = channel
                                        },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text("⚙️ 配置", fontSize = 12.sp)
                                    }

                                    Button(
                                        onClick = { performSendTestMessage(channel) },
                                        shape = RoundedCornerShape(8.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                        enabled = !isTesting
                                    ) {
                                        if (isTesting) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                                color = MaterialTheme.colorScheme.onPrimary
                                            )
                                        } else {
                                            Text("测试", fontSize = 12.sp, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.Bold)
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

    // 通道专属配置弹窗 (内置极速指引)
    editingChannel?.let { channel ->
        ChannelDedicatedConfigDialog(
            channel = channel,
            config = config,
            onDismiss = { editingChannel = null },
            onSaved = {
                setChannelEnabled(channel.id, true)
                Toast.makeText(context, "${channel.name} 配置已安全加密保存并立即启用！", Toast.LENGTH_SHORT).show()
                editingChannel = null
            }
        )
    }

    // 群组聚合分发弹窗
    if (showGroupDialog) {
        ChannelGroupMembersDialog(
            allChannels = channelDefs.filter { !it.isGroupChannel },
            config = config,
            onDismiss = { showGroupDialog = false },
            onSaved = {
                setChannelEnabled(ForwardingChannels.CHANNEL_GROUP, true)
                Toast.makeText(context, "群组聚合通道配置已更新并启用！", Toast.LENGTH_SHORT).show()
                showGroupDialog = false
            }
        )
    }

    // 全量教程指南弹窗
    if (showFullTutorialDialog) {
        ChannelFullTutorialDialog(
            onDismiss = { showFullTutorialDialog = false }
        )
    }
}

@Composable
fun ChannelDedicatedConfigDialog(
    channel: ChannelDefinition,
    config: MultiForwardConfig,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var f1 by remember {
        mutableStateOf(
            when (channel.id) {
                ForwardingChannels.PUSHPLUS -> config.pushPlusToken()
                ForwardingChannels.WECHAT_TEST -> config.wechatTestAppId()
                ForwardingChannels.QQ -> config.qqWebhook()
                ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> config.weComCorpId()
                ForwardingChannels.WECOM_BOT -> config.weComBotWebhook()
                ForwardingChannels.FEISHU_APP -> config.feishuAppId()
                ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT -> config.feishuWebhook()
                ForwardingChannels.DINGTALK -> config.dingTalkWebhook()
                ForwardingChannels.BARK -> config.barkServerUrl()
                ForwardingChannels.WEBSOCKET -> config.websocketUrl()
                ForwardingChannels.TELEGRAM -> config.telegramBotToken()
                ForwardingChannels.DISCORD -> config.discordWebhook()
                ForwardingChannels.TENCENT_CLOUD -> config.tencentCloudWebhook()
                ForwardingChannels.EMAIL -> config.emailHost()
                ForwardingChannels.SMS_DIRECT -> config.smsDirectPhone()
                ForwardingChannels.CUSTOM_WEBHOOK -> config.customWebhookUrl()
                else -> ""
            }
        )
    }

    var f2 by remember {
        mutableStateOf(
            when (channel.id) {
                ForwardingChannels.PUSHPLUS -> config.pushPlusTopic()
                ForwardingChannels.WECHAT_TEST -> config.wechatTestAppSecret()
                ForwardingChannels.QQ -> config.qqType()
                ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> config.weComAgentId()
                ForwardingChannels.FEISHU_APP -> config.feishuAppSecret()
                ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT -> config.feishuSecret()
                ForwardingChannels.DINGTALK -> config.dingTalkSecret()
                ForwardingChannels.BARK -> config.barkDeviceKey()
                ForwardingChannels.WEBSOCKET -> config.websocketToken()
                ForwardingChannels.TELEGRAM -> config.telegramChatId()
                ForwardingChannels.TENCENT_CLOUD -> config.tencentCloudSecret()
                ForwardingChannels.EMAIL -> config.emailUser()
                ForwardingChannels.CUSTOM_WEBHOOK -> config.customWebhookHeaders()
                else -> ""
            }
        )
    }

    var f3 by remember {
        mutableStateOf(
            when (channel.id) {
                ForwardingChannels.WECHAT_TEST -> config.wechatTestTemplateId()
                ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> config.weComSecret()
                ForwardingChannels.FEISHU_APP -> config.feishuReceiveId()
                ForwardingChannels.EMAIL -> config.emailPassword()
                else -> ""
            }
        )
    }

    var f4 by remember {
        mutableStateOf(
            when (channel.id) {
                ForwardingChannels.WECHAT_TEST -> config.wechatTestOpenId()
                ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> config.weComToUser()
                ForwardingChannels.EMAIL -> config.emailRecipients()
                else -> ""
            }
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("${channel.iconEmoji} 配置 ${channel.name}")
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 内置极速配置指引卡片
                Surface(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.08f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("💡 极速配置指引：", fontWeight = FontWeight.Bold, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = getChannelTutorial(channel.id),
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = 16.sp
                        )
                    }
                }

                when (channel.id) {
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
                    ForwardingChannels.FEISHU_APP -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("App ID (cli_xxx)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("App Secret") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f3, onValueChange = { f3 = it }, label = { Text("接收人 receive_id (open_id)") }, modifier = Modifier.fillMaxWidth())
                    }
                    ForwardingChannels.DINGTALK -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Webhook URL") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("加签密钥 Secret (SEC...)") }, modifier = Modifier.fillMaxWidth())
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
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("接收端 HTTP URL (POST)") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = f2, onValueChange = { f2 = it }, label = { Text("自定义 Headers (可选)") }, modifier = Modifier.fillMaxWidth())
                    }
                    else -> {
                        OutlinedTextField(value = f1, onValueChange = { f1 = it }, label = { Text("Webhook 地址") }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    when (channel.id) {
                        ForwardingChannels.PUSHPLUS -> config.savePushPlus(f1, f2)
                        ForwardingChannels.WECHAT_TEST -> config.saveWechatTest(f1, f2, f3, f4)
                        ForwardingChannels.QQ -> config.saveQq(f1, f2)
                        ForwardingChannels.WECOM, ForwardingChannels.WECOM_APP -> config.saveWeCom(f1, f2, f3, f4)
                        ForwardingChannels.WECOM_BOT -> config.saveWeComBot(f1)
                        ForwardingChannels.FEISHU_APP -> config.saveFeishuApp(f1, f2, f3)
                        ForwardingChannels.FEISHU, ForwardingChannels.FEISHU_BOT -> config.saveFeishu(f1, f2)
                        ForwardingChannels.DINGTALK -> config.saveDingTalk(f1, f2)
                        ForwardingChannels.BARK -> config.saveBark(f1, f2)
                        ForwardingChannels.WEBSOCKET -> config.saveWebsocket(f1, f2)
                        ForwardingChannels.TELEGRAM -> config.saveTelegram(f1, f2)
                        ForwardingChannels.DISCORD -> config.saveDiscord(f1)
                        ForwardingChannels.TENCENT_CLOUD -> config.saveTencentCloud(f1, f2)
                        ForwardingChannels.EMAIL -> config.saveEmail(f1, 465, f2, f3, f4)
                        ForwardingChannels.SMS_DIRECT -> config.saveSmsDirect(f1)
                        ForwardingChannels.CUSTOM_WEBHOOK -> config.saveCustomWebhook(f1, f2)
                    }
                    onSaved()
                }
            ) {
                Text("保存配置")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun ChannelGroupMembersDialog(
    allChannels: List<ChannelDefinition>,
    config: MultiForwardConfig,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var selectedMembers by remember { mutableStateOf(config.channelGroupMembers().toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("👥 聚合群组通道配置") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("请勾选需要归纳到群组的通道。收到短信后将一键并发推送到所选的所有渠道：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

                allChannels.forEach { channel ->
                    val isChecked = selectedMembers.contains(channel.id)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                selectedMembers = if (isChecked) selectedMembers - channel.id else selectedMembers + channel.id
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { checked ->
                                selectedMembers = if (checked) selectedMembers + channel.id else selectedMembers - channel.id
                            }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("${channel.iconEmoji} ${channel.name}", fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    config.saveChannelGroupMembers(selectedMembers)
                    onSaved()
                }
            ) {
                Text("保存群组成员 (${selectedMembers.size})")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

@Composable
fun ChannelFullTutorialDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📖 15 大全生态推送通道配置指南", fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .height(420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                TutorialSection(
                    title = "🟢 微信生态",
                    items = listOf(
                        "1. PushPlus 微信推送" to "微信小程序或网站 (pushplus.plus) 扫码登录，在「一对一推送」复制 Token 填入即可。",
                        "2. 微信公众平台测试号" to "访问 mp.weixin.qq.com 申请测试号，获取 appID、appsecret，关注后获取 openID，新增模板获取 template_id 即可直推微信模板消息。",
                        "3. 企业微信应用号" to "企业微信后台创建自建应用，获取企业ID (corpid)、AgentId 与 Secret，接收人填 @all 或具体账号。",
                        "4. 企业微信群机器人" to "企业微信群聊设置 -> 添加群机器人，复制生成的 Webhook URL 填入即可。"
                    )
                )

                TutorialSection(
                    title = "🏢 办公协同",
                    items = listOf(
                        "5. 钉钉群机器人" to "钉钉群设置 -> 智能群助手 -> 添加自定义机器人 -> 勾选【加签】，复制 Webhook 与加签 Secret。",
                        "6. 飞书群机器人" to "飞书群设置 -> 群机器人 -> 自定义机器人，复制 Webhook 地址与签名 Secret。",
                        "7. 飞书自建应用" to "飞书开放平台 (open.feishu.cn) 创建自建应用，获取 App ID 与 App Secret，开启消息权限并填入 open_id。"
                    )
                )

                TutorialSection(
                    title = "⚡ 极客通讯",
                    items = listOf(
                        "8. QQ 消息 (Qmsg/OneBot)" to "Qmsg 酱模式：访问 qmsg.zendee.cn 登录并添加 QQ 机器人好友，复制 Key；OneBot 模式直接填入 HTTP Webhook。",
                        "9. Bark (iOS 苹果设备)" to "iPhone 下载 Bark App，打开后复制提供的专属 Device Key 填入即可实现 APNs 极速低功耗弹窗。",
                        "10. WebSocket 客户端" to "运行 personal-assistant 或标准 WebSocket 服务，填入 ws:// 或 http:// 地址实现毫秒级桌面推流。",
                        "11. Telegram 机器人" to "Telegram @BotFather 创建机器人获取 Bot Token，@userinfobot 获取 Chat ID 填入。",
                        "12. Discord 群机器人" to "Discord 频道设置 -> 整合 -> Webhooks -> 复制 Webhook URL 填入即可。"
                    )
                )

                TutorialSection(
                    title = "☁️ 云服务与自定义",
                    items = listOf(
                        "13. 邮件消息 (SMTP 邮箱直发)" to "以 QQ 邮箱为例：SMTP 服务器填 smtp.qq.com (端口 465 SSL)，在 QQ 邮箱网页版设置账户中生成 16 位 POP3/SMTP 授权码填入密码栏。",
                        "14. 腾讯云自定义告警" to "腾讯云控制台云监控告警回调设置中获取 Webhook，可触发免费短信提醒。",
                        "15. 短信直发 (SIM 转发)" to "通过本机备用 SIM 卡直接将收到的短信转发到指定的目标手机号码。",
                        "16. 自定义 Webhook" to "支持自定义 HTTP POST 目标地址，反向适配任意已有业务平台。",
                        "👥 群组聚合消息" to "自由勾选多个已配置好的通道，收到短信后一键并发扇出到选中的所有渠道！"
                    )
                )
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
        Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
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
