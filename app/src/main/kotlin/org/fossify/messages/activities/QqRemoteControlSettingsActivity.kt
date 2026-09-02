package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityQqRemoteControlSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.bindMiuiOptions
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.SimSendMode
import org.fossify.messages.services.QqRemoteControlService

class QqRemoteControlSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityQqRemoteControlSettingsBinding::inflate)
    private val config by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.qqRemoteAppbar),
            padBottomImeAndSystem = listOf(binding.qqRemoteScrollview),
        )
        setupMaterialScrollListener(binding.qqRemoteScrollview, binding.qqRemoteAppbar)
        setupTopAppBar(binding.qqRemoteAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.qqRemoteAppbar, binding.qqRemoteToolbar)
        binding.qqRemoteSendSim.bindMiuiOptions(R.array.dingtalk_remote_sim_options)
        loadConfig()

        binding.qqRemoteSave.setOnClickListener {
            if (!saveConfig()) return@setOnClickListener
            if (config.qqRemoteControlEnabled) {
                QqRemoteControlService.ensureStarted(applicationContext)
            } else {
                QqRemoteControlService.stop(applicationContext)
            }
            toast(R.string.forwarding_saved)
            loadConfig()
        }

        binding.qqRemoteTest.setOnClickListener {
            if (!binding.qqRemoteEnabled.isChecked) {
                toast("请先开启 QQ 远程指令")
                return@setOnClickListener
            }
            if (!saveConfig()) return@setOnClickListener
            toast("正在测试连接 OneBot 11 QQ 客户端…")
            val wsUrl = config.qqRemoteWsUrl()
            val token = config.qqRemoteToken()
            org.fossify.commons.helpers.ensureBackgroundThread {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val req = okhttp3.Request.Builder().url(wsUrl).apply {
                        if (token.isNotBlank()) {
                            header("Authorization", "Bearer $token")
                        }
                    }.build()
                    val latch = java.util.concurrent.CountDownLatch(1)
                    var connected = false
                    var errMsg = ""
                    val ws = client.newWebSocket(req, object : okhttp3.WebSocketListener() {
                        override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                            connected = true
                            webSocket.close(1000, "test done")
                            latch.countDown()
                        }
                        override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                            errMsg = t.message ?: t.javaClass.simpleName
                            latch.countDown()
                        }
                    })
                    latch.await(9, java.util.concurrent.TimeUnit.SECONDS)
                    if (connected) {
                        config.appendQqRemoteLog("OneBot 11 QQ 测试握手成功")
                        runOnUiThread {
                            toast("OneBot 11 连接测试成功！")
                            QqRemoteControlService.ensureStarted(applicationContext)
                            loadConfig()
                        }
                    } else {
                        val failDesc = errMsg.ifBlank { "连接超时" }
                        config.appendQqRemoteLog("OneBot 11 测试失败：$failDesc")
                        runOnUiThread {
                            toast("QQ 连接测试失败：$failDesc")
                            loadConfig()
                        }
                    }
                } catch (e: Throwable) {
                    val err = e.message ?: e.javaClass.simpleName
                    config.appendQqRemoteLog("测试异常：$err")
                    runOnUiThread {
                        toast("测试异常：$err")
                        loadConfig()
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.qqRemoteAppbar, binding.qqRemoteToolbar)
        loadConfig()
    }

    private fun loadConfig() = with(binding) {
        qqRemoteEnabled.isChecked = config.qqRemoteControlEnabled
        qqRemoteWsUrl.setText(config.qqRemoteWsUrl())
        qqRemoteToken.setText(config.qqRemoteToken())
        qqRemoteAuthUsers.setText(config.qqRemoteAuthorizedUsers())
        qqRemoteAuthGroups.setText(config.qqRemoteAuthorizedGroups())
        qqRemoteCustomPrefix.setText(config.qqRemoteCustomPrefix())
        qqRemoteRequireAt.isChecked = config.qqRemoteRequireAt
        qqRemoteSendSim.setSelection(
            when (config.qqRemoteSendSimMode) {
                SimSendMode.SIM1 -> 1
                SimSendMode.SIM2 -> 2
                else -> 0
            },
        )
        qqRemoteStatus.text = config.qqRemoteConnectionStatus.ifBlank { "尚未连接" }
        qqRemoteLogs.text = config.qqRemoteLogs().ifBlank { "暂无日志" }
    }

    private fun saveConfig(): Boolean {
        val wsUrl = binding.qqRemoteWsUrl.value.trim()
        val token = binding.qqRemoteToken.value.trim()
        val authUsers = binding.qqRemoteAuthUsers.value.trim()
        val authGroups = binding.qqRemoteAuthGroups.value.trim()
        val customPrefix = binding.qqRemoteCustomPrefix.value.trim()
        val requireAt = binding.qqRemoteRequireAt.isChecked
        val enabled = binding.qqRemoteEnabled.isChecked
        if (enabled && wsUrl.isBlank()) {
            toast("请填写 OneBot 11 WebSocket URL")
            return false
        }
        config.qqRemoteControlEnabled = enabled
        config.saveQqRemoteControl(wsUrl, token, authUsers, authGroups, requireAt, customPrefix)
        config.qqRemoteSendSimMode = when (binding.qqRemoteSendSim.selectedItemPosition) {
            1 -> SimSendMode.SIM1
            2 -> SimSendMode.SIM2
            else -> SimSendMode.DEFAULT
        }
        return true
    }
}
