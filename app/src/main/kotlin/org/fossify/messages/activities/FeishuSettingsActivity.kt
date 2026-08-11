package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.databinding.ActivityFeishuSettingsBinding
import org.fossify.messages.extensions.applyWhitePageChrome
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.MultiChannelForwardWorker

class FeishuSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityFeishuSettingsBinding::inflate)
    private val forwardingConfig by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.feishuScrollview))
        setupMaterialScrollListener(binding.feishuScrollview, binding.feishuAppbar)
        setupTopAppBar(binding.feishuAppbar, NavigationIcon.Arrow)
        loadConfig()

        binding.feishuSave.setOnClickListener {
            if (saveConfig()) toast("配置已保存")
        }

        binding.feishuTest.setOnClickListener {
            if (saveConfig(requireWebhook = true)) {
                val sender = binding.feishuTestSender.value.trim()
                val body = binding.feishuTestBody.value.trim()
                if (sender.isBlank() || body.isBlank()) {
                    toast("请填写测试发送方和短信正文")
                } else {
                    MultiChannelForwardWorker.enqueueTest(this, "feishu", sender, body)
                    toast("测试消息已加入发送队列")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyWhitePageChrome()
    }

    private fun loadConfig() = with(binding) {
        feishuEnabled.isChecked = forwardingConfig.feishuEnabled
        feishuWebhook.setText(forwardingConfig.feishuWebhook())
        feishuSecret.setText(forwardingConfig.feishuSecret())
        feishuTestSender.setText("10086")
        feishuTestBody.setText("这是一条飞书转发测试消息")
    }

    private fun saveConfig(requireWebhook: Boolean = false): Boolean {
        val webhook = binding.feishuWebhook.text?.toString().orEmpty().trim()
        val secret = binding.feishuSecret.text?.toString().orEmpty().trim()
        if ((requireWebhook || binding.feishuEnabled.isChecked) && webhook.isBlank()) {
            toast("请填写 Webhook 地址")
            return false
        }
        forwardingConfig.feishuEnabled = binding.feishuEnabled.isChecked
        forwardingConfig.saveFeishu(webhook, secret)
        return true
    }
}
