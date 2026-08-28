package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.databinding.ActivityCustomWebhookSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.ForwardingUrlPolicy
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig

class CustomWebhookSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityCustomWebhookSettingsBinding::inflate)
    private val forwardingConfig by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.customWebhookAppbar),
            padBottomImeAndSystem = listOf(binding.customWebhookScrollview),
        )
        setupMaterialScrollListener(binding.customWebhookScrollview, binding.customWebhookAppbar)
        setupTopAppBar(binding.customWebhookAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.customWebhookAppbar, binding.customWebhookToolbar)
        loadConfig()

        binding.customWebhookSave.setOnClickListener { if (saveConfig()) toast("配置已保存") }
        binding.customWebhookTest.setOnClickListener {
            if (saveConfig(requireConfig = true)) {
                val sender = binding.customWebhookTestSender.value.trim()
                val body = binding.customWebhookTestBody.value.trim()
                if (sender.isBlank() || body.isBlank()) {
                    toast("请填写测试发送方和短信正文")
                } else {
                    MultiChannelForwardWorker.enqueueTest(this, ForwardingChannels.CUSTOM_WEBHOOK, sender, body)
                    toast("测试消息已加入发送队列")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.customWebhookAppbar, binding.customWebhookToolbar)
    }

    private fun loadConfig() = with(binding) {
        customWebhookEnabled.isChecked = forwardingConfig.customWebhookEnabled
        customWebhookUrl.setText(forwardingConfig.customWebhookUrl())
        customWebhookMethod.setText(forwardingConfig.customWebhookMethod)
        customWebhookHeaders.setText(forwardingConfig.customWebhookHeaders())
        customWebhookBodyTemplate.setText(forwardingConfig.customWebhookBodyTemplate())
        customWebhookAllowHttp.isChecked = forwardingConfig.customWebhookAllowHttp
        customWebhookTestSender.setText("10086")
        customWebhookTestBody.setText("这是一条自定义 Webhook 转发测试消息")
    }

    private fun saveConfig(requireConfig: Boolean = false): Boolean {
        val url = binding.customWebhookUrl.value.trim()
        val method = binding.customWebhookMethod.value.trim().uppercase().ifBlank { "POST" }
        val headers = binding.customWebhookHeaders.value.trim()
        val bodyTemplate = binding.customWebhookBodyTemplate.value.trim()
        val needsConfig = requireConfig || binding.customWebhookEnabled.isChecked
        if (needsConfig && url.isBlank()) {
            toast("请填写 Webhook 接口地址")
            return false
        }
        if (needsConfig && !ForwardingUrlPolicy.isAllowed(url, binding.customWebhookAllowHttp.isChecked)) {
            toast("默认必须使用 HTTPS；HTTP 仅允许局域网地址")
            return false
        }
        forwardingConfig.customWebhookEnabled = binding.customWebhookEnabled.isChecked
        forwardingConfig.customWebhookAllowHttp = binding.customWebhookAllowHttp.isChecked
        forwardingConfig.saveCustomWebhook(url, method, headers, bodyTemplate)
        return true
    }
}
