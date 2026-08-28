package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.databinding.ActivityDiscordSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig

class DiscordSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityDiscordSettingsBinding::inflate)
    private val forwardingConfig by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.discordAppbar),
            padBottomImeAndSystem = listOf(binding.discordScrollview),
        )
        setupMaterialScrollListener(binding.discordScrollview, binding.discordAppbar)
        setupTopAppBar(binding.discordAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.discordAppbar, binding.discordToolbar)
        loadConfig()

        binding.discordSave.setOnClickListener { if (saveConfig()) toast("配置已保存") }
        binding.discordTest.setOnClickListener {
            if (saveConfig(requireConfig = true)) {
                val sender = binding.discordTestSender.value.trim()
                val body = binding.discordTestBody.value.trim()
                if (sender.isBlank() || body.isBlank()) {
                    toast("请填写测试发送方和短信正文")
                } else {
                    MultiChannelForwardWorker.enqueueTest(this, ForwardingChannels.DISCORD, sender, body)
                    toast("测试消息已加入发送队列")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.discordAppbar, binding.discordToolbar)
    }

    private fun loadConfig() = with(binding) {
        discordEnabled.isChecked = forwardingConfig.discordEnabled
        discordWebhookUrl.setText(forwardingConfig.discordWebhookUrl())
        discordTestSender.setText("10086")
        discordTestBody.setText("这是一条 Discord 转发测试消息")
    }

    private fun saveConfig(requireConfig: Boolean = false): Boolean {
        val webhookUrl = binding.discordWebhookUrl.value.trim()
        val needsConfig = requireConfig || binding.discordEnabled.isChecked
        if (needsConfig && webhookUrl.isBlank()) {
            toast("请填写 Discord Webhook 地址")
            return false
        }
        if (needsConfig && !webhookUrl.startsWith("https://")) {
            toast("Discord Webhook 地址必须以 https:// 开头")
            return false
        }
        forwardingConfig.discordEnabled = binding.discordEnabled.isChecked
        forwardingConfig.saveDiscord(webhookUrl)
        return true
    }
}
