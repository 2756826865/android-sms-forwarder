package org.fossify.messages.activities

import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.Toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityDingtalkSettingsBinding
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.MultiChannelForwardWorker

class DingTalkSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityDingtalkSettingsBinding::inflate)
    private val forwardingConfig by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.dingtalkScrollview))
        setupMaterialScrollListener(binding.dingtalkScrollview, binding.dingtalkAppbar)
        setupTopAppBar(binding.dingtalkAppbar, NavigationIcon.Arrow)
        loadConfig()

        binding.dingtalkSave.setOnClickListener {
            if (saveConfig()) {
                toast("配置已保存")
            }
        }

        binding.dingtalkTest.setOnClickListener {
            if (saveConfig(requireWebhook = true)) {
                val sender = binding.dingtalkTestSender.value.trim()
                val body = binding.dingtalkTestBody.value.trim()
                if (sender.isBlank() || body.isBlank()) {
                    toast("请填写测试发送方和短信正文")
                } else {
                    MultiChannelForwardWorker.enqueueTest(this, "dingtalk", sender, body)
                    toast("测试消息已加入发送队列")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
    }

    private fun loadConfig() = with(binding) {
        dingtalkEnabled.isChecked = forwardingConfig.dingTalkEnabled
        dingtalkWebhook.setText(forwardingConfig.dingTalkWebhook())
        dingtalkSecret.setText(forwardingConfig.dingTalkSecret())
        dingtalkTestSender.setText("10086")
        dingtalkTestBody.setText("这是一条钉钉转发测试消息")
    }

    private fun saveConfig(requireWebhook: Boolean = false): Boolean {
        val webhook = binding.dingtalkWebhook.text?.toString().orEmpty().trim()
        val secret = binding.dingtalkSecret.text?.toString().orEmpty().trim()
        if ((requireWebhook || binding.dingtalkEnabled.isChecked) && webhook.isBlank()) {
            toast("请填写 Webhook 地址")
            return false
        }

        forwardingConfig.dingTalkEnabled = binding.dingtalkEnabled.isChecked
        forwardingConfig.saveDingTalk(webhook, secret)
        return true
    }
}
