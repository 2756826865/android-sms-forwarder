package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.databinding.ActivityBarkSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.ForwardingUrlPolicy
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig

class BarkSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityBarkSettingsBinding::inflate)
    private val forwardingConfig by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.barkAppbar),
            padBottomImeAndSystem = listOf(binding.barkScrollview),
        )
        setupMaterialScrollListener(binding.barkScrollview, binding.barkAppbar)
        setupTopAppBar(binding.barkAppbar, NavigationIcon.Arrow)
        applyMiuiTopAppBarChrome(binding.barkAppbar, binding.barkToolbar)
        loadConfig()

        binding.barkSave.setOnClickListener { if (saveConfig()) toast("配置已保存") }
        binding.barkTest.setOnClickListener {
            if (saveConfig(requireConfig = true)) {
                val sender = binding.barkTestSender.value.trim()
                val body = binding.barkTestBody.value.trim()
                if (sender.isBlank() || body.isBlank()) {
                    toast("请填写测试发送方和短信正文")
                } else {
                    MultiChannelForwardWorker.enqueueTest(this, ForwardingChannels.BARK, sender, body)
                    toast("测试消息已加入发送队列")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.barkAppbar, binding.barkToolbar)
    }

    private val levelOptions = listOf(
        "active" to "默认提醒 (active)",
        "timeSensitive" to "时效性通知 - 突破专注模式 (timeSensitive)",
        "critical" to "重要警告 - 忽略静音强制响铃 (critical)",
        "passive" to "静默无声 (passive)"
    )

    private fun loadConfig() = with(binding) {
        barkEnabled.isChecked = forwardingConfig.barkEnabled
        barkServerUrl.setText(forwardingConfig.barkServerUrl().ifBlank { "https://api.day.app" })
        barkDeviceKey.setText(forwardingConfig.barkDeviceKey())
        barkAllowHttp.isChecked = forwardingConfig.barkAllowHttp
        val adapter = android.widget.ArrayAdapter(
            this@BarkSettingsActivity,
            android.R.layout.simple_spinner_item,
            levelOptions.map { it.second }
        ).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        barkLevelSpinner.adapter = adapter
        val currentLevelIndex = levelOptions.indexOfFirst { it.first == forwardingConfig.barkLevel }.coerceAtLeast(0)
        barkLevelSpinner.setSelection(currentLevelIndex)
        barkGroup.setText(forwardingConfig.barkGroup)
        barkAutoCopyCode.isChecked = forwardingConfig.barkAutoCopyCode
        barkTestSender.setText("10086")
        barkTestBody.setText("这是一条 Bark 转发测试消息")
    }

    private fun saveConfig(requireConfig: Boolean = false): Boolean {
        val serverUrl = binding.barkServerUrl.value.trim().trimEnd('/')
        val deviceKey = binding.barkDeviceKey.value.trim()
        val needsConfig = requireConfig || binding.barkEnabled.isChecked
        if (needsConfig && (serverUrl.isBlank() || deviceKey.isBlank())) {
            toast("请填写 Bark 服务地址和 Device Key")
            return false
        }
        if (needsConfig && !ForwardingUrlPolicy.isAllowed(serverUrl, binding.barkAllowHttp.isChecked)) {
            toast("默认必须使用 HTTPS；HTTP 仅允许局域网地址")
            return false
        }
        val selectedLevel = levelOptions.getOrNull(binding.barkLevelSpinner.selectedItemPosition)?.first ?: "active"
        forwardingConfig.barkEnabled = binding.barkEnabled.isChecked
        forwardingConfig.barkAllowHttp = binding.barkAllowHttp.isChecked
        forwardingConfig.saveBark(
            serverUrl = serverUrl,
            deviceKey = deviceKey,
            level = selectedLevel,
            group = binding.barkGroup.value.trim().take(32),
            autoCopyCode = binding.barkAutoCopyCode.isChecked
        )
        return true
    }
}
