package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.databinding.ActivityTelegramSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig

class TelegramSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityTelegramSettingsBinding::inflate)
    private val forwardingConfig by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.telegramAppbar),
            padBottomImeAndSystem = listOf(binding.telegramScrollview),
        )
        setupMaterialScrollListener(binding.telegramScrollview, binding.telegramAppbar)
        setupTopAppBar(binding.telegramAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.telegramAppbar, binding.telegramToolbar)
        loadConfig()

        binding.telegramSave.setOnClickListener { if (saveConfig()) toast("配置已保存") }
        binding.telegramTest.setOnClickListener {
            if (saveConfig(requireConfig = true)) {
                val sender = binding.telegramTestSender.value.trim()
                val body = binding.telegramTestBody.value.trim()
                if (sender.isBlank() || body.isBlank()) {
                    toast("请填写测试发送方和短信正文")
                } else {
                    MultiChannelForwardWorker.enqueueTest(this, ForwardingChannels.TELEGRAM, sender, body)
                    toast("测试消息已加入发送队列")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.telegramAppbar, binding.telegramToolbar)
    }

    private fun loadConfig() = with(binding) {
        telegramEnabled.isChecked = forwardingConfig.telegramEnabled
        telegramBotToken.setText(forwardingConfig.telegramBotToken())
        telegramChatId.setText(forwardingConfig.telegramChatId())
        telegramApiHost.setText(forwardingConfig.telegramApiHost().ifBlank { "https://api.telegram.org" })
        telegramTestSender.setText("10086")
        telegramTestBody.setText("这是一条 Telegram 转发测试消息")
    }

    private fun saveConfig(requireConfig: Boolean = false): Boolean {
        val botToken = binding.telegramBotToken.value.trim()
        val chatId = binding.telegramChatId.value.trim()
        val apiHost = binding.telegramApiHost.value.trim().trimEnd('/')
        val needsConfig = requireConfig || binding.telegramEnabled.isChecked
        if (needsConfig && (botToken.isBlank() || chatId.isBlank())) {
            toast("请填写 Telegram Bot Token 和 Chat ID")
            return false
        }
        if (needsConfig && apiHost.isNotBlank() && !apiHost.startsWith("https://")) {
            toast("Telegram API 地址必须以 https:// 开头")
            return false
        }
        forwardingConfig.telegramEnabled = binding.telegramEnabled.isChecked
        forwardingConfig.saveTelegram(botToken, chatId, apiHost)
        return true
    }
}
