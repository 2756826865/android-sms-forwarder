package org.fossify.messages.activities

import android.os.Bundle
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityFeishuRemoteControlSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.bindMiuiOptions
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.SimSendMode
import org.fossify.messages.services.FeishuRemoteControlService

class FeishuRemoteControlSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityFeishuRemoteControlSettingsBinding::inflate)
    private val config by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.feishuRemoteAppbar),
            padBottomImeAndSystem = listOf(binding.feishuRemoteScrollview),
        )
        setupMaterialScrollListener(binding.feishuRemoteScrollview, binding.feishuRemoteAppbar)
        setupTopAppBar(binding.feishuRemoteAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.feishuRemoteAppbar, binding.feishuRemoteToolbar)
        binding.feishuRemoteSendSim.bindMiuiOptions(R.array.dingtalk_remote_sim_options)
        loadConfig()

        binding.feishuRemoteSave.setOnClickListener {
            if (!saveConfig()) return@setOnClickListener
            if (config.feishuRemoteControlEnabled) {
                FeishuRemoteControlService.ensureStarted(applicationContext)
            } else {
                FeishuRemoteControlService.stop(applicationContext)
            }
            toast(R.string.forwarding_saved)
            loadConfig()
        }

        binding.feishuRemoteTest.setOnClickListener {
            if (!binding.feishuRemoteEnabled.isChecked) {
                toast("请先开启飞书远程指令")
                return@setOnClickListener
            }
            if (!saveConfig()) return@setOnClickListener
            toast("正在校验飞书应用凭证…")
            val appId = config.feishuRemoteAppId()
            val appSecret = config.feishuRemoteAppSecret()
            org.fossify.commons.helpers.ensureBackgroundThread {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val payload = org.json.JSONObject()
                        .put("app_id", appId)
                        .put("app_secret", appSecret)
                    val request = okhttp3.Request.Builder()
                        .url("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal")
                        .post(payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                        .build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        val json = org.json.JSONObject(body)
                        val code = json.optInt("code", -1)
                        if (code == 0) {
                            config.appendFeishuRemoteLog("飞书凭证校验成功 · Token 获取正常")
                            runOnUiThread {
                                toast("飞书应用凭证验证成功！")
                                FeishuRemoteControlService.ensureStarted(applicationContext)
                                loadConfig()
                            }
                        } else {
                            val msg = json.optString("msg", body)
                            config.appendFeishuRemoteLog("校验失败：$msg (code=$code)")
                            runOnUiThread {
                                toast("验证失败：$msg")
                                loadConfig()
                            }
                        }
                    }
                } catch (e: Throwable) {
                    val err = e.message ?: e.javaClass.simpleName
                    config.appendFeishuRemoteLog("连接异常：$err")
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
        applyMiuiTopAppBarChrome(binding.feishuRemoteAppbar, binding.feishuRemoteToolbar)
        loadConfig()
    }

    private fun loadConfig() = with(binding) {
        feishuRemoteEnabled.isChecked = config.feishuRemoteControlEnabled
        feishuRemoteAppId.setText(config.feishuRemoteAppId())
        feishuRemoteAppSecret.setText(config.feishuRemoteAppSecret())
        feishuRemoteCustomPrefix.setText(config.feishuRemoteCustomPrefix())
        feishuRemoteSendSim.setSelection(
            when (config.feishuRemoteSendSimMode) {
                SimSendMode.SIM1 -> 1
                SimSendMode.SIM2 -> 2
                else -> 0
            },
        )
        feishuRemoteStatus.text = config.feishuRemoteConnectionStatus.ifBlank { "尚未连接" }
        feishuRemoteLogs.text = config.feishuRemoteLogs().ifBlank { "暂无日志" }
    }

    private fun saveConfig(): Boolean {
        val appId = binding.feishuRemoteAppId.value.trim()
        val appSecret = binding.feishuRemoteAppSecret.value.trim()
        val customPrefix = binding.feishuRemoteCustomPrefix.value.trim()
        val enabled = binding.feishuRemoteEnabled.isChecked
        if (enabled && (appId.isBlank() || appSecret.isBlank())) {
            toast("请填写 App ID 和 App Secret")
            return false
        }
        config.feishuRemoteControlEnabled = enabled
        config.saveFeishuRemoteControl(appId, appSecret, customPrefix)
        config.feishuRemoteSendSimMode = when (binding.feishuRemoteSendSim.selectedItemPosition) {
            1 -> SimSendMode.SIM1
            2 -> SimSendMode.SIM2
            else -> SimSendMode.DEFAULT
        }
        return true
    }
}