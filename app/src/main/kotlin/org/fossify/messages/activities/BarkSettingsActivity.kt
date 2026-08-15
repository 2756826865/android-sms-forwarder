package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.databinding.ActivityBarkSettingsBinding
import org.fossify.messages.extensions.applyMiuiPageChrome
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.ForwardingUrlPolicy
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig

class BarkSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityBarkSettingsBinding::inflate)
    private val forwardingConfig by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.barkScrollview))
        setupMaterialScrollListener(binding.barkScrollview, binding.barkAppbar)
        setupTopAppBar(binding.barkAppbar, NavigationIcon.Arrow)
        loadConfig()

        binding.barkSave.setOnClickListener { if (saveConfig()) toast("配置已保存") }
        binding.barkTest.setOnClickListener {
            if (saveConfig(requireConfig = true)) {
                val sender = binding.barkTestSender.value.trim()
                val body = binding.barkTestBody.value.trim()
                if (sender.isBlank() || body.isBlank()) {
                    toast("请填写测试发送方和短信正文")
                } else {
                    MultiChannelForwardWorker.enqueueTest(this, ForwardingChannels.BARK, sender, body)
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
        barkEnabled.isChecked = forwardingConfig.barkEnabled
        barkServerUrl.setText(forwardingConfig.barkServerUrl().ifBlank { "https://api.day.app" })
        barkDeviceKey.setText(forwardingConfig.barkDeviceKey())
        barkAllowHttp.isChecked = forwardingConfig.barkAllowHttp
        barkTestSender.setText("10086")
        barkTestBody.setText("这是一条 Bark 转发测试消息")
    }

    private fun saveConfig(requireConfig: Boolean = false): Boolean {
        val serverUrl = binding.barkServerUrl.value.trim().trimEnd('/')
        val deviceKey = binding.barkDeviceKey.value.trim()
        val needsConfig = requireConfig || binding.barkEnabled.isChecked
        if (needsConfig && (serverUrl.isBlank() || deviceKey.isBlank())) {
            toast("请填写 Bark 服务地址和 Device Key")
            return false
        }
        if (needsConfig && !ForwardingUrlPolicy.isAllowed(serverUrl, binding.barkAllowHttp.isChecked)) {
            toast("默认必须使用 HTTPS；HTTP 仅允许局域网地址")
            return false
        }
        forwardingConfig.barkEnabled = binding.barkEnabled.isChecked
        forwardingConfig.barkAllowHttp = binding.barkAllowHttp.isChecked
        forwardingConfig.saveBark(serverUrl, deviceKey)
        return true
    }
}
