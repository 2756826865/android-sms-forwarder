package org.fossify.messages.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.fossify.commons.extensions.toast
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.autoreply.AutoReplyConfig
import org.fossify.messages.autoreply.AutoReplyRule
import org.fossify.messages.databinding.ActivityAutoReplySettingsBinding
import org.fossify.messages.extensions.applySmsDialogColors

class AutoReplySettingsActivity : SimpleActivity() {

    private lateinit var binding: ActivityAutoReplySettingsBinding
    private lateinit var config: AutoReplyConfig

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAutoReplySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = AutoReplyConfig(this)

        setupTopAppBar(binding.autoReplyAppbar, NavigationIcon.Arrow)
        binding.autoReplyToolbar.setNavigationOnClickListener { finish() }

        initViews()
    }

    private fun initViews() {
        binding.autoReplyMasterSwitch.isChecked = config.enabled
        binding.autoReplyMasterSwitch.setOnCheckedChangeListener { _, isChecked ->
            config.enabled = isChecked
            updateSummary()
        }

        val dailyLimitOptions = listOf(5, 10, 20, 50, 100)
        val limitAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dailyLimitOptions.map { "$it 条/天" })
        binding.autoReplyDailyLimitSpinner.adapter = limitAdapter
        val limitIndex = dailyLimitOptions.indexOf(config.dailyLimit).coerceAtLeast(0)
        binding.autoReplyDailyLimitSpinner.setSelection(limitIndex)
        binding.autoReplyDailyLimitSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                config.dailyLimit = dailyLimitOptions[position]
                updateSummary()
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        binding.btnAddAutoReplyRule.setOnClickListener {
            showEditRuleDialog(null)
        }

        renderRulesList()
        updateSummary()
    }

    private fun updateSummary() {
        binding.autoReplyStatusSummary.text = config.summary()
    }

    private fun renderRulesList() {
        val container = binding.autoReplyRulesContainer
        container.removeAllViews()
        val rules = config.rules

        binding.autoReplyEmptyHint.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE

        rules.forEachIndexed { index, rule ->
            val card = createRuleCard(rule, index)
            container.addView(card)
        }
    }

    private fun createRuleCard(rule: AutoReplyRule, index: Int): View {
        val card = CardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, 0, 0, 24) }
            radius = 28f
            cardElevation = 0f
            setCardBackgroundColor(ContextCompat.getColor(this@AutoReplySettingsActivity, R.color.miui_card_background))
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 32, 36, 32)
        }

        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val title = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            text = rule.name.ifBlank { "未命名规则 #${index + 1}" }
            textSize = 16f
            setTextColor(ContextCompat.getColor(this@AutoReplySettingsActivity, R.color.miui_primary_text))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        val switch = SwitchCompat(this).apply {
            isChecked = rule.enabled
            setOnCheckedChangeListener { _, isChecked ->
                val currentRules = config.rules.toMutableList()
                if (index in currentRules.indices) {
                    currentRules[index] = currentRules[index].copy(enabled = isChecked)
                    config.rules = currentRules
                    updateSummary()
                }
            }
        }

        headerRow.addView(title)
        headerRow.addView(switch)
        layout.addView(headerRow)

        val desc = TextView(this).apply {
            val kw = if (rule.includeKeywords.isNotEmpty()) rule.includeKeywords.joinToString(" / ") else "所有内容"
            val sender = if (rule.senderFilter.isNotBlank()) rule.senderFilter else "任意号码"
            text = "匹配: 发件人 [$sender] · 关键词 [$kw]\n回复: ${rule.replyContent}\n冷却: 同号码 [${rule.formatCooldownLabel()}] 最多一次"
            textSize = 13f
            setTextColor(ContextCompat.getColor(this@AutoReplySettingsActivity, R.color.miui_secondary_text))
            setPadding(0, 12, 0, 16)
        }
        layout.addView(desc)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
        }

        val btnEdit = MaterialButton(this).apply {
            text = "编辑"
            setTextColor(ContextCompat.getColor(this@AutoReplySettingsActivity, R.color.miui_action_blue))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener { showEditRuleDialog(rule) }
        }

        val btnDelete = MaterialButton(this).apply {
            text = "删除"
            setTextColor(ContextCompat.getColor(this@AutoReplySettingsActivity, R.color.miui_unread_red))
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setOnClickListener {
                val currentRules = config.rules.toMutableList()
                currentRules.removeAll { it.id == rule.id }
                config.rules = currentRules
                renderRulesList()
                updateSummary()
                toast("规则已删除")
            }
        }

        btnRow.addView(btnEdit)
        btnRow.addView(btnDelete)
        layout.addView(btnRow)

        card.addView(layout)
        return card
    }

    private fun showEditRuleDialog(existingRule: AutoReplyRule?) {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_auto_reply_rule, null)
        val etName = dialogView.findViewById<EditText>(R.id.dialog_rule_name)
        val etSender = dialogView.findViewById<EditText>(R.id.dialog_rule_sender)
        val etKeywords = dialogView.findViewById<EditText>(R.id.dialog_rule_keywords)
        val etExclude = dialogView.findViewById<EditText>(R.id.dialog_rule_exclude)
        val etContent = dialogView.findViewById<EditText>(R.id.dialog_rule_content)
        val etCooldownVal = dialogView.findViewById<EditText>(R.id.dialog_rule_cooldown_value)
        val spCooldownUnit = dialogView.findViewById<Spinner>(R.id.dialog_rule_cooldown_unit)
        val spSim = dialogView.findViewById<Spinner>(R.id.dialog_rule_sim)

        val unitLabels = listOf("小时", "分钟", "天", "不限制 (每次均回复)")
        spCooldownUnit.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, unitLabels)

        val simScopes = listOf(AutoReplyRule.SIM_SAME, AutoReplyRule.SIM_1, AutoReplyRule.SIM_2)
        val simLabels = listOf("跟随接收短信的卡槽 (推荐)", "指定 SIM 1 发送", "指定 SIM 2 发送")
        spSim.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, simLabels)

        spCooldownUnit.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 3) { // 不限制
                    etCooldownVal.setText("0")
                    etCooldownVal.isEnabled = false
                } else {
                    etCooldownVal.isEnabled = true
                    if (etCooldownVal.text.toString() == "0") {
                        etCooldownVal.setText(if (position == 0) "24" else if (position == 1) "10" else "1")
                    }
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        if (existingRule != null) {
            etName.setText(existingRule.name)
            etSender.setText(existingRule.senderFilter)
            etKeywords.setText(existingRule.includeKeywords.joinToString(", "))
            etExclude.setText(existingRule.excludeKeywords.joinToString(", "))
            etContent.setText(existingRule.replyContent)
            
            val minutes = existingRule.rateLimitMinutes
            when {
                minutes <= 0 -> {
                    etCooldownVal.setText("0")
                    spCooldownUnit.setSelection(3) // 不限制
                }
                minutes % 1440 == 0 -> {
                    etCooldownVal.setText("${minutes / 1440}")
                    spCooldownUnit.setSelection(2) // 天
                }
                minutes % 60 == 0 -> {
                    etCooldownVal.setText("${minutes / 60}")
                    spCooldownUnit.setSelection(0) // 小时
                }
                else -> {
                    etCooldownVal.setText("$minutes")
                    spCooldownUnit.setSelection(1) // 分钟
                }
            }
            spSim.setSelection(simScopes.indexOf(existingRule.simScope).coerceAtLeast(0))
        } else {
            etCooldownVal.setText("24")
            spCooldownUnit.setSelection(0) // 24小时默认
            spSim.setSelection(0) // same sim default
        }

        AlertDialog.Builder(this)
            .setTitle(if (existingRule != null) "编辑自动回复规则" else "添加自动回复规则")
            .setView(dialogView)
            .setPositiveButton("保存") { _, _ ->
                val name = etName.text.toString().trim()
                val replyContent = etContent.text.toString().trim()
                if (replyContent.isBlank()) {
                    toast("回复内容不能为空")
                    return@setPositiveButton
                }

                val senderFilter = etSender.text.toString().trim()
                val includeKeywords = etKeywords.text.toString().split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
                val excludeKeywords = etExclude.text.toString().split(",", "，").map { it.trim() }.filter { it.isNotEmpty() }
                
                val rawVal = etCooldownVal.text.toString().toIntOrNull() ?: 0
                val unitPos = spCooldownUnit.selectedItemPosition
                val calculatedMinutes = when (unitPos) {
                    0 -> rawVal * 60 // 小时
                    1 -> rawVal // 分钟 (如 1 分钟, 5 分钟, 30 分钟)
                    2 -> rawVal * 1440 // 天
                    else -> 0 // 不限制
                }.coerceAtLeast(0)

                val simScope = simScopes[spSim.selectedItemPosition.coerceIn(0, simScopes.lastIndex)]

                val newRule = (existingRule ?: AutoReplyRule()).copy(
                    name = name,
                    senderFilter = senderFilter,
                    includeKeywords = includeKeywords,
                    excludeKeywords = excludeKeywords,
                    replyContent = replyContent,
                    rateLimitMinutes = calculatedMinutes,
                    simScope = simScope,
                    enabled = true
                )

                val currentRules = config.rules.toMutableList()
                val existingIndex = currentRules.indexOfFirst { it.id == newRule.id }
                if (existingIndex >= 0) {
                    currentRules[existingIndex] = newRule
                } else {
                    currentRules.add(newRule)
                }
                config.rules = currentRules
                renderRulesList()
                updateSummary()
                toast("保存成功 (冷却: ${newRule.formatCooldownLabel()})")
            }
            .setNegativeButton("取消", null)
            .show()
            .applySmsDialogColors()
    }
}
