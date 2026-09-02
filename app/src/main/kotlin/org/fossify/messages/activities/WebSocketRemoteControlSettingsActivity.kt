package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityWebsocketRemoteControlSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.bindMiuiOptions
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.SimSendMode
import org.fossify.messages.services.WebSocketRemoteControlService

class WebSocketRemoteControlSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityWebsocketRemoteControlSettingsBinding::inflate)
    private val config by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.wsRemoteAppbar),
            padBottomImeAndSystem = listOf(binding.wsRemoteScrollview),
        )
        setupMaterialScrollListener(binding.wsRemoteScrollview, binding.wsRemoteAppbar)
        setupTopAppBar(binding.wsRemoteAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.wsRemoteAppbar, binding.wsRemoteToolbar)
        binding.wsRemoteSendSim.bindMiuiOptions(R.array.dingtalk_remote_sim_options)
        loadConfig()

        binding.wsRemoteSave.setOnClickListener {
            if (!saveConfig()) return@setOnClickListener
            if (config.websocketRemoteControlEnabled) {
                WebSocketRemoteControlService.ensureStarted(applicationContext)
            } else {
                WebSocketRemoteControlService.stop(applicationContext)
            }
            toast(R.string.forwarding_saved)
            loadConfig()
        }

        binding.wsRemoteTest.setOnClickListener {
            if (!binding.wsRemoteEnabled.isChecked) {
                toast("请先开启 WebSocket 远程指令")
                return@setOnClickListener
            }
            if (!saveConfig()) return@setOnClickListener
            toast("正在测试连接 WebSocket…")
            val url = config.websocketRemoteUrl()
            val token = config.websocketRemoteToken()
            org.fossify.commons.helpers.ensureBackgroundThread {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val req = okhttp3.Request.Builder().url(url).apply {
                        if (token.isNotBlank()) {
                            header("Authorization", "Bearer $token")
                            header("X-Token", token)
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
                        config.appendWebSocketRemoteLog("WebSocket 测试握手成功")
                        runOnUiThread {
                            toast("WebSocket 连接测试成功！")
                            WebSocketRemoteControlService.ensureStarted(applicationContext)
                            loadConfig()
                        }
                    } else {
                        val failDesc = errMsg.ifBlank { "连接超时" }
                        config.appendWebSocketRemoteLog("WebSocket 测试失败：$failDesc")
                        runOnUiThread {
                            toast("WebSocket 测试失败：$failDesc")
                            loadConfig()
                        }
                    }
                } catch (e: Throwable) {
                    val err = e.message ?: e.javaClass.simpleName
                    config.appendWebSocketRemoteLog("测试异常：$err")
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
        applyMiuiTopAppBarChrome(binding.wsRemoteAppbar, binding.wsRemoteToolbar)
        loadConfig()
    }

    private fun loadConfig() = with(binding) {
        wsRemoteEnabled.isChecked = config.websocketRemoteControlEnabled
        wsRemoteUrl.setText(config.websocketRemoteUrl())
        wsRemoteToken.setText(config.websocketRemoteToken())
        wsRemoteCustomPrefix.setText(config.websocketRemoteCustomPrefix())
        wsRemoteSendSim.setSelection(
            when (config.websocketRemoteSendSimMode) {
                SimSendMode.SIM1 -> 1
                SimSendMode.SIM2 -> 2
                else -> 0
            },
        )
        wsRemoteStatus.text = config.websocketRemoteConnectionStatus.ifBlank { "尚未连接" }
        wsRemoteLogs.text = config.websocketRemoteLogs().ifBlank { "暂无日志" }
    }

    private fun saveConfig(): Boolean {
        val url = binding.wsRemoteUrl.value.trim()
        val token = binding.wsRemoteToken.value.trim()
        val customPrefix = binding.wsRemoteCustomPrefix.value.trim()
        val enabled = binding.wsRemoteEnabled.isChecked
        if (enabled && url.isBlank()) {
            toast("请填写 WebSocket URL")
            return false
        }
        config.websocketRemoteControlEnabled = enabled
        config.saveWebSocketRemoteControl(url, token, customPrefix)
        config.websocketRemoteSendSimMode = when (binding.wsRemoteSendSim.selectedItemPosition) {
            1 -> SimSendMode.SIM1
            2 -> SimSendMode.SIM2
            else -> SimSendMode.DEFAULT
        }
        return true
    }
}
