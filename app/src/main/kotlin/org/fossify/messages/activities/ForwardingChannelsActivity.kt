package org.fossify.messages.activities

import android.app.ProgressDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityForwardingChannelsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.applySmsDialogColors
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.showSmsStyled
import org.fossify.messages.forwarding.ChannelTestSender
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.PushPlusConfig
import org.fossify.messages.forwarding.PushPlusWorker

class ForwardingChannelsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityForwardingChannelsBinding::inflate)
    private val multiConfig by lazy { MultiForwardConfig(applicationContext) }
    private val pushPlusConfig by lazy { PushPlusConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.forwardingAppbar),
            padBottomImeAndSystem = listOf(binding.forwardingScrollview),
        )
        setupMaterialScrollListener(binding.forwardingScrollview, binding.forwardingAppbar)
        setupTopAppBar(binding.forwardingAppbar, NavigationIcon.Arrow)
        binding.forwardingToolbar.title = getString(R.string.forwarding_title)
        applyMiuiTopAppBarChrome(binding.forwardingAppbar, binding.forwardingToolbar)
        setupToolbarMenu()
        bindActions()
        enforceForwardingDisclaimer()
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.forwardingAppbar, binding.forwardingToolbar)
        updateSummaries()
    }

    private fun setupToolbarMenu() {
        binding.forwardingToolbar.menu.clear()
        val guideItem = binding.forwardingToolbar.menu.add(Menu.NONE, 9999, Menu.NONE, "📖 说明书")
        guideItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)

        val devItem = binding.forwardingToolbar.menu.add(Menu.NONE, 9998, Menu.NONE, "🚀 切换到开发版")
        devItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)

        binding.forwardingToolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                9999 -> {
                    showFullManualDialog()
                    true
                }
                9998 -> {
                    config.useGatewayDeveloperUi = true
                    val intent = Intent(this, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                    finish()
                    true
                }
                else -> false
            }
        }
    }

    private fun bindActions() = binding.apply {
        // 1. 微信与即时通讯
        forwardingPushplusHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, PushPlusSettingsActivity::class.java))
        }
        forwardingWxtestHolder.setOnClickListener { showWxTestDialog() }
        forwardingQqHolder.setOnClickListener { showQqDialog() }
        forwardingEmailHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, EmailSettingsActivity::class.java))
        }

        // 2. 办公协同
        forwardingWecomHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, WeComSettingsActivity::class.java))
        }
        forwardingWecomBotHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, WeComBotSettingsActivity::class.java))
        }
        forwardingFeishuAppHolder.setOnClickListener { showFeishuAppDialog() }
        forwardingFeishuHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, FeishuSettingsActivity::class.java))
        }
        forwardingDingtalkHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, DingTalkSettingsActivity::class.java))
        }

        // 3. 极客通讯与海外
        forwardingBarkHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, BarkSettingsActivity::class.java))
        }
        forwardingWebsocketHolder.setOnClickListener { showWebSocketDialog() }
        forwardingTelegramHolder.setOnClickListener { showTelegramDialog() }
        forwardingDiscordHolder.setOnClickListener { showDiscordDialog() }
        forwardingGotifyHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, GotifySettingsActivity::class.java))
        }

        // 4. 云服务与高级路由
        forwardingTencentSmsHolder.setOnClickListener { showTencentSmsDialog() }
        forwardingSmsDirectHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, SmsDirectSettingsActivity::class.java))
        }
        forwardingCustomWebhookHolder.setOnClickListener { showCustomWebhookDialog() }
        forwardingGroupBroadcastHolder.setOnClickListener { showGroupBroadcastDialog() }

        // 5. 规则、模板与流水
        forwardingRulesHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, ForwardingRulesSettingsActivity::class.java))
        }
        forwardingTemplateHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, MessageTemplateActivity::class.java))
        }
        forwardingRemoteControlHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, RemoteForwardingActivity::class.java))
        }
        forwardingSimOneHolder.setOnClickListener { showSimLabelDialog(0) }
        forwardingSimTwoHolder.setOnClickListener { showSimLabelDialog(1) }
        forwardingHistoryHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, ForwardingHistoryActivity::class.java))
        }
        forwardingDisclaimerHolder.setOnClickListener { showForwardingDisclaimer(requireAcceptance = false) }

        forwardingTest.setOnClickListener {
            val pushPlusEnabled = multiConfig.pushPlusEnabled && multiConfig.pushPlusToken().isNotBlank()
            if (!pushPlusEnabled && !multiConfig.anyEnabled()) {
                toast(R.string.forwarding_no_enabled)
                return@setOnClickListener
            }
            if (pushPlusEnabled) {
                PushPlusWorker.enqueueTest(
                    applicationContext,
                    getString(R.string.pushplus_test_sender_default),
                    getString(R.string.pushplus_test_body_default)
                )
            }
            if (multiConfig.anyEnabled()) MultiChannelForwardWorker.enqueueTest(applicationContext)
            toast(R.string.forwarding_test_queued)
        }
    }

    private fun updateSummaries() = binding.apply {
        val greenText = " · 已开启"
        val offText = " · 未开启"
        val unconfigured = "未配置"

        // 微信生态
        val pushPlusConfigured = multiConfig.pushPlusToken().isNotBlank()
        forwardingPushplusSummary.text = if (pushPlusConfigured) (if (multiConfig.pushPlusEnabled) "微信公众号模板消息$greenText" else "已配置$offText") else unconfigured

        val wxTestConfigured = multiConfig.wechatTestAppId().isNotBlank() && multiConfig.wechatTestAppSecret().isNotBlank()
        forwardingWxtestSummary.text = if (wxTestConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.WECHAT_TEST)) "微信测试号$greenText" else "已配置$offText") else unconfigured

        val qqConfigured = multiConfig.qqWebhook().isNotBlank()
        forwardingQqSummary.text = if (qqConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.QQ)) "QQ推送$greenText" else "已配置$offText") else unconfigured

        val emailConfigured = multiConfig.emailHost().isNotBlank() && multiConfig.emailPassword().isNotBlank()
        forwardingEmailSummary.text = if (emailConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.EMAIL)) "邮件SMTP$greenText" else "已配置$offText") else unconfigured

        // 办公协同
        val wecomConfigured = multiConfig.weComCorpId().isNotBlank() && multiConfig.weComSecret().isNotBlank()
        forwardingWecomSummary.text = if (wecomConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.WECOM_APP)) "自建应用$greenText" else "已配置$offText") else unconfigured

        val wecomBotConfigured = multiConfig.weComBotWebhook().isNotBlank()
        forwardingWecomBotSummary.text = if (wecomBotConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.WECOM_BOT)) "群Webhook$greenText" else "已配置$offText") else unconfigured

        val feishuAppConfigured = multiConfig.feishuAppId().isNotBlank() && multiConfig.feishuAppSecret().isNotBlank()
        forwardingFeishuAppSummary.text = if (feishuAppConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.FEISHU_APP)) "自建应用$greenText" else "已配置$offText") else unconfigured

        val feishuBotConfigured = multiConfig.feishuWebhook().isNotBlank()
        forwardingFeishuSummary.text = if (feishuBotConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.FEISHU_BOT)) "群机器人$greenText" else "已配置$offText") else unconfigured

        val dingConfigured = multiConfig.dingTalkWebhook().isNotBlank()
        forwardingDingtalkSummary.text = if (dingConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.DINGTALK)) "群Webhook$greenText" else "已配置$offText") else unconfigured

        // 极客通讯
        val barkConfigured = multiConfig.barkDeviceKey().isNotBlank()
        forwardingBarkSummary.text = if (barkConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.BARK)) "iOS 客户端$greenText" else "已配置$offText") else unconfigured

        val wsConfigured = multiConfig.websocketUrl().isNotBlank()
        forwardingWebsocketSummary.text = if (wsConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.WEBSOCKET)) "长连接网关$greenText" else "已配置$offText") else unconfigured

        val tgConfigured = multiConfig.telegramBotToken().isNotBlank() && multiConfig.telegramChatId().isNotBlank()
        forwardingTelegramSummary.text = if (tgConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.TELEGRAM)) "TG Bot$greenText" else "已配置$offText") else unconfigured

        val discordConfigured = multiConfig.discordWebhook().isNotBlank()
        forwardingDiscordSummary.text = if (discordConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.DISCORD)) "群Webhook$greenText" else "已配置$offText") else unconfigured

        val gotifyConfigured = multiConfig.gotifyServerUrl().isNotBlank() && multiConfig.gotifyToken().isNotBlank()
        forwardingGotifySummary.text = if (gotifyConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.GOTIFY)) "自建服务器$greenText" else "已配置$offText") else unconfigured

        // 云服务
        val tencentConfigured = multiConfig.tencentCloudWebhook().isNotBlank()
        forwardingTencentSmsSummary.text = if (tencentConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.TENCENT_CLOUD)) "告警策略短信$greenText" else "已配置$offText") else unconfigured

        val smsDirectConfigured = multiConfig.smsDirectPhone().isNotBlank()
        forwardingSmsDirectSummary.text = if (smsDirectConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.SMS_DIRECT)) "副卡短信直发$greenText" else "已配置$offText") else unconfigured

        val webhookConfigured = multiConfig.customWebhookUrl().isNotBlank()
        forwardingCustomWebhookSummary.text = if (webhookConfigured) (if (multiConfig.isChannelEnabled(ForwardingChannels.CUSTOM_WEBHOOK)) "自定义HTTP$greenText" else "已配置$offText") else unconfigured

        val groupCount = multiConfig.channelGroupMembers().size
        forwardingGroupBroadcastSummary.text = if (groupCount > 0) (if (multiConfig.isChannelEnabled(ForwardingChannels.CHANNEL_GROUP)) "已包含 $groupCount 个通道$greenText" else "已包含 $groupCount 个通道$offText") else "未选择聚合通道"

        // 规则与显示
        forwardingRulesSummary.text = "配置关键词与发件人黑白名单路由"
        forwardingTemplateSummary.text = when (multiConfig.templateMode) {
            MultiForwardConfig.TEMPLATE_COMPACT -> getString(R.string.forwarding_template_compact)
            MultiForwardConfig.TEMPLATE_DETAILED -> getString(R.string.forwarding_template_detailed)
            MultiForwardConfig.TEMPLATE_EMOJI -> getString(R.string.forwarding_template_emoji)
            MultiForwardConfig.TEMPLATE_CUSTOM -> getString(R.string.forwarding_template_custom)
            else -> getString(R.string.forwarding_template_standard)
        }
        forwardingSimOneSummary.text = multiConfig.simOneLabel.ifBlank { "默认 SIM 1" }
        forwardingSimTwoSummary.text = multiConfig.simTwoLabel.ifBlank { "默认 SIM 2" }
        forwardingHistorySummary.text = "查看近期派发与重试状态"
    }

    // ==========================================
    // 📖 15 大全生态通道全景说明书弹窗
    // ==========================================
    private fun showFullManualDialog() {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding)
        }
        scroll.addView(container)

        val manualContent = """
            📖 15 大消息推送通道全景操作指南：

            🟢 1. 微信与国内即时通讯
            • PushPlus: 微信扫码登录 pushplus.plus，获取一对一 Token 即可无门槛接收微信模板消息。
            • 微信公众平台测试号: 访问 mp.weixin.qq.com 申请测试号，扫描关注后填入 AppID、AppSecret、OpenID 及模板 ID。
            • QQ 消息推送: 支持 Qmsg 酱 (qmsg.zendee.cn) 或自建 CQHttp / Go-CQHttp 机器人。
            • 邮件 (SMTP): 支持 QQ邮箱/163/Gmail/企业邮箱。QQ 邮箱需在网页端设置中开启 SMTP 并生成 16 位专属授权码。

            🏢 2. 办公协同工作台
            • 飞书自建应用: 登录 open.feishu.cn 创建企业自建应用，获取 App ID 与 App Secret，开启机器人能力并在权限中勾选 im:message:send_as_bot。
            • 飞书群机器人: 飞书群设置 -> 添加机器人 -> 自定义机器人 -> 复制 Webhook 地址与加签密钥。
            • 企业微信自建应用: 登录 work.weixin.qq.com -> 应用管理 -> 创建应用，获取 AgentId 与 Secret；我的企业获取 CorpId。
            • 企业微信群机器人: 企微群添加群机器人，获取专属 Webhook。
            • 钉钉群机器人: 钉钉群智能助手 -> 添加自定义机器人 -> 安全设置选择「加签」，复制 Webhook 与 SEC 开头的密钥。

            ⚡ 3. 极客通讯与海外全生态
            • Bark (iOS): App Store 下载 Bark，打开 App 即可直接复制属于您个人的设备推送 Key 或私有部署服务器地址。
            • WebSocket 客户端: 支持局域网或公网通过 ws:// / wss:// 协议与官方或自建个人助理客户端进行实时全双工通讯。
            • Telegram 机器人: 在 Telegram 联系 @BotFather 创建机器人获取 Token，向机器人发任意消息后通过 @userinfobot 获取 ChatID。
            • Discord 机器人: Discord 服务器设置 -> 整合 -> 创建 Webhook，复制生成的 Webhook URL。
            • Gotify: 自建轻量级推送服务器，在 Gotify Web 端创建 Application 获取 Token。

            ☁️ 4. 云服务与高级路由
            • 腾讯云告警 (免费短信): 利用腾讯云监控平台的告警回调机制，通过云 API 发起免费短信下发。
            • 短信直发: 当手机支持双卡或插入了有免费短信套餐的副卡时，可将收到的短信直接调用 SIM 卡基带转发给指定的备用机号码。
            • 自定义 Webhook: 支持任意 HTTP GET / POST 协议、自定义 Headers、JSON 请求体模板及变量插值。
            • 👥 群组消息: 支持一键勾选多个已配置的通道，收到短信时以扇出并发形式一次性推送到所有被勾选的群组成员！
        """.trimIndent()

        val textView = TextView(this).apply {
            text = manualContent
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            setTextColor(Color.rgb(40, 40, 40))
            setLineSpacing(4f * density, 1f)
        }
        container.addView(textView)

        AlertDialog.Builder(this)
            .setTitle("📖 15 大推送通道配置指南")
            .setView(scroll)
            .setPositiveButton("我知道了", null)
            .create()
            .showSmsStyled()
    }

    // ==========================================
    // 各通道独立极速配置与测试弹窗
    // ==========================================

    private fun showWxTestDialog() {
        val guide = "💡 极速指引：访问 mp.weixin.qq.com 申请测试号，扫描关注后填入 AppID、AppSecret、关注用户的 OpenID 及模板 ID。"
        showChannelConfigDialog(
            title = "微信公众平台测试号",
            guide = guide,
            channelId = ForwardingChannels.WECHAT_TEST,
            fields = listOf(
                "AppID" to multiConfig.wechatTestAppId(),
                "AppSecret (加密)" to multiConfig.wechatTestAppSecret(),
                "用户 OpenID" to multiConfig.wechatTestOpenId(),
                "模板 Template ID" to multiConfig.wechatTestTemplateId()
            ),
            isPassword = listOf(false, true, false, false),
            onSave = { values, enabled ->
                multiConfig.saveWechatTest(values[0], values[1], values[3], values[2])
                multiConfig.setChannelEnabled(ForwardingChannels.WECHAT_TEST, enabled)
            }
        )
    }

    private fun showQqDialog() {
        val guide = "💡 极速指引：支持 Qmsg 酱 (qmsg.zendee.cn) 填入 Qmsg Key，或自建 CQHttp 填入服务地址与推送 QQ 号。"
        showChannelConfigDialog(
            title = "QQ 消息推送",
            guide = guide,
            channelId = ForwardingChannels.QQ,
            fields = listOf(
                "API 地址 / Qmsg Key (加密)" to multiConfig.qqWebhook()
            ),
            isPassword = listOf(true),
            onSave = { values, enabled ->
                multiConfig.saveQq(values[0])
                multiConfig.setChannelEnabled(ForwardingChannels.QQ, enabled)
            }
        )
    }

    private fun showFeishuAppDialog() {
        val guide = "💡 极速指引：open.feishu.cn 创建企业自建应用，获取 App ID 与 App Secret，并在权限管理中开启 im:message:send_as_bot。"
        showChannelConfigDialog(
            title = "飞书自建应用",
            guide = guide,
            channelId = ForwardingChannels.FEISHU_APP,
            fields = listOf(
                "App ID" to multiConfig.feishuAppId(),
                "App Secret (加密)" to multiConfig.feishuAppSecret(),
                "接收人 ID (OpenID/Email)" to multiConfig.feishuReceiveId()
            ),
            isPassword = listOf(false, true, false),
            onSave = { values, enabled ->
                multiConfig.saveFeishuApp(values[0], values[1], values[2])
                multiConfig.setChannelEnabled(ForwardingChannels.FEISHU_APP, enabled)
            }
        )
    }

    private fun showWebSocketDialog() {
        val guide = "💡 极速指引：填入 ws:// 或 wss:// 开头的 WebSocket 服务端地址，可搭配个人助理官方客户端实时监听。"
        showChannelConfigDialog(
            title = "WebSocket 客户端",
            guide = guide,
            channelId = ForwardingChannels.WEBSOCKET,
            fields = listOf(
                "WebSocket 服务端 URL" to multiConfig.websocketUrl(),
                "认证 Token (可选加密)" to multiConfig.websocketToken()
            ),
            isPassword = listOf(false, true),
            onSave = { values, enabled ->
                multiConfig.saveWebsocket(values[0], values[1])
                multiConfig.setChannelEnabled(ForwardingChannels.WEBSOCKET, enabled)
            }
        )
    }

    private fun showTelegramDialog() {
        val guide = "💡 极速指引：Telegram 联系 @BotFather 获取 Bot Token，向 Bot 发送消息后通过 @userinfobot 获取 Chat ID。"
        showChannelConfigDialog(
            title = "Telegram 机器人",
            guide = guide,
            channelId = ForwardingChannels.TELEGRAM,
            fields = listOf(
                "Bot Token (加密)" to multiConfig.telegramBotToken(),
                "Chat ID" to multiConfig.telegramChatId()
            ),
            isPassword = listOf(true, false),
            onSave = { values, enabled ->
                multiConfig.saveTelegram(values[0], values[1])
                multiConfig.setChannelEnabled(ForwardingChannels.TELEGRAM, enabled)
            }
        )
    }

    private fun showDiscordDialog() {
        val guide = "💡 极速指引：在 Discord 频道设置 -> 整合 -> 创建 Webhook，将生成的 Webhook URL 完整粘贴至下方。"
        showChannelConfigDialog(
            title = "Discord 群机器人",
            guide = guide,
            channelId = ForwardingChannels.DISCORD,
            fields = listOf(
                "Discord Webhook URL (加密)" to multiConfig.discordWebhook()
            ),
            isPassword = listOf(true),
            onSave = { values, enabled ->
                multiConfig.saveDiscord(values[0])
                multiConfig.setChannelEnabled(ForwardingChannels.DISCORD, enabled)
            }
        )
    }

    private fun showTencentSmsDialog() {
        val guide = "💡 极速指引：填入腾讯云 API 密钥 (SecretId/SecretKey) 以及接收告警短信的手机号，享受云监控免费短信额度。"
        showChannelConfigDialog(
            title = "腾讯云告警 (免费短信)",
            guide = guide,
            channelId = ForwardingChannels.TENCENT_CLOUD,
            fields = listOf(
                "告警 Webhook / SecretId" to multiConfig.tencentCloudWebhook(),
                "SecretKey (加密)" to multiConfig.tencentCloudSecret()
            ),
            isPassword = listOf(false, true),
            onSave = { values, enabled ->
                multiConfig.saveTencentCloud(values[0], values[1])
                multiConfig.setChannelEnabled(ForwardingChannels.TENCENT_CLOUD, enabled)
            }
        )
    }

    private fun showCustomWebhookDialog() {
        val guide = "💡 极速指引：支持填入任意 HTTP GET/POST 接口，支持自定义 Headers 与请求体模板 (支持 [from]、[msg] 等变量插值)。"
        showChannelConfigDialog(
            title = "自定义 Webhook",
            guide = guide,
            channelId = ForwardingChannels.CUSTOM_WEBHOOK,
            fields = listOf(
                "Webhook URL" to multiConfig.customWebhookUrl(),
                "自定义 Headers (JSON/KeyValue)" to multiConfig.customWebhookHeaders()
            ),
            isPassword = listOf(false, false),
            onSave = { values, enabled ->
                multiConfig.saveCustomWebhook(values[0], values[1])
                multiConfig.setChannelEnabled(ForwardingChannels.CUSTOM_WEBHOOK, enabled)
            }
        )
    }

    private fun showGroupBroadcastDialog() {
        val allChannels = listOf(
            ForwardingChannels.PUSHPLUS to "PushPlus 微信推送",
            ForwardingChannels.WECHAT_TEST to "微信公众平台测试号",
            ForwardingChannels.QQ to "QQ 消息推送",
            ForwardingChannels.EMAIL to "邮件消息 (SMTP)",
            ForwardingChannels.WECOM_APP to "企业微信自建应用",
            ForwardingChannels.WECOM_BOT to "企业微信群机器人",
            ForwardingChannels.FEISHU_APP to "飞书自建应用",
            ForwardingChannels.FEISHU_BOT to "飞书群机器人",
            ForwardingChannels.DINGTALK to "钉钉群机器人",
            ForwardingChannels.BARK to "Bark App (iOS)",
            ForwardingChannels.WEBSOCKET to "WebSocket 客户端",
            ForwardingChannels.TELEGRAM to "Telegram 机器人",
            ForwardingChannels.DISCORD to "Discord 机器人",
            ForwardingChannels.GOTIFY to "Gotify 自建",
            ForwardingChannels.TENCENT_CLOUD to "腾讯云告警短信",
            ForwardingChannels.SMS_DIRECT to "副卡短信直发",
            ForwardingChannels.CUSTOM_WEBHOOK to "自定义 Webhook"
        )

        val channelLabels = allChannels.map { it.second }.toTypedArray()
        val currentGroup = multiConfig.channelGroupMembers().toMutableSet()
        val checkedItems = allChannels.map { currentGroup.contains(it.first) }.toBooleanArray()

        AlertDialog.Builder(this)
            .setTitle("👥 选择群组聚合通道成员")
            .setMultiChoiceItems(channelLabels, checkedItems) { _, which, isChecked ->
                val cid = allChannels[which].first
                if (isChecked) currentGroup.add(cid) else currentGroup.remove(cid)
            }
            .setPositiveButton(R.string.forwarding_save) { _, _ ->
                multiConfig.saveChannelGroupMembers(currentGroup)
                multiConfig.setChannelEnabled(ForwardingChannels.CHANNEL_GROUP, currentGroup.isNotEmpty())
                updateSummaries()
                toast("群组已更新，包含 ${currentGroup.size} 个通道")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }

    private fun showChannelConfigDialog(
        title: String,
        guide: String,
        channelId: String,
        fields: List<Pair<String, String>>,
        isPassword: List<Boolean>,
        onSave: (List<String>, Boolean) -> Unit
    ) {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, padding)
        }
        scroll.addView(container)

        // 1. 顶部指引卡片
        val guideCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            setBackgroundColor(Color.parseColor("#F0F8F3"))
        }
        val guideText = TextView(this).apply {
            text = guide
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
            setTextColor(Color.parseColor("#0A4F24"))
            setLineSpacing(3f * density, 1f)
        }
        guideCard.addView(guideText)
        container.addView(guideCard)

        // 2. 开关
        val switchRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (14 * density).toInt(), 0, (8 * density).toInt())
        }
        val switchLabel = TextView(this).apply {
            text = "启用此推送通道"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTypeface(null, Typeface.BOLD)
            setTextColor(Color.rgb(17, 17, 17))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val channelSwitch = Switch(this).apply {
            isChecked = multiConfig.isChannelEnabled(channelId)
        }
        switchRow.addView(switchLabel)
        switchRow.addView(channelSwitch)
        container.addView(switchRow)

        // 3. 输入框列表
        val editTexts = mutableListOf<EditText>()
        fields.forEachIndexed { index, pair ->
            val label = TextView(this).apply {
                text = pair.first
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(Color.rgb(60, 60, 60))
                setPadding(0, (10 * density).toInt(), 0, (2 * density).toInt())
            }
            container.addView(label)

            val input = EditText(this).apply {
                setText(pair.second)
                hint = "请输入 ${pair.first}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(Color.rgb(17, 17, 17))
                setHintTextColor(Color.rgb(160, 160, 160))
                if (isPassword.getOrElse(index) { false }) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            editTexts.add(input)
            container.addView(input)
        }

        // 4. 🧪 发送测试按钮
        val testButton = Button(this).apply {
            text = "🧪 发送即时测试消息"
            setBackgroundColor(Color.parseColor("#159447"))
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (48 * density).toInt()).apply {
                topMargin = (16 * density).toInt()
            }
            setOnClickListener {
                val values = editTexts.map { it.text.toString().trim() }
                onSave(values, channelSwitch.isChecked)
                val progress = ProgressDialog.show(this@ForwardingChannelsActivity, "正在测试", "正在连接服务接口...", true, false)
                lifecycleScope.launch {
                    val res = withContext(Dispatchers.IO) {
                        ChannelTestSender.sendTest(this@ForwardingChannelsActivity, channelId)
                    }
                    progress.dismiss()
                    if (res.isSuccess) {
                        toast("✅ 发送成功！${res.getOrDefault("通道连通正常")}")
                    } else {
                        toast("❌ 发送失败：${res.exceptionOrNull()?.message ?: "未知异常"}")
                    }
                }
            }
        }
        container.addView(testButton)

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton(R.string.forwarding_save) { _, _ ->
                val values = editTexts.map { it.text.toString().trim() }
                onSave(values, channelSwitch.isChecked)
                updateSummaries()
                toast("保存成功")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }

    private fun enforceForwardingDisclaimer() {
        if (!multiConfig.hasAcceptedDisclaimer()) {
            showForwardingDisclaimer(requireAcceptance = true)
        }
    }

    private fun showForwardingDisclaimer(requireAcceptance: Boolean) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.forwarding_disclaimer_title)
            .setMessage(R.string.forwarding_disclaimer_body)
            .setPositiveButton(
                if (requireAcceptance) R.string.forwarding_disclaimer_accept else android.R.string.ok
            ) { _, _ ->
                if (requireAcceptance) {
                    multiConfig.acceptCurrentDisclaimer()
                    updateSummaries()
                }
            }
            .apply {
                if (requireAcceptance) {
                    setNegativeButton(R.string.forwarding_disclaimer_decline) { _, _ -> finish() }
                }
            }
            .create()

        dialog.setCancelable(!requireAcceptance)
        dialog.setCanceledOnTouchOutside(!requireAcceptance)
        dialog.setOnCancelListener {
            if (requireAcceptance) finish()
        }
        dialog.show()
        dialog.applySmsDialogColors()
    }

    private fun showSimLabelDialog(slotIndex: Int) {
        val labelEditor = EditText(this).apply {
            setText(if (slotIndex == 0) multiConfig.simOneLabel else multiConfig.simTwoLabel)
            hint = getString(if (slotIndex == 0) R.string.forwarding_sim_one_hint else R.string.forwarding_sim_two_hint)
            setTextColor(Color.rgb(17, 17, 17))
            setHintTextColor(Color.rgb(120, 120, 120))
            isSingleLine = true
        }
        val numberEditor = EditText(this).apply {
            setText(if (slotIndex == 0) multiConfig.simOneNumber else multiConfig.simTwoNumber)
            hint = getString(R.string.forwarding_sim_number_hint)
            inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(Color.rgb(17, 17, 17))
            setHintTextColor(Color.rgb(120, 120, 120))
            isSingleLine = true
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(labelEditor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(numberEditor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(if (slotIndex == 0) R.string.forwarding_sim_one_label else R.string.forwarding_sim_two_label)
            .setView(container)
            .setPositiveButton(R.string.forwarding_save) { _, _ ->
                if (slotIndex == 0) {
                    multiConfig.simOneLabel = labelEditor.text.toString()
                    multiConfig.simOneNumber = numberEditor.text.toString()
                } else {
                    multiConfig.simTwoLabel = labelEditor.text.toString()
                    multiConfig.simTwoNumber = numberEditor.text.toString()
                }
                updateSummaries()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }

    private fun showTemplateDialog() {
        val labels = arrayOf(
            getString(R.string.forwarding_template_compact),
            getString(R.string.forwarding_template_standard),
            getString(R.string.forwarding_template_detailed),
            getString(R.string.forwarding_template_emoji),
            getString(R.string.forwarding_template_custom),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.forwarding_template)
            .setSingleChoiceItems(labels, multiConfig.templateMode) { dialog, which ->
                if (which == MultiForwardConfig.TEMPLATE_CUSTOM) {
                    dialog.dismiss()
                    showCustomTemplateDialog()
                } else {
                    multiConfig.templateMode = which
                    updateSummaries()
                    dialog.dismiss()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }

    private fun showCustomTemplateDialog() {
        val density = resources.displayMetrics.density
        val paddingLarge = (24 * density).toInt()
        val paddingSmall = (12 * density).toInt()

        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingLarge, paddingSmall, paddingLarge, paddingSmall)
        }
        scroll.addView(container)

        val tipText = TextView(this).apply {
            text = getString(R.string.forwarding_custom_template_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        container.addView(tipText)

        val templateInput = EditText(this).apply {
            setText(multiConfig.customTemplate.ifEmpty { "[from]\n[msg]\n[time]" })
            setTextColor(Color.rgb(17, 17, 17))
            setHintTextColor(Color.GRAY)
            minLines = 4
            maxLines = 8
            gravity = android.view.Gravity.TOP or android.view.Gravity.START
        }
        container.addView(templateInput)

        AlertDialog.Builder(this)
            .setTitle(R.string.forwarding_template_custom)
            .setView(scroll)
            .setPositiveButton(R.string.forwarding_save) { _, _ ->
                val newTpl = templateInput.text.toString().trim()
                multiConfig.customTemplate = newTpl
                multiConfig.templateMode = MultiForwardConfig.TEMPLATE_CUSTOM
                updateSummaries()
                toast("自定义模板已保存")
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }
}
