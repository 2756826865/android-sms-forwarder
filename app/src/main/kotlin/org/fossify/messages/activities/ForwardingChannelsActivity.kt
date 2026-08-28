package org.fossify.messages.activities

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityForwardingChannelsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.applySmsDialogColors
import org.fossify.messages.extensions.showSmsStyled
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.PushPlusConfig
import org.fossify.messages.forwarding.PushPlusWorker
import org.fossify.messages.forwarding.ForwardingRulesConfig
import org.fossify.messages.remote.RemoteSmsCommandConfig

class ForwardingChannelsActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityForwardingChannelsBinding::inflate)
    private val multiConfig by lazy { MultiForwardConfig(applicationContext) }
    private val pushPlusConfig by lazy { PushPlusConfig(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.forwardingAppbar),
            padBottomImeAndSystem = listOf(binding.forwardingScrollview),
        )
        setupMaterialScrollListener(binding.forwardingScrollview, binding.forwardingAppbar)
        setupTopAppBar(binding.forwardingAppbar, NavigationIcon.Arrow)
        binding.forwardingToolbar.title = ""
        applyMiuiTopAppBarChrome(binding.forwardingAppbar, binding.forwardingToolbar)
        bindActions()
        enforceForwardingDisclaimer()
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.forwardingAppbar, binding.forwardingToolbar)
        updateSummaries()
    }

    private fun bindActions() = binding.apply {
        forwardingPushplusHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, PushPlusSettingsActivity::class.java))
        }
        forwardingDingtalkHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, DingTalkSettingsActivity::class.java))
        }
        forwardingFeishuHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, FeishuSettingsActivity::class.java))
        }
        forwardingWecomHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, WeComSettingsActivity::class.java))
        }
        forwardingWecomBotHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, WeComBotSettingsActivity::class.java))
        }
        forwardingEmailHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, EmailSettingsActivity::class.java))
        }
        forwardingSmsDirectHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, SmsDirectSettingsActivity::class.java))
        }
        forwardingBarkHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, BarkSettingsActivity::class.java))
        }
        forwardingGotifyHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, GotifySettingsActivity::class.java))
        }
        forwardingWechatTestHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, WeChatTestSettingsActivity::class.java))
        }
        forwardingTelegramHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, TelegramSettingsActivity::class.java))
        }
        forwardingCustomWebhookHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, CustomWebhookSettingsActivity::class.java))
        }
        forwardingDiscordHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, DiscordSettingsActivity::class.java))
        }
        forwardingRulesHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, ForwardingRulesSettingsActivity::class.java))
        }
        forwardingRemoteForwardingHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, RemoteForwardingActivity::class.java))
        }
        forwardingHistoryHolder.setOnClickListener {
            startActivity(Intent(this@ForwardingChannelsActivity, ForwardingHistoryActivity::class.java))
        }
        forwardingSimOneHolder.setOnClickListener { showSimLabelDialog(0) }
        forwardingSimTwoHolder.setOnClickListener { showSimLabelDialog(1) }
        forwardingTemplateHolder.setOnClickListener {
            if (multiConfig.templateMode == MultiForwardConfig.TEMPLATE_CUSTOM) {
                showCustomTemplateDialog()
            } else {
                showTemplateDialog()
            }
        }
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
        dialog.applySmsDialogColors()
    }

    private fun showSimLabelDialog(slotIndex: Int) {
        val labelEditor = EditText(this).apply {
            setText(if (slotIndex == 0) multiConfig.simOneLabel else multiConfig.simTwoLabel)
            hint = getString(if (slotIndex == 0) R.string.forwarding_sim_one_hint else R.string.forwarding_sim_two_hint)
            setTextColor(Color.rgb(17, 17, 17))
            setHintTextColor(Color.rgb(120, 120, 120))
            isSingleLine = true
        }
        val numberEditor = EditText(this).apply {
            setText(if (slotIndex == 0) multiConfig.simOneNumber else multiConfig.simTwoNumber)
            hint = getString(R.string.forwarding_sim_number_hint)
            inputType = InputType.TYPE_CLASS_PHONE
            setTextColor(Color.rgb(17, 17, 17))
            setHintTextColor(Color.rgb(120, 120, 120))
            isSingleLine = true
        }
        val padding = (20 * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
            addView(labelEditor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(numberEditor, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(this)
            .setTitle(if (slotIndex == 0) R.string.forwarding_sim_one_label else R.string.forwarding_sim_two_label)
            .setView(container)
            .setPositiveButton(R.string.forwarding_save) { _, _ ->
                if (slotIndex == 0) {
                    multiConfig.simOneLabel = labelEditor.text.toString()
                    multiConfig.simOneNumber = numberEditor.text.toString()
                } else {
                    multiConfig.simTwoLabel = labelEditor.text.toString()
                    multiConfig.simTwoNumber = numberEditor.text.toString()
                }
                updateSummaries()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }

    private fun showTemplateDialog() {
        val labels = arrayOf(
            getString(R.string.forwarding_template_compact),
            getString(R.string.forwarding_template_standard),
            getString(R.string.forwarding_template_detailed),
            getString(R.string.forwarding_template_emoji),
            getString(R.string.forwarding_template_custom),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.forwarding_template)
            .setSingleChoiceItems(labels, multiConfig.templateMode) { dialog, which ->
                if (which == MultiForwardConfig.TEMPLATE_CUSTOM) {
                    dialog.dismiss()
                    showCustomTemplateDialog()
                } else {
                    multiConfig.templateMode = which
                    updateSummaries()
                    dialog.dismiss()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }

    private fun showCustomTemplateDialog() {
        val density = resources.displayMetrics.density
        val paddingLarge = (24 * density).toInt()
        val paddingSmall = (12 * density).toInt()
        
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(paddingLarge, paddingSmall, paddingLarge, paddingSmall)
        }
        scroll.addView(container)

        val tipText = TextView(this).apply {
            text = getString(R.string.forwarding_custom_template_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, (8 * density).toInt())
        }
        container.addView(tipText)

        val templateInput = EditText(this).apply {
            hint = getString(R.string.forwarding_template_custom_hint)
            setText(multiConfig.customTemplate)
            minLines = 6
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            gravity = Gravity.TOP
            setBackgroundResource(R.drawable.message_input_background)
            setPadding(paddingSmall, paddingSmall, paddingSmall, paddingSmall)
        }
        container.addView(templateInput)

        val grid = GridLayout(this).apply {
            columnCount = 4
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setPadding(0, paddingSmall, 0, 0)
        }
        
        val tags = listOf(
            Pair("{{FROM}}", R.string.forwarding_template_tag_from),
            Pair("{{SMS}}", R.string.forwarding_template_tag_sms),
            Pair("{{RECEIVE_TIME}}", R.string.forwarding_template_tag_receive_time),
            Pair("{{CONTACT_NAME}}", R.string.forwarding_template_tag_contact_name),
            Pair("{{RECEIVER_NUMBER}}", R.string.forwarding_template_tag_receiver_number),
            Pair("{{SIM_SLOT}}", R.string.forwarding_template_tag_sim_slot),
            Pair("{{DEVICE_NAME}}", R.string.forwarding_template_tag_device_name),
            Pair("{{BATTERY_INFO}}", R.string.forwarding_template_tag_battery_info),
            Pair("{{NET_TYPE}}", R.string.forwarding_template_tag_net_type),
            Pair("{{IP_LIST}}", R.string.forwarding_template_tag_ip_list),
            Pair("{{APP_VERSION}}", R.string.forwarding_template_tag_app_version),
            Pair("{{CURRENT_TIME}}", R.string.forwarding_template_tag_current_time),
        )

        val blueColor = Color.parseColor("#2196F3")
        tags.forEach { (tag, stringRes) ->
            val btn = Button(this).apply {
                text = getString(stringRes)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.WHITE)
                isAllCaps = false
                setBackgroundResource(R.drawable.send_button_background)
                backgroundTintList = android.content.res.ColorStateList.valueOf(blueColor)
                setOnClickListener {
                    val start = templateInput.selectionStart
                    val end = templateInput.selectionEnd
                    templateInput.text.replace(Math.min(start, end), Math.max(start, end), tag)
                }
            }
            val params = GridLayout.LayoutParams().apply {
                width = 0
                height = ViewGroup.LayoutParams.WRAP_CONTENT
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins((2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt(), (2 * density).toInt())
            }
            grid.addView(btn, params)
        }
        container.addView(grid)

        AlertDialog.Builder(this)
            .setTitle(R.string.forwarding_template_custom)
            .setView(scroll)
            .setPositiveButton(R.string.forwarding_save) { _, _ ->
                val template = templateInput.text.toString().trim()
                if (template.isBlank()) {
                    toast(R.string.forwarding_template_custom_empty)
                    return@setPositiveButton
                }
                multiConfig.customTemplate = template
                multiConfig.templateMode = MultiForwardConfig.TEMPLATE_CUSTOM
                updateSummaries()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
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

    private fun showWeComBotDialog() {
        showConfigDialog(
            title = getString(R.string.forwarding_wecom_bot),
            enabled = multiConfig.weComBotEnabled,
            fields = listOf(
                Field(getString(R.string.forwarding_webhook), multiConfig.weComBotWebhook())
            )
        ) { enabled, values ->
            multiConfig.saveWeComBot(values[0])
            multiConfig.weComBotEnabled = enabled && values[0].isNotBlank() && values[0].startsWith("https://")
            !enabled || multiConfig.weComBotEnabled
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
                    field.secret -> InputType.TYPE_CLASS_TEXT
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
            .create()
            .showSmsStyled()
    }

    private fun updateSummaries() = binding.apply {
        forwardingPushplusSummary.text = statusText(pushPlusConfig.getToken().isNotBlank(), pushPlusConfig.enabled)
        forwardingDingtalkSummary.text = statusText(multiConfig.dingTalkWebhook().isNotBlank(), multiConfig.dingTalkEnabled)
        forwardingFeishuSummary.text = statusText(multiConfig.feishuWebhook().isNotBlank(), multiConfig.feishuEnabled)
        forwardingWecomSummary.text = statusText(multiConfig.weComCorpId().isNotBlank(), multiConfig.weComEnabled)
        forwardingWecomBotSummary.text = statusText(multiConfig.weComBotWebhook().isNotBlank(), multiConfig.weComBotEnabled)
        forwardingEmailSummary.text = statusText(multiConfig.emailHost().isNotBlank(), multiConfig.emailEnabled)
        forwardingSmsDirectSummary.text = statusText(multiConfig.smsDirectPhone().isNotBlank(), multiConfig.smsDirectEnabled)
        forwardingBarkSummary.text = statusText(
            multiConfig.barkServerUrl().isNotBlank() && multiConfig.barkDeviceKey().isNotBlank(),
            multiConfig.barkEnabled,
        )
        forwardingGotifySummary.text = statusText(
            multiConfig.gotifyServerUrl().isNotBlank() && multiConfig.gotifyToken().isNotBlank(),
            multiConfig.gotifyEnabled,
        )
        forwardingWechatTestSummary.text = statusText(
            multiConfig.weChatTestAppId().isNotBlank() && multiConfig.weChatTestAppSecret().isNotBlank() &&
                multiConfig.weChatTestTemplateId().isNotBlank() && multiConfig.weChatTestOpenId().isNotBlank(),
            multiConfig.weChatTestEnabled,
        )
        forwardingTelegramSummary.text = statusText(
            multiConfig.telegramBotToken().isNotBlank() && multiConfig.telegramChatId().isNotBlank(),
            multiConfig.telegramEnabled,
        )
        forwardingCustomWebhookSummary.text = statusText(
            multiConfig.customWebhookUrl().isNotBlank(),
            multiConfig.customWebhookEnabled,
        )
        forwardingDiscordSummary.text = statusText(
            multiConfig.discordWebhookUrl().isNotBlank(),
            multiConfig.discordEnabled,
        )
        forwardingRulesSummary.text = ForwardingRulesConfig(applicationContext).summary()
        val historyRecords = org.fossify.messages.forwarding.ForwardingHistoryStore(applicationContext).records()
        forwardingHistorySummary.text = getString(
            R.string.forwarding_history_summary,
            historyRecords.map { record -> record.workId.ifBlank { record.recordId } }.distinct().size,
            historyRecords.count { it.status == org.fossify.messages.forwarding.ForwardingHistoryStore.STATUS_FAILED },
        )
        forwardingRemoteForwardingSummary.text = remoteForwardingHubSummary()
        forwardingSimOneSummary.text = simSummary(multiConfig.simOneLabel, multiConfig.simOneNumber)
        forwardingSimTwoSummary.text = simSummary(multiConfig.simTwoLabel, multiConfig.simTwoNumber)
        forwardingTemplateSummary.text = when (multiConfig.templateMode) {
            MultiForwardConfig.TEMPLATE_STANDARD -> getString(R.string.forwarding_template_standard)
            MultiForwardConfig.TEMPLATE_DETAILED -> getString(R.string.forwarding_template_detailed)
            MultiForwardConfig.TEMPLATE_EMOJI -> getString(R.string.forwarding_template_emoji)
            MultiForwardConfig.TEMPLATE_CUSTOM -> {
                val custom = multiConfig.customTemplate
                if (custom.isNotBlank()) {
                    getString(R.string.forwarding_template_custom_summary, custom.take(60) + if (custom.length > 60) "…" else "")
                } else {
                    getString(R.string.forwarding_template_custom)
                }
            }
            else -> getString(R.string.forwarding_template_compact)
        }
        val statuses = listOf(pushPlusConfig.lastStatus, multiConfig.lastStatus).filter(String::isNotBlank)
        forwardingLastStatus.text = getString(
            R.string.forwarding_last_status,
            statuses.joinToString("\n").ifBlank { getString(R.string.forwarding_status_never) }
        )
    }

    private fun remoteForwardingHubSummary(): String {
        val smsEnabled = RemoteSmsCommandConfig(applicationContext).enabled
        val dingTalkEnabled = multiConfig.dingTalkRemoteControlEnabled &&
            multiConfig.dingTalkRemoteClientId().isNotBlank()
        return when {
            smsEnabled && dingTalkEnabled -> getString(R.string.remote_forwarding_hub_both_enabled)
            smsEnabled -> getString(R.string.remote_forwarding_hub_sms_enabled)
            dingTalkEnabled -> getString(R.string.remote_forwarding_hub_dingtalk_enabled)
            else -> getString(R.string.remote_forwarding_hub_default)
        }
    }

    private fun simSummary(label: String, number: String): String {
        if (label.isBlank() && number.isBlank()) return getString(R.string.forwarding_sim_system_default)
        return listOf(label, number).filter(String::isNotBlank).joinToString(" · ")
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
