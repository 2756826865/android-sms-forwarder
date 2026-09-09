package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityWecomRemoteControlSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.bindMiuiOptions
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.SimSendMode
import org.fossify.messages.remote.runtime.RemoteSourceRuntimeManager
import org.fossify.messages.services.WeComRemoteControlService

class WeComRemoteControlSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityWecomRemoteControlSettingsBinding::inflate)
    private val config by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.wecomRemoteAppbar),
            padBottomImeAndSystem = listOf(binding.wecomRemoteScrollview),
        )
        setupMaterialScrollListener(binding.wecomRemoteScrollview, binding.wecomRemoteAppbar)
        setupTopAppBar(binding.wecomRemoteAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.wecomRemoteAppbar, binding.wecomRemoteToolbar)
        binding.wecomRemoteSendSim.bindMiuiOptions(R.array.dingtalk_remote_sim_options)
        loadConfig()

        binding.wecomRemoteSave.setOnClickListener {
            if (!saveConfig()) return@setOnClickListener
            if (config.weComRemoteControlEnabled) {
                WeComRemoteControlService.ensureStarted(applicationContext)
            } else {
                WeComRemoteControlService.stop(applicationContext)
            }
            toast(R.string.forwarding_saved)
            loadConfig()
        }

        binding.wecomRemoteTest.setOnClickListener {
            if (!binding.wecomRemoteEnabled.isChecked) {
                toast("请先开启企业微信长连接远程指令")
                return@setOnClickListener
            }
            if (!saveConfig()) return@setOnClickListener

            val chatId = config.weComRemoteChatId().trim()
            if (chatId.isBlank()) {
                toast("请先填写用于测试推送的 Chat ID / User ID")
                return@setOnClickListener
            }

            toast("正在通过长连接发送测试推送…")
            org.fossify.commons.helpers.ensureBackgroundThread {
                val runtimeManager = RemoteSourceRuntimeManager.getInstance(applicationContext)
                val testContent = "【短信转发器 · 企业微信长连接测试】\n连接状态正常，握手与主动消息推送成功！"
                val result = runtimeManager.sendWeComPush("legacy_remote_wecom", chatId, testContent)
                runOnUiThread {
                    if (result.isSuccess) {
                        toast("测试推送成功！")
                        config.appendWeComRemoteLog("测试推送成功 -> $chatId")
                    } else {
                        val detail = result.weComErrorCode?.let { "${result.message}（错误码 $it）" } ?: result.message
                        toast("测试推送失败：$detail")
                        config.appendWeComRemoteLog("测试推送失败 -> $chatId：$detail")
                    }
                    loadConfig()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.wecomRemoteAppbar, binding.wecomRemoteToolbar)
        loadConfig()
    }

    private fun loadConfig() = with(binding) {
        wecomRemoteEnabled.isChecked = config.weComRemoteControlEnabled
        wecomRemoteBotId.setText(config.weComRemoteBotId())
        wecomRemoteSecret.setText(config.weComRemoteSecret())
        wecomRemoteChatId.setText(config.weComRemoteChatId())
        wecomRemoteCustomPrefix.setText(config.weComRemoteCustomPrefix())
        wecomRemoteSendSim.setSelection(
            when (config.weComRemoteSendSimMode) {
                SimSendMode.SIM1 -> 1
                SimSendMode.SIM2 -> 2
                else -> 0
            },
        )
        wecomRemoteStatus.text = config.weComRemoteConnectionStatus.ifBlank { "尚未连接" }
        wecomRemoteLogs.text = config.weComRemoteLogs().ifBlank { "暂无日志" }

        val capturedChatId = config.lastCapturedWeComChatId.trim()
        if (capturedChatId.isNotBlank()) {
            wecomRemoteAutoChatIdLayout.visibility = android.view.View.VISIBLE
            wecomRemoteAutoChatIdText.text = "💡 检测到最近会话: $capturedChatId"
            wecomRemoteAutoChatIdBtn.setOnClickListener {
                wecomRemoteChatId.setText(capturedChatId)
                toast("已填入最近捕获的会话 ID")
            }
            wecomRemoteCopyChatIdBtn.setOnClickListener {
                val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Chat ID", capturedChatId))
                toast("已复制会话 ID 到剪贴板")
            }
        } else {
            wecomRemoteAutoChatIdLayout.visibility = android.view.View.GONE
        }
    }

    private fun saveConfig(): Boolean {
        val botId = binding.wecomRemoteBotId.value.trim()
        val secret = binding.wecomRemoteSecret.value.trim()
        val chatId = binding.wecomRemoteChatId.value.trim()
        val customPrefix = binding.wecomRemoteCustomPrefix.value.trim()
        val enabled = binding.wecomRemoteEnabled.isChecked
        if (enabled && (botId.isBlank() || secret.isBlank())) {
            toast("请填写 Bot ID 和 Secret")
            return false
        }
        config.weComRemoteControlEnabled = enabled
        config.saveWeComRemoteControl(botId, secret, chatId, customPrefix)
        config.weComRemoteSendSimMode = when (binding.wecomRemoteSendSim.selectedItemPosition) {
            1 -> SimSendMode.SIM1
            2 -> SimSendMode.SIM2
            else -> SimSendMode.DEFAULT
        }
        org.fossify.messages.remote.repository.RemoteSourceRepository
            .getInstance(applicationContext)
            .syncLegacySourcesFromClassic()
        return true
    }
}
