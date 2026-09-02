package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityEmailRemoteControlSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.bindMiuiOptions
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.SimSendMode
import org.fossify.messages.remote.EmailRemoteCommandPoller
import org.fossify.messages.services.EmailRemoteControlService
import org.fossify.commons.helpers.ensureBackgroundThread

class EmailRemoteControlSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityEmailRemoteControlSettingsBinding::inflate)
    private val config by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.emailRemoteAppbar),
            padBottomImeAndSystem = listOf(binding.emailRemoteScrollview),
        )
        setupMaterialScrollListener(binding.emailRemoteScrollview, binding.emailRemoteAppbar)
        setupTopAppBar(binding.emailRemoteAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.emailRemoteAppbar, binding.emailRemoteToolbar)
        binding.emailRemoteSendSim.bindMiuiOptions(R.array.dingtalk_remote_sim_options)
        loadConfig()

        binding.emailRemoteSave.setOnClickListener {
            if (!saveConfig()) return@setOnClickListener
            if (config.emailRemoteControlEnabled) {
                EmailRemoteControlService.ensureStarted(applicationContext)
            } else {
                EmailRemoteControlService.stop(applicationContext)
            }
            toast(R.string.forwarding_saved)
            loadConfig()
        }

        binding.emailRemoteTest.setOnClickListener {
            if (!binding.emailRemoteEnabled.isChecked) {
                toast("请先开启邮箱远程指令")
                return@setOnClickListener
            }
            if (!saveConfig()) return@setOnClickListener
            toast("正在测试连接 IMAP 邮箱…")
            ensureBackgroundThread {
                val count = EmailRemoteCommandPoller(applicationContext).pollOnce()
                runOnUiThread {
                    toast("连接测试完成，检测到 $count 条新指令")
                    loadConfig()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.emailRemoteAppbar, binding.emailRemoteToolbar)
        loadConfig()
    }

    private fun loadConfig() = with(binding) {
        emailRemoteEnabled.isChecked = config.emailRemoteControlEnabled
        emailRemoteHost.setText(config.emailRemoteHost().ifBlank { "imap.qq.com" })
        emailRemotePort.setText(config.emailRemotePort().toString())
        emailRemoteUser.setText(config.emailRemoteUser())
        emailRemotePassword.setText(config.emailRemotePassword())
        emailRemoteAuthSenders.setText(config.emailRemoteAuthorizedSenders())
        emailRemoteCustomPrefix.setText(config.emailRemoteCustomPrefix())
        emailRemoteSendSim.setSelection(
            when (config.emailRemoteSendSimMode) {
                SimSendMode.SIM1 -> 1
                SimSendMode.SIM2 -> 2
                else -> 0
            },
        )
        emailRemoteStatus.text = config.emailRemoteConnectionStatus.ifBlank { "尚未连接" }
        emailRemoteLogs.text = config.emailRemoteLogs().ifBlank { "暂无日志" }
    }

    private fun saveConfig(): Boolean {
        val host = binding.emailRemoteHost.value.trim()
        val port = binding.emailRemotePort.value.trim().toIntOrNull() ?: 993
        val user = binding.emailRemoteUser.value.trim()
        val pass = binding.emailRemotePassword.value.trim()
        val authSenders = binding.emailRemoteAuthSenders.value.trim()
        val customPrefix = binding.emailRemoteCustomPrefix.value.trim()
        val enabled = binding.emailRemoteEnabled.isChecked
        if (enabled && (host.isBlank() || user.isBlank() || pass.isBlank())) {
            toast("请填写 IMAP 主机 / 账号 / 密码")
            return false
        }
        config.emailRemoteControlEnabled = enabled
        config.saveEmailRemoteControl(host, port, user, pass, authSenders, customPrefix = customPrefix)
        config.emailRemoteSendSimMode = when (binding.emailRemoteSendSim.selectedItemPosition) {
            1 -> SimSendMode.SIM1
            2 -> SimSendMode.SIM2
            else -> SimSendMode.DEFAULT
        }
        return true
    }
}