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
                toast("请先开启企业微信远程指令")
                return@setOnClickListener
            }
            if (!saveConfig()) return@setOnClickListener
            toast("正在校验企业微信凭证…")
            val corpId = config.weComRemoteCorpId()
            val secret = config.weComRemoteSecret()
            org.fossify.commons.helpers.ensureBackgroundThread {
                try {
                    val client = okhttp3.OkHttpClient.Builder()
                        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                        .build()
                    val request = okhttp3.Request.Builder()
                        .url("https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=$corpId&corpsecret=$secret")
                        .build()
                    client.newCall(request).execute().use { response ->
                        val body = response.body?.string().orEmpty()
                        val json = org.json.JSONObject(body)
                        val errcode = json.optInt("errcode", -1)
                        if (errcode == 0) {
                            config.appendWeComRemoteLog("凭证校验成功 · AccessToken 获取正常")
                            runOnUiThread {
                                toast("企业微信凭证验证成功！")
                                WeComRemoteControlService.ensureStarted(applicationContext)
                                loadConfig()
                            }
                        } else {
                            val errmsg = json.optString("errmsg", body)
                            config.appendWeComRemoteLog("校验失败：$errmsg (errcode=$errcode)")
                            runOnUiThread {
                                toast("验证失败：$errmsg")
                                loadConfig()
                            }
                        }
                    }
                } catch (e: Throwable) {
                    val err = e.message ?: e.javaClass.simpleName
                    config.appendWeComRemoteLog("连接异常：$err")
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
        applyMiuiTopAppBarChrome(binding.wecomRemoteAppbar, binding.wecomRemoteToolbar)
        loadConfig()
    }

    private fun loadConfig() = with(binding) {
        wecomRemoteEnabled.isChecked = config.weComRemoteControlEnabled
        wecomRemoteCorpId.setText(config.weComRemoteCorpId())
        wecomRemoteAgentId.setText(config.weComRemoteAgentId())
        wecomRemoteSecret.setText(config.weComRemoteSecret())
        wecomRemoteAuthUsers.setText(config.weComRemoteAuthorizedUsers())
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
    }

    private fun saveConfig(): Boolean {
        val corpId = binding.wecomRemoteCorpId.value.trim()
        val agentId = binding.wecomRemoteAgentId.value.trim()
        val secret = binding.wecomRemoteSecret.value.trim()
        val authUsers = binding.wecomRemoteAuthUsers.value.trim()
        val customPrefix = binding.wecomRemoteCustomPrefix.value.trim()
        val enabled = binding.wecomRemoteEnabled.isChecked
        if (enabled && (corpId.isBlank() || agentId.isBlank() || secret.isBlank())) {
            toast("请填写 Corp ID / Agent ID / Secret")
            return false
        }
        config.weComRemoteControlEnabled = enabled
        config.saveWeComRemoteControl(corpId, agentId, secret, authUsers, customPrefix)
        config.weComRemoteSendSimMode = when (binding.wecomRemoteSendSim.selectedItemPosition) {
            1 -> SimSendMode.SIM1
            2 -> SimSendMode.SIM2
            else -> SimSendMode.DEFAULT
        }
        return true
    }
}