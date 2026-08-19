package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.databinding.ActivityWecomSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.MultiChannelForwardWorker

class WeComSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityWecomSettingsBinding::inflate)
    private val forwardingConfig by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.wecomAppbar),
            padBottomImeAndSystem = listOf(binding.wecomScrollview),
        )
        setupMaterialScrollListener(binding.wecomScrollview, binding.wecomAppbar)
        setupTopAppBar(binding.wecomAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.wecomAppbar, binding.wecomToolbar)
        loadConfig()

        binding.wecomSave.setOnClickListener {
            if (saveConfig()) toast("配置已保存")
        }

        binding.wecomTest.setOnClickListener {
            if (saveConfig(requireConfig = true)) {
                val sender = binding.wecomTestSender.value.trim()
                val body = binding.wecomTestBody.value.trim()
                if (sender.isBlank() || body.isBlank()) {
                    toast("请填写测试发送方和短信正文")
                } else {
                    MultiChannelForwardWorker.enqueueTest(this, "wecom", sender, body)
                    toast("测试消息已加入发送队列")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.wecomAppbar, binding.wecomToolbar)
    }

    private fun loadConfig() = with(binding) {
        wecomEnabled.isChecked = forwardingConfig.weComEnabled
        wecomCorpId.setText(forwardingConfig.weComCorpId())
        wecomAgentId.setText(forwardingConfig.weComAgentId())
        wecomSecret.setText(forwardingConfig.weComSecret())
        wecomToUser.setText(forwardingConfig.weComToUser())
        wecomTestSender.setText("10086")
        wecomTestBody.setText("这是一条企业微信转发测试消息")
    }

    private fun saveConfig(requireConfig: Boolean = false): Boolean {
        val corpId = binding.wecomCorpId.text?.toString().orEmpty().trim()
        val agentId = binding.wecomAgentId.text?.toString().orEmpty().trim()
        val secret = binding.wecomSecret.text?.toString().orEmpty().trim()
        val toUser = binding.wecomToUser.text?.toString().orEmpty().trim()
        if ((requireConfig || binding.wecomEnabled.isChecked) && (corpId.isBlank() || agentId.isBlank() || secret.isBlank())) {
            toast("请填写 CorpId、AgentId 和 Secret")
            return false
        }
        forwardingConfig.weComEnabled = binding.wecomEnabled.isChecked
        forwardingConfig.saveWeCom(corpId, agentId, secret, toUser)
        return true
    }
}
