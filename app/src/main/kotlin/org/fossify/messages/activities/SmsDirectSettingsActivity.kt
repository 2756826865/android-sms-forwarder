package org.fossify.messages.activities

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.databinding.ActivitySmsDirectSettingsBinding
import org.fossify.messages.extensions.applyWhitePageChrome
import org.fossify.messages.messaging.sendMessageCompat
import org.fossify.messages.forwarding.MultiForwardConfig

class SmsDirectSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivitySmsDirectSettingsBinding::inflate)
    private val forwardingConfig by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.smsDirectScrollview))
        setupMaterialScrollListener(binding.smsDirectScrollview, binding.smsDirectAppbar)
        setupTopAppBar(binding.smsDirectAppbar, NavigationIcon.Arrow)
        loadConfig()

        binding.smsDirectSave.setOnClickListener {
            if (saveConfig()) toast("配置已保存")
        }

        binding.smsDirectTest.setOnClickListener {
            if (saveConfig(requirePhone = true)) {
                val sender = binding.smsDirectTestSender.value.trim()
                val body = binding.smsDirectTestBody.value.trim()
                if (sender.isBlank() || body.isBlank()) {
                    toast("请填写测试发送方和短信正文")
                } else {
                    val phone = binding.smsDirectPhone.text?.toString().orEmpty().trim()
                    val onlyOnNoNetwork = binding.smsDirectOnlyOnNoNetwork.isChecked
                    val networkAvailable = isNetworkAvailable()
                    
                    if (onlyOnNoNetwork && networkAvailable) {
                        // 仅断网时发送模式，且当前有网络
                        toast("当前有网络，短信直发不会发送（已开启仅断网时发送）")
                    } else {
                        // 直接发送或断网时发送
                        sendSmsDirect(phone, "[$sender] $body")
                        if (networkAvailable) {
                            toast("已通过短信发送，注意运营商可能收费")
                        } else {
                            toast("网络不可用，已通过短信发送")
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyWhitePageChrome()
    }

    private fun loadConfig() = with(binding) {
        smsDirectEnabled.isChecked = forwardingConfig.smsDirectEnabled
        smsDirectPhone.setText(forwardingConfig.smsDirectPhone())
        smsDirectOnlyOnNoNetwork.isChecked = forwardingConfig.smsDirectOnlyOnNoNetwork
        smsDirectTestSender.setText("10086")
        smsDirectTestBody.setText("这是一条短信直发测试消息")
    }

    private fun saveConfig(requirePhone: Boolean = false): Boolean {
        val phone = binding.smsDirectPhone.text?.toString().orEmpty().trim()
        if ((requirePhone || binding.smsDirectEnabled.isChecked) && phone.isBlank()) {
            toast("请填写目标手机号")
            return false
        }
        forwardingConfig.smsDirectEnabled = binding.smsDirectEnabled.isChecked
        forwardingConfig.saveSmsDirect(phone)
        forwardingConfig.smsDirectOnlyOnNoNetwork = binding.smsDirectOnlyOnNoNetwork.isChecked
        return true
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun sendSmsDirect(phone: String, message: String) {
        try {
            val normalized = phone.trim()
            if (normalized.isEmpty()) {
                toast("目标手机号不能为空")
                return
            }
            sendMessageCompat(message, listOf(normalized), null, emptyList())
        } catch (e: Exception) {
            toast("短信发送失败: ${e.message}")
        }
    }
}
