package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityDingtalkRemoteControlSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.bindMiuiOptions
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.SimSendMode
import org.fossify.messages.services.DingTalkRemoteControlService

class DingTalkRemoteControlSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityDingtalkRemoteControlSettingsBinding::inflate)
    private val config by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.dingtalkRemoteAppbar),
            padBottomImeAndSystem = listOf(binding.dingtalkRemoteScrollview),
        )
        setupMaterialScrollListener(binding.dingtalkRemoteScrollview, binding.dingtalkRemoteAppbar)
        setupTopAppBar(binding.dingtalkRemoteAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.dingtalkRemoteAppbar, binding.dingtalkRemoteToolbar)
        binding.dingtalkRemoteSendSim.bindMiuiOptions(R.array.dingtalk_remote_sim_options)
        loadConfig()
        binding.dingtalkRemoteSave.setOnClickListener {
            if (!saveConfig()) return@setOnClickListener
            if (config.dingTalkRemoteControlEnabled) {
                DingTalkRemoteControlService.ensureStarted(applicationContext)
            } else {
                DingTalkRemoteControlService.stop(applicationContext)
            }
            toast(R.string.forwarding_saved)
            loadConfig()
        }
        binding.dingtalkRemoteTest.setOnClickListener {
            if (!binding.dingtalkRemoteEnabled.isChecked) {
                toast("请先开启钉钉远程指令")
                return@setOnClickListener
            }
            if (!saveConfig()) return@setOnClickListener
            DingTalkRemoteControlService.ensureStarted(applicationContext)
            toast("正在尝试建立 Stream 连接")
            loadConfig()
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.dingtalkRemoteAppbar, binding.dingtalkRemoteToolbar)
        loadConfig()
    }

    private fun loadConfig() = with(binding) {
        dingtalkRemoteEnabled.isChecked = config.dingTalkRemoteControlEnabled
        dingtalkRemoteClientId.setText(config.dingTalkRemoteClientId())
        dingtalkRemoteClientSecret.setText(config.dingTalkRemoteClientSecret())
        dingtalkRemoteSendSim.setSelection(
            when (config.dingTalkRemoteSendSimMode) {
                SimSendMode.SIM1 -> 1
                SimSendMode.SIM2 -> 2
                else -> 0
            },
        )
        dingtalkRemoteStatus.text = config.dingTalkRemoteConnectionStatus.ifBlank { "尚未连接" }
        dingtalkRemoteLogs.text = config.dingTalkRemoteLogs().ifBlank { "暂无日志" }
    }

    private fun saveConfig(): Boolean {
        val clientId = binding.dingtalkRemoteClientId.value.trim()
        val clientSecret = binding.dingtalkRemoteClientSecret.value.trim()
        val enabled = binding.dingtalkRemoteEnabled.isChecked
        if (enabled && (clientId.isBlank() || clientSecret.isBlank())) {
            toast("请填写 Client ID 和 Client Secret")
            return false
        }
        config.dingTalkRemoteControlEnabled = enabled
        config.saveDingTalkRemoteControl(clientId, clientSecret)
        config.dingTalkRemoteSendSimMode = when (binding.dingtalkRemoteSendSim.selectedItemPosition) {
            1 -> SimSendMode.SIM1
            2 -> SimSendMode.SIM2
            else -> SimSendMode.DEFAULT
        }
        return true
    }
}
