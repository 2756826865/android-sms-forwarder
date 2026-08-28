package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.databinding.ActivityWechatTestSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig

class WeChatTestSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityWechatTestSettingsBinding::inflate)
    private val forwardingConfig by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.wechatTestAppbar),
            padBottomImeAndSystem = listOf(binding.wechatTestScrollview),
        )
        setupMaterialScrollListener(binding.wechatTestScrollview, binding.wechatTestAppbar)
        setupTopAppBar(binding.wechatTestAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.wechatTestAppbar, binding.wechatTestToolbar)
        loadConfig()

        binding.wechatTestSave.setOnClickListener { if (saveConfig()) toast("配置已保存") }
        binding.wechatTestTest.setOnClickListener {
            if (saveConfig(requireConfig = true)) {
                val sender = binding.wechatTestTestSender.value.trim()
                val body = binding.wechatTestTestBody.value.trim()
                if (sender.isBlank() || body.isBlank()) {
                    toast("请填写测试发送方和短信正文")
                } else {
                    MultiChannelForwardWorker.enqueueTest(this, ForwardingChannels.WECHAT_TEST, sender, body)
                    toast("测试消息已加入发送队列")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.wechatTestAppbar, binding.wechatTestToolbar)
    }

    private fun loadConfig() = with(binding) {
        wechatTestEnabled.isChecked = forwardingConfig.weChatTestEnabled
        wechatTestAppId.setText(forwardingConfig.weChatTestAppId())
        wechatTestAppSecret.setText(forwardingConfig.weChatTestAppSecret())
        wechatTestTemplateId.setText(forwardingConfig.weChatTestTemplateId())
        wechatTestOpenId.setText(forwardingConfig.weChatTestOpenId())
        wechatTestTestSender.setText("10086")
        wechatTestTestBody.setText("这是一条微信测试号转发测试消息")
    }

    private fun saveConfig(requireConfig: Boolean = false): Boolean {
        val appId = binding.wechatTestAppId.value.trim()
        val appSecret = binding.wechatTestAppSecret.value.trim()
        val templateId = binding.wechatTestTemplateId.value.trim()
        val openId = binding.wechatTestOpenId.value.trim()
        val needsConfig = requireConfig || binding.wechatTestEnabled.isChecked
        if (needsConfig && (appId.isBlank() || appSecret.isBlank() || templateId.isBlank() || openId.isBlank())) {
            toast("请完整填写 AppID、AppSecret、模板 ID 和微信号 OpenID")
            return false
        }
        forwardingConfig.weChatTestEnabled = binding.wechatTestEnabled.isChecked
        forwardingConfig.saveWeChatTest(appId, appSecret, templateId, openId)
        return true
    }
}
