package org.fossify.messages.activities

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.databinding.ActivitySmsDirectSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.messaging.sendMessageCompat
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.models.SmsSendTriggerType

class SmsDirectSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivitySmsDirectSettingsBinding::inflate)
    private val forwardingConfig by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.smsDirectAppbar),
            padBottomImeAndSystem = listOf(binding.smsDirectScrollview),
        )
        setupMaterialScrollListener(binding.smsDirectScrollview, binding.smsDirectAppbar)
        setupTopAppBar(binding.smsDirectAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.smsDirectAppbar, binding.smsDirectToolbar)
        binding.smsDirectSimMode.adapter = android.widget.ArrayAdapter(this,
            android.R.layout.simple_spinner_dropdown_item,
            listOf("跟随接收卡", "固定 SIM1", "固定 SIM2"))
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
                        if (sendSmsDirect(phone, "[$sender] $body")) {
                            if (networkAvailable) {
                                toast("已提交短信发送，注意运营商可能收费")
                            } else {
                                toast("网络不可用，已提交短信发送")
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.smsDirectAppbar, binding.smsDirectToolbar)
    }

    private fun loadConfig() = with(binding) {
        smsDirectSimMode.setSelection(forwardingConfig.smsDirectSimMode.coerceIn(0, 2))
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
        forwardingConfig.smsDirectSimMode = binding.smsDirectSimMode.selectedItemPosition
        forwardingConfig.smsDirectEnabled = binding.smsDirectEnabled.isChecked
        forwardingConfig.saveSmsDirect(phone)
        forwardingConfig.smsDirectOnlyOnNoNetwork = binding.smsDirectOnlyOnNoNetwork.isChecked
        return true
    }

    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun sendSmsDirect(phone: String, message: String): Boolean {
        return try {
            val normalized = phone.trim()
            if (normalized.isEmpty()) {
                toast("目标手机号不能为空")
                return false
            }
            val mode = forwardingConfig.smsDirectSimMode
            require(mode in 1..2) { "测试没有接收卡信息，请先选择固定 SIM1 或 SIM2" }
            val resolved = org.fossify.messages.messaging.SubscriptionResolver.resolve(this,
                org.fossify.messages.messaging.SimResolutionRequest(explicitSlotIndex = mode - 1, allowFallback = false))
            require(resolved.resolvedSubscriptionId >= 0) { "指定卡不可用或缺少电话权限，已停止发送" }
            sendMessageCompat(
                message, listOf(normalized), resolved.resolvedSubscriptionId, emptyList(),
                propagateErrors = true,
                triggerType = SmsSendTriggerType.SMS_DIRECT_TEST
            )
            true
        } catch (e: Exception) {
            toast("短信发送失败: ${e.message}")
            false
        }
    }
}
