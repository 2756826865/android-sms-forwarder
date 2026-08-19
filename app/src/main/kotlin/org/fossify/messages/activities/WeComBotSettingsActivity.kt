package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.databinding.ActivityWecomBotSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.MultiChannelForwardWorker

class WeComBotSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityWecomBotSettingsBinding::inflate)
    private val forwardingConfig by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.wecomBotAppbar),
            padBottomImeAndSystem = listOf(binding.wecomBotScrollview),
        )
        setupMaterialScrollListener(binding.wecomBotScrollview, binding.wecomBotAppbar)
        setupTopAppBar(binding.wecomBotAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.wecomBotAppbar, binding.wecomBotToolbar)
        loadConfig()

        binding.wecomBotSave.setOnClickListener {
            if (saveConfig()) toast("配置已保存")
        }

        binding.wecomBotTest.setOnClickListener {
            if (saveConfig(requireWebhook = true)) {
                val sender = binding.wecomBotTestSender.value.trim()
                val body = binding.wecomBotTestBody.value.trim()
                if (sender.isBlank() || body.isBlank()) {
                    toast("请填写测试发送方和短信正文")
                } else {
                    MultiChannelForwardWorker.enqueueTest(this, "wecom_bot", sender, body)
                    toast("测试消息已加入发送队列")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.wecomBotAppbar, binding.wecomBotToolbar)
    }

    private fun loadConfig() = with(binding) {
        wecomBotEnabled.isChecked = forwardingConfig.weComBotEnabled
        wecomBotWebhook.setText(forwardingConfig.weComBotWebhook())
        wecomBotTestSender.setText("10086")
        wecomBotTestBody.setText("这是一条企业微信群机器人转发测试消息")
    }

    private fun saveConfig(requireWebhook: Boolean = false): Boolean {
        val webhook = binding.wecomBotWebhook.text?.toString().orEmpty().trim()
        if ((requireWebhook || binding.wecomBotEnabled.isChecked) && webhook.isBlank()) {
            toast("请填写 Webhook 地址")
            return false
        }
        forwardingConfig.weComBotEnabled = binding.wecomBotEnabled.isChecked
        forwardingConfig.saveWeComBot(webhook)
        return true
    }
}
