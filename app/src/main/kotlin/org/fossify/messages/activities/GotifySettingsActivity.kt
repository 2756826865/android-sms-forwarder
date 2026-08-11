package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.databinding.ActivityGotifySettingsBinding
import org.fossify.messages.extensions.applyMiuiPageChrome
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig

class GotifySettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityGotifySettingsBinding::inflate)
    private val forwardingConfig by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.gotifyScrollview))
        setupMaterialScrollListener(binding.gotifyScrollview, binding.gotifyAppbar)
        setupTopAppBar(binding.gotifyAppbar, NavigationIcon.Arrow)
        loadConfig()
        binding.gotifySave.setOnClickListener { if (saveConfig()) toast("配置已保存") }
        binding.gotifyTest.setOnClickListener {
            if (saveConfig(requireConfig = true)) {
                val sender = binding.gotifyTestSender.value.trim()
                val body = binding.gotifyTestBody.value.trim()
                if (sender.isBlank() || body.isBlank()) {
                    toast("请填写测试发送方和短信正文")
                } else {
                    MultiChannelForwardWorker.enqueueTest(this, ForwardingChannels.GOTIFY, sender, body)
                    toast("测试消息已加入发送队列")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiPageChrome()
    }

    private fun loadConfig() = with(binding) {
        gotifyEnabled.isChecked = forwardingConfig.gotifyEnabled
        gotifyServerUrl.setText(forwardingConfig.gotifyServerUrl())
        gotifyToken.setText(forwardingConfig.gotifyToken())
        gotifyAllowHttp.isChecked = forwardingConfig.gotifyAllowHttp
        gotifyTestSender.setText("10086")
        gotifyTestBody.setText("这是一条 Gotify 转发测试消息")
    }

    private fun saveConfig(requireConfig: Boolean = false): Boolean {
        val serverUrl = binding.gotifyServerUrl.value.trim().trimEnd('/')
        val token = binding.gotifyToken.value.trim()
        val needs = requireConfig || binding.gotifyEnabled.isChecked
        if (needs && (serverUrl.isBlank() || token.isBlank())) {
            toast("请填写 Gotify 服务地址和 Token")
            return false
        }
        if (needs && !serverUrl.startsWith("https://") &&
            !(binding.gotifyAllowHttp.isChecked && serverUrl.startsWith("http://"))
        ) {
            toast("默认必须使用 HTTPS；HTTP 内网地址请开启允许 HTTP")
            return false
        }
        forwardingConfig.gotifyEnabled = binding.gotifyEnabled.isChecked
        forwardingConfig.gotifyAllowHttp = binding.gotifyAllowHttp.isChecked
        forwardingConfig.saveGotify(serverUrl, token)
        return true
    }
}
