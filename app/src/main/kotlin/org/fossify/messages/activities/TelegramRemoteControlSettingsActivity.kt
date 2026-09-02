package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityTelegramRemoteControlSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.bindMiuiOptions
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.SimSendMode
import org.fossify.messages.services.TelegramRemoteControlService

class TelegramRemoteControlSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityTelegramRemoteControlSettingsBinding::inflate)
    private val config by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.tgRemoteAppbar),
            padBottomImeAndSystem = listOf(binding.tgRemoteScrollview),
        )
        setupMaterialScrollListener(binding.tgRemoteScrollview, binding.tgRemoteAppbar)
        setupTopAppBar(binding.tgRemoteAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.tgRemoteAppbar, binding.tgRemoteToolbar)
        binding.tgRemoteSendSim.bindMiuiOptions(R.array.dingtalk_remote_sim_options)
        loadConfig()

        binding.tgRemoteSave.setOnClickListener {
            if (!saveConfig()) return@setOnClickListener
            if (config.telegramRemoteControlEnabled) {
                TelegramRemoteControlService.ensureStarted(applicationContext)
            } else {
                TelegramRemoteControlService.stop(applicationContext)
            }
            toast(R.string.forwarding_saved)
            loadConfig()
        }

        binding.tgRemoteTest.setOnClickListener {
            if (!binding.tgRemoteEnabled.isChecked) {
                toast("请先开启 Telegram 远程指令")
                return@setOnClickListener
            }
            if (!saveConfig()) return@setOnClickListener
            toast("正在测试连接 Telegram Bot…")
            val botToken = config.telegramRemoteBotToken()
            val baseUrl = config.telegramRemoteCustomHost().trim().ifBlank { "https://api.telegram.org" }.trimEnd('/')
            org.fossify.commons.helpers.ensureBackgroundThread {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = okhttp3.Request.Builder()
                        .url("$baseUrl/bot$botToken/getMe")
                        .build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        val json = org.json.JSONObject(body)
                        if (response.isSuccessful && json.optBoolean("ok", false)) {
                            val botName = json.optJSONObject("result")?.optString("username").orEmpty()
                            config.appendTelegramRemoteLog("测试连接成功：@$botName")
                            runOnUiThread {
                                toast("Telegram 连接成功：@$botName")
                                TelegramRemoteControlService.ensureStarted(applicationContext)
                                loadConfig()
                            }
                        } else {
                            val desc = json.optString("description", body)
                            config.appendTelegramRemoteLog("测试失败：$desc")
                            runOnUiThread {
                                toast("Telegram 连接失败：$desc")
                                loadConfig()
                            }
                        }
                    }
                } catch (e: Throwable) {
                    val err = e.message ?: e.javaClass.simpleName
                    config.appendTelegramRemoteLog("测试连接异常：$err")
                    runOnUiThread {
                        toast("连接异常：$err")
                        loadConfig()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.tgRemoteAppbar, binding.tgRemoteToolbar)
        loadConfig()
    }

    private fun loadConfig() = with(binding) {
        tgRemoteEnabled.isChecked = config.telegramRemoteControlEnabled
        tgRemoteBotToken.setText(config.telegramRemoteBotToken())
        tgRemoteChatId.setText(config.telegramRemoteChatId())
        tgRemoteCustomHost.setText(config.telegramRemoteCustomHost())
        tgRemoteAuthUsers.setText(config.telegramRemoteAuthorizedUsers())
        tgRemoteCustomPrefix.setText(config.telegramRemoteCustomPrefix())
        tgRemoteSendSim.setSelection(
            when (config.telegramRemoteSendSimMode) {
                SimSendMode.SIM1 -> 1
                SimSendMode.SIM2 -> 2
                else -> 0
            },
        )
        tgRemoteStatus.text = config.telegramRemoteConnectionStatus.ifBlank { "尚未连接" }
        tgRemoteLogs.text = config.telegramRemoteLogs().ifBlank { "暂无日志" }
    }

    private fun saveConfig(): Boolean {
        val botToken = binding.tgRemoteBotToken.value.trim()
        val chatId = binding.tgRemoteChatId.value.trim()
        val customHost = binding.tgRemoteCustomHost.value.trim()
        val authUsers = binding.tgRemoteAuthUsers.value.trim()
        val customPrefix = binding.tgRemoteCustomPrefix.value.trim()
        val enabled = binding.tgRemoteEnabled.isChecked
        if (enabled && botToken.isBlank()) {
            toast("请填写 Bot Token")
            return false
        }
        config.telegramRemoteControlEnabled = enabled
        config.saveTelegramRemoteControl(botToken, chatId, customHost, authUsers, customPrefix)
        config.telegramRemoteSendSimMode = when (binding.tgRemoteSendSim.selectedItemPosition) {
            1 -> SimSendMode.SIM1
            2 -> SimSendMode.SIM2
            else -> SimSendMode.DEFAULT
        }
        return true
    }
}
