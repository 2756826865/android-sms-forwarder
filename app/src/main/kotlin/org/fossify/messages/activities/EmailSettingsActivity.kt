package org.fossify.messages.activities

import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.databinding.ActivityEmailSettingsBinding
import org.fossify.messages.extensions.applyWhitePageChrome
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
        setupSecuritySelector()
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
        applyWhitePageChrome()
    }

    private fun loadConfig() = with(binding) {
        emailEnabled.isChecked = forwardingConfig.emailEnabled
        emailHost.setText(forwardingConfig.emailHost())
        emailPort.setText(forwardingConfig.emailPort.toString())
        emailSecurity.setSelection(forwardingConfig.emailSecurity, false)
        updateSecurityHint(forwardingConfig.emailSecurity)
        emailUser.setText(forwardingConfig.emailUser())
        emailPassword.setText(forwardingConfig.emailPassword())
        emailRecipients.setText(forwardingConfig.emailRecipients())
        emailTestSender.setText("10086")
        emailTestBody.setText("这是一条邮箱转发测试消息")
    }

    private fun setupSecuritySelector() {
        binding.emailSecurity.adapter = ArrayAdapter.createFromResource(
            this,
            org.fossify.messages.R.array.email_security_options,
            org.fossify.messages.R.layout.item_email_security_spinner,
        ).apply {
            setDropDownViewResource(org.fossify.messages.R.layout.item_email_security_spinner_dropdown)
        }
        binding.emailSecurity.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                updateSecurityHint(position)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun updateSecurityHint(security: Int) {
        binding.emailSecurityHint.setText(
            if (security == MultiForwardConfig.EMAIL_SECURITY_STARTTLS) {
                org.fossify.messages.R.string.forwarding_email_security_starttls_hint
            } else {
                org.fossify.messages.R.string.forwarding_email_security_ssl_hint
            }
        )
    }

    private fun saveConfig(requireConfig: Boolean = false): Boolean {
        val host = binding.emailHost.text?.toString().orEmpty().trim()
        val security = binding.emailSecurity.selectedItemPosition
        val defaultPort = if (security == MultiForwardConfig.EMAIL_SECURITY_STARTTLS) 587 else 465
        val port = binding.emailPort.text?.toString().orEmpty().trim().toIntOrNull() ?: defaultPort
        val user = binding.emailUser.text?.toString().orEmpty().trim()
        val password = binding.emailPassword.text?.toString().orEmpty().trim()
        val recipients = binding.emailRecipients.text?.toString().orEmpty().trim()
        if ((requireConfig || binding.emailEnabled.isChecked) && (host.isBlank() || user.isBlank() || password.isBlank())) {
            toast("请填写 SMTP 服务器、账号和密码")
            return false
        }
        forwardingConfig.emailEnabled = binding.emailEnabled.isChecked
        forwardingConfig.saveEmail(host, port, user, password, recipients, security)
        return true
    }
}
