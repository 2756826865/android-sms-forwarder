package org.fossify.messages.activities

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import androidx.appcompat.app.AlertDialog
import androidx.core.view.WindowInsetsControllerCompat
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityForwardingChannelsBinding
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.PushPlusConfig
import org.fossify.messages.forwarding.PushPlusWorker

class ForwardingChannelsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityForwardingChannelsBinding::inflate)
    private val multiConfig by lazy { MultiForwardConfig(applicationContext) }
    private val pushPlusConfig by lazy { PushPlusConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.forwardingScrollview))
        setupMaterialScrollListener(binding.forwardingScrollview, binding.forwardingAppbar)
        setupTopAppBar(binding.forwardingAppbar, NavigationIcon.Arrow)
        binding.forwardingToolbar.title = ""
        bindActions()
        enforceForwardingDisclaimer()
    }

    override fun onResume() {
        super.onResume()
        window.statusBarColor = Color.rgb(247, 247, 247)
        window.navigationBarColor = Color.rgb(247, 247, 247)
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = true
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightNavigationBars = true
        updateSummaries()
    }

    private fun bindActions() = binding.apply {
        forwardingPushplusHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, PushPlusSettingsActivity::class.java))
        }
        forwardingDingtalkHolder.setOnClickListener { showDingTalkDialog() }
        forwardingFeishuHolder.setOnClickListener { showFeishuDialog() }
        forwardingWecomHolder.setOnClickListener { showWeComDialog() }
        forwardingEmailHolder.setOnClickListener { showEmailDialog() }
        forwardingSimOneHolder.setOnClickListener { showSimLabelDialog(0) }
        forwardingSimTwoHolder.setOnClickListener { showSimLabelDialog(1) }
        forwardingTemplateHolder.setOnClickListener { showTemplateDialog() }
        forwardingDisclaimerHolder.setOnClickListener { showForwardingDisclaimer(requireAcceptance = false) }
        forwardingTest.setOnClickListener {
            val pushPlusEnabled = pushPlusConfig.enabled && pushPlusConfig.getToken().isNotBlank()
            if (!pushPlusEnabled && !multiConfig.anyEnabled()) {
                toast(R.string.forwarding_no_enabled)
                return@setOnClickListener
            }
            if (pushPlusEnabled) {
                PushPlusWorker.enqueueTest(
                    applicationContext,
                    getString(R.string.pushplus_test_sender_default),
                    getString(R.string.pushplus_test_body_default)
                )
            }
            if (multiConfig.anyEnabled()) MultiChannelForwardWorker.enqueueTest(applicationContext)
            toast(R.string.forwarding_test_queued)
        }
    }

    private fun enforceForwardingDisclaimer() {
        if (!multiConfig.hasAcceptedDisclaimer()) {
            showForwardingDisclaimer(requireAcceptance = true)
        }
    }

    private fun showForwardingDisclaimer(requireAcceptance: Boolean) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.forwarding_disclaimer_title)
            .setMessage(R.string.forwarding_disclaimer_body)
            .setPositiveButton(
                if (requireAcceptance) R.string.forwarding_disclaimer_accept else android.R.string.ok
            ) { _, _ ->
                if (requireAcceptance) {
                    multiConfig.acceptCurrentDisclaimer()
                    updateSummaries()
                }
            }
            .apply {
                if (requireAcceptance) {
                    setNegativeButton(R.string.forwarding_disclaimer_decline) { _, _ -> finish() }
                }
            }
            .create()

        dialog.setCancelable(!requireAcceptance)
        dialog.setCanceledOnTouchOutside(!requireAcceptance)
        dialog.setOnCancelListener {
            if (requireAcceptance) finish()
        }
        dialog.setOnShowListener {
            val green = getColor(R.color.miui_fab_green)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(green)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(Color.rgb(80, 80, 80))
        }
        dialog.show()
    }

    private fun showSimLabelDialog(slotIndex: Int) {
        val current = if (slotIndex == 0) multiConfig.simOneLabel else multiConfig.simTwoLabel
        val editor = EditText(this).apply {
            setText(current)
            hint = getString(if (slotIndex == 0) R.string.forwarding_sim_one_hint else R.string.forwarding_sim_two_hint)
            setTextColor(Color.rgb(17, 17, 17))
            setHintTextColor(Color.rgb(120, 120, 120))
            isSingleLine = true
            setPadding(48, 16, 48, 8)
        }
        AlertDialog.Builder(this)
            .setTitle(if (slotIndex == 0) R.string.forwarding_sim_one_label else R.string.forwarding_sim_two_label)
            .setView(editor)
            .setPositiveButton(R.string.forwarding_save) { _, _ ->
                if (slotIndex == 0) multiConfig.simOneLabel = editor.text.toString()
                else multiConfig.simTwoLabel = editor.text.toString()
                updateSummaries()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTemplateDialog() {
        val labels = arrayOf(
            getString(R.string.forwarding_template_compact),
            getString(R.string.forwarding_template_standard),
            getString(R.string.forwarding_template_detailed),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.forwarding_template)
            .setSingleChoiceItems(labels, multiConfig.templateMode) { dialog, which ->
                multiConfig.templateMode = which
                updateSummaries()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDingTalkDialog() {
        showConfigDialog(
            title = getString(R.string.forwarding_dingtalk),
            enabled = multiConfig.dingTalkEnabled,
            fields = listOf(
                Field(getString(R.string.forwarding_webhook), multiConfig.dingTalkWebhook()),
                Field(getString(R.string.forwarding_sign_secret), multiConfig.dingTalkSecret(), secret = true)
            )
        ) { enabled, values ->
            multiConfig.saveDingTalk(values[0], values[1])
            multiConfig.dingTalkEnabled = enabled && values[0].startsWith("https://")
            !enabled || multiConfig.dingTalkEnabled
        }
    }

    private fun showFeishuDialog() {
        showConfigDialog(
            title = getString(R.string.forwarding_feishu),
            enabled = multiConfig.feishuEnabled,
            fields = listOf(
                Field(getString(R.string.forwarding_webhook), multiConfig.feishuWebhook()),
                Field(getString(R.string.forwarding_sign_secret), multiConfig.feishuSecret(), secret = true)
            )
        ) { enabled, values ->
            multiConfig.saveFeishu(values[0], values[1])
            multiConfig.feishuEnabled = enabled && values[0].startsWith("https://")
            !enabled || multiConfig.feishuEnabled
        }
    }

    private fun showWeComDialog() {
        showConfigDialog(
            title = getString(R.string.forwarding_wecom),
            enabled = multiConfig.weComEnabled,
            fields = listOf(
                Field(getString(R.string.forwarding_corp_id), multiConfig.weComCorpId()),
                Field(getString(R.string.forwarding_agent_id), multiConfig.weComAgentId(), numeric = true),
                Field(getString(R.string.forwarding_app_secret), multiConfig.weComSecret(), secret = true),
                Field(getString(R.string.forwarding_to_user), multiConfig.weComToUser())
            )
        ) { enabled, values ->
            multiConfig.saveWeCom(values[0], values[1], values[2], values[3])
            val complete = values[0].isNotBlank() && values[1].toLongOrNull() != null &&
                values[2].isNotBlank() && values[3].isNotBlank()
            multiConfig.weComEnabled = enabled && complete
            !enabled || complete
        }
    }

    private fun showEmailDialog() {
        showConfigDialog(
            title = getString(R.string.forwarding_email),
            enabled = multiConfig.emailEnabled,
            fields = listOf(
                Field(getString(R.string.forwarding_smtp_host), multiConfig.emailHost()),
                Field(getString(R.string.forwarding_smtp_port), multiConfig.emailPort.toString(), numeric = true),
                Field(getString(R.string.forwarding_email_user), multiConfig.emailUser()),
                Field(getString(R.string.forwarding_email_password), multiConfig.emailPassword(), secret = true),
                Field(getString(R.string.forwarding_email_to), multiConfig.emailRecipients())
            )
        ) { enabled, values ->
            val port = values[1].toIntOrNull() ?: 465
            multiConfig.saveEmail(values[0], port, values[2], values[3], values[4])
            val complete = values[0].isNotBlank() && values[2].isNotBlank() &&
                values[3].isNotBlank() && values[4].isNotBlank()
            multiConfig.emailEnabled = enabled && complete
            !enabled || complete
        }
    }

    private fun showConfigDialog(
        title: String,
        enabled: Boolean,
        fields: List<Field>,
        onSave: (Boolean, List<String>) -> Boolean
    ) {
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val enabledSwitch = Switch(this).apply {
            text = getString(R.string.forwarding_enable_channel)
            isChecked = enabled
            setTextColor(Color.rgb(17, 17, 17))
        }
        container.addView(
            enabledSwitch,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        val editors = fields.map { field ->
            EditText(this).apply {
                hint = field.hint
                setText(field.value)
                setTextColor(Color.rgb(17, 17, 17))
                setHintTextColor(Color.rgb(120, 120, 120))
                isSingleLine = true
                inputType = when {
                    field.secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                    field.numeric -> InputType.TYPE_CLASS_NUMBER
                    else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                }
                container.addView(
                    this,
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { topMargin = padding / 2 }
                )
            }
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(container)
            .setPositiveButton(R.string.forwarding_save) { _, _ ->
                val valid = onSave(enabledSwitch.isChecked, editors.map { it.text.toString().trim() })
                if (valid) toast(R.string.forwarding_saved) else toast(R.string.forwarding_required_fields)
                updateSummaries()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateSummaries() = binding.apply {
        forwardingPushplusSummary.text = statusText(pushPlusConfig.getToken().isNotBlank(), pushPlusConfig.enabled)
        forwardingDingtalkSummary.text = statusText(multiConfig.dingTalkWebhook().isNotBlank(), multiConfig.dingTalkEnabled)
        forwardingFeishuSummary.text = statusText(multiConfig.feishuWebhook().isNotBlank(), multiConfig.feishuEnabled)
        forwardingWecomSummary.text = statusText(multiConfig.weComCorpId().isNotBlank(), multiConfig.weComEnabled)
        forwardingEmailSummary.text = statusText(multiConfig.emailHost().isNotBlank(), multiConfig.emailEnabled)
        forwardingSimOneSummary.text = multiConfig.simOneLabel.ifBlank { getString(R.string.forwarding_sim_system_default) }
        forwardingSimTwoSummary.text = multiConfig.simTwoLabel.ifBlank { getString(R.string.forwarding_sim_system_default) }
        forwardingTemplateSummary.text = getString(
            when (multiConfig.templateMode) {
                MultiForwardConfig.TEMPLATE_STANDARD -> R.string.forwarding_template_standard
                MultiForwardConfig.TEMPLATE_DETAILED -> R.string.forwarding_template_detailed
                else -> R.string.forwarding_template_compact
            }
        )
        val statuses = listOf(pushPlusConfig.lastStatus, multiConfig.lastStatus).filter(String::isNotBlank)
        forwardingLastStatus.text = getString(
            R.string.forwarding_last_status,
            statuses.joinToString("\n").ifBlank { getString(R.string.forwarding_status_never) }
        )
    }

    private fun statusText(configured: Boolean, enabled: Boolean) = getString(
        when {
            !configured -> R.string.forwarding_not_configured
            enabled -> R.string.forwarding_configured_enabled
            else -> R.string.forwarding_configured_disabled
        }
    )

    private data class Field(
        val hint: String,
        val value: String,
        val secret: Boolean = false,
        val numeric: Boolean = false
    )
}
