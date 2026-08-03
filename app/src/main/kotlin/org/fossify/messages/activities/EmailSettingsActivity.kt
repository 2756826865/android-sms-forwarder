package org.fossify.messages.activities

import android.graphics.Color
import android.os.Bundle
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.databinding.ActivityEmailSettingsBinding
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.MultiChannelForwardWorker

class EmailSettingsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityEmailSettingsBinding::inflate)
    private val forwardingConfig by lazy { MultiForwardConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.emailScrollview))
        setupMaterialScrollListener(binding.emailScrollview, binding.emailAppbar)
        setupTopAppBar(binding.emailAppbar, NavigationIcon.Arrow)
        loadConfig()

        binding.emailSave.setOnClickListener {
            if (saveConfig()) toast("配置已保存")
        }

        binding.emailTest.setOnClickListener {
            if (saveConfig(requireConfig = true)) {
                val sender = binding.emailTestSender.value.trim()
                val body = binding.emailTestBody.value.trim()
                if (sender.isBlank() || body.isBlank()) {
                    toast("请填写测试发送方和短信正文")
                } else {
                    MultiChannelForwardWorker.enqueueTest(this, "email", sender, body)
                    toast("测试消息已加入发送队列")
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
    }

    private fun loadConfig() = with(binding) {
        emailEnabled.isChecked = forwardingConfig.emailEnabled
        emailHost.setText(forwardingConfig.emailHost())
        emailPort.setText(forwardingConfig.emailPort.toString())
        emailUser.setText(forwardingConfig.emailUser())
        emailPassword.setText(forwardingConfig.emailPassword())
        emailRecipients.setText(forwardingConfig.emailRecipients())
        emailTestSender.setText("10086")
        emailTestBody.setText("这是一条邮箱转发测试消息")
    }

    private fun saveConfig(requireConfig: Boolean = false): Boolean {
        val host = binding.emailHost.text?.toString().orEmpty().trim()
        val port = binding.emailPort.text?.toString().orEmpty().trim().toIntOrNull() ?: 465
        val user = binding.emailUser.text?.toString().orEmpty().trim()
        val password = binding.emailPassword.text?.toString().orEmpty().trim()
        val recipients = binding.emailRecipients.text?.toString().orEmpty().trim()
        if ((requireConfig || binding.emailEnabled.isChecked) && (host.isBlank() || user.isBlank() || password.isBlank())) {
            toast("请填写 SMTP 服务器、账号和密码")
            return false
        }
        forwardingConfig.emailEnabled = binding.emailEnabled.isChecked
        forwardingConfig.saveEmail(host, port, user, password, recipients)
        return true
    }
}
