package org.fossify.messages.ui.compose.rules

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fossify.messages.R
import org.fossify.messages.forwarding.ForwardingMessageFormatter
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.rule.template.TemplateRenderer
import org.fossify.messages.ui.compose.theme.AppBackground
import org.fossify.messages.ui.compose.theme.BrandGreen
import org.fossify.messages.ui.compose.theme.BrandGreenSoft
import org.fossify.messages.ui.compose.theme.DarkBackground
import org.fossify.messages.ui.compose.theme.DarkOutline
import org.fossify.messages.ui.compose.theme.DarkSurface
import org.fossify.messages.ui.compose.theme.GatewayBlue
import org.fossify.messages.ui.compose.theme.GatewayGreen
import org.fossify.messages.ui.compose.theme.GatewayPurple
import org.fossify.messages.ui.compose.theme.OutlineSoft
import org.fossify.messages.ui.compose.theme.SurfaceCard
import org.fossify.messages.ui.compose.theme.TextPrimary
import org.fossify.messages.ui.compose.theme.TextSecondary
import org.fossify.messages.ui.compose.theme.TextTertiary

enum class TemplatePreset(val mode: Int, val label: String, val emoji: String) {
    COMPACT(MultiForwardConfig.TEMPLATE_COMPACT, "紧凑模式", "📱"),
    STANDARD(MultiForwardConfig.TEMPLATE_STANDARD, "标准模式", "📑"),
    DETAILED(MultiForwardConfig.TEMPLATE_DETAILED, "详细模式", "🔍"),
    EMOJI(MultiForwardConfig.TEMPLATE_EMOJI, "Emoji增强", "✨"),
    CUSTOM(MultiForwardConfig.TEMPLATE_CUSTOM, "自定义", "🛠️")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleStudioScreen(
    onBack: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val config = remember { MultiForwardConfig(context) }
    val isDark = isSystemInDarkTheme()
    val pageBgColor = if (isDark) DarkBackground else AppBackground
    val primaryTextColor = if (isDark) Color.White else TextPrimary
    val secondaryTextColor = if (isDark) Color(0xFF9CA3AF) else TextSecondary

    var currentMode by remember { mutableStateOf(config.templateMode) }
    var customTemplateText by remember {
        mutableStateOf(
            config.customTemplate.ifBlank {
                "【短信转发】\n发信人: {{FROM}}\n验证码: {{CODE}}\n内容: {{SMS}}\n时间: {{RECEIVE_TIME}}\n卡槽: {{SIM_SLOT}}"
            }
        )
    }

    // 沙箱测试变量
    var testSender by remember { mutableStateOf("95588") }
    var testBody by remember { mutableStateOf("【工商银行】您尾号8899的账户于08月28日转账支出500.00元，动态验证码为 629184，请勿泄露。") }
    var testSimSlot by remember { mutableStateOf(0) }

    val extractedCode = remember(testBody) {
        val code = TemplateRenderer.extractVerificationCode(testBody)
        if (code.isBlank()) "未检测到验证码" else code
    }

    // 实时预览渲染结果
    val previewOutput = remember(currentMode, customTemplateText, testSender, testBody, testSimSlot) {
        if (currentMode == MultiForwardConfig.TEMPLATE_CUSTOM) {
            val prevCustom = config.customTemplate
            val prevMode = config.templateMode
            config.customTemplate = customTemplateText
            config.templateMode = MultiForwardConfig.TEMPLATE_CUSTOM
            val payload = ForwardingMessageFormatter.format(
                context = context,
                sender = testSender,
                body = testBody,
                receivedAt = System.currentTimeMillis(),
                subscriptionId = testSimSlot
            )
            config.customTemplate = prevCustom
            config.templateMode = prevMode
            payload.content
        } else {
            val prevMode = config.templateMode
            config.templateMode = currentMode
            val payload = ForwardingMessageFormatter.format(
                context = context,
                sender = testSender,
                body = testBody,
                receivedAt = System.currentTimeMillis(),
                subscriptionId = testSimSlot
            )
            config.templateMode = prevMode
            "${payload.title}\n\n${payload.content}"
        }
    }

    // 常用模板标签（全量扩展）
    val placeholderTags = listOf(
        "{{FROM}}" to "发信号码",
        "{{CONTACT_NAME}}" to "通讯录姓名",
        "{{CODE}}" to "智能提取验证码 🔑",
        "{{SMS}}" to "短信完整正文",
        "{{RECEIVE_TIME}}" to "完整接收时间",
        "{{DATE_YMD}}" to "仅日期",
        "{{DATE_HMS}}" to "仅时间",
        "{{SIM_SLOT}}" to "卡槽与运营商",
        "{{RECEIVER_NUMBER}}" to "本机接收卡号",
        "{{DEVICE_NAME}}" to "设备型号",
        "{{BATTERY_INFO}}" to "电量与充电状态",
        "{{NET_TYPE}}" to "网络类型(WiFi/5G)",
        "{{IP_LIST}}" to "当前IP地址",
        "{{APP_VERSION}}" to "客户端版本"
    )

    Scaffold(
        containerColor = pageBgColor
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
        ) {
            // 顶部 Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (onBack != null) {
                        Surface(
                            onClick = onBack,
                            shape = RoundedCornerShape(14.dp),
                            color = if (isDark) DarkSurface else Color.White,
                            shadowElevation = 2.dp,
                            border = BorderStroke(1.dp, if (isDark) DarkOutline else OutlineSoft),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                                Icon(
                                    painter = painterResource(id = org.fossify.commons.R.drawable.ic_arrow_left_vector),
                                    contentDescription = "返回",
                                    tint = primaryTextColor,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    Column {
                        Text(
                            text = if (onBack != null) "消息模板" else "转发模板与规则编排",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryTextColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "自定义推送消息格式 · 智能验证码提取沙箱",
                            fontSize = 12.sp,
                            color = secondaryTextColor,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. 模板预设选择
                item {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = if (isDark) DarkSurface else SurfaceCard,
                        shadowElevation = 2.dp,
                        border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(15.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🎨 转发消息模板预设",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color.White else TextPrimary
                                )

                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isDark) Color(0xFF2C1E3A) else Color(0xFFF3E8FF)
                                ) {
                                    Text(
                                        text = TemplatePreset.values().firstOrNull { it.mode == currentMode }?.label ?: "自定义",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GatewayPurple,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // 模板模式网格卡片选择器 (两列等宽)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                val presets = TemplatePreset.values()
                                for (i in presets.indices step 2) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val p1 = presets[i]
                                        TemplatePresetCard(
                                            preset = p1,
                                            isSelected = currentMode == p1.mode,
                                            onClick = { currentMode = p1.mode },
                                            modifier = Modifier.weight(1f)
                                        )

                                        if (i + 1 < presets.size) {
                                            val p2 = presets[i + 1]
                                            TemplatePresetCard(
                                                preset = p2,
                                                isSelected = currentMode == p2.mode,
                                                onClick = { currentMode = p2.mode },
                                                modifier = Modifier.weight(1f)
                                            )
                                        } else {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }

                            // 如果选择自定义模式，显示编辑器与标签插入
                            if (currentMode == MultiForwardConfig.TEMPLATE_CUSTOM) {
                                Spacer(modifier = Modifier.height(14.dp))
                                Text(
                                    text = "💡 点击下方标签可快速插入变量：",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (isDark) Color(0xFFD1D5DB) else TextSecondary
                                )

                                Spacer(modifier = Modifier.height(8.dp))

                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    placeholderTags.forEach { (tag, desc) ->
                                        Surface(
                                            shape = RoundedCornerShape(10.dp),
                                            color = if (isDark) Color(0xFF1B3322) else BrandGreenSoft,
                                            border = BorderStroke(1.dp, if (isDark) Color(0xFF2E5E3B) else Color(0xFFC7EBD0)),
                                            modifier = Modifier.clickable {
                                                customTemplateText += tag
                                            }
                                        ) {
                                            Text(
                                                text = "+ $tag ($desc)",
                                                fontSize = 11.5.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = BrandGreen,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                OutlinedTextField(
                                    value = customTemplateText,
                                    onValueChange = { customTemplateText = it },
                                    label = { Text("自定义模板表达式") },
                                    shape = RoundedCornerShape(16.dp),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedBorderColor = BrandGreen,
                                        unfocusedBorderColor = if (isDark) DarkOutline else OutlineSoft
                                    ),
                                    modifier = Modifier.fillMaxWidth(),
                                    minLines = 4,
                                    maxLines = 8
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            // 保存按钮
                            Button(
                                onClick = {
                                    config.templateMode = currentMode
                                    if (currentMode == MultiForwardConfig.TEMPLATE_CUSTOM) {
                                        config.customTemplate = customTemplateText
                                    }
                                    Toast.makeText(context, "转发模板配置已保存成功并即时生效！", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
                            ) {
                                Text(
                                    text = "💾 保存模板配置",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }

                // 2. 实时沙箱沙盘预览
                item {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = if (isDark) DarkSurface else SurfaceCard,
                        shadowElevation = 2.dp,
                        border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(15.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🧪 实时渲染预览沙箱",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color.White else TextPrimary
                                )
                                if (extractedCode != "未检测到验证码") {
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (isDark) Color(0xFF1B3322) else BrandGreenSoft
                                    ) {
                                        Text(
                                            text = "验证码: $extractedCode",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = BrandGreen,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            OutlinedTextField(
                                value = testSender,
                                onValueChange = { testSender = it },
                                label = { Text("测试发信号码 / 名称") },
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BrandGreen,
                                    unfocusedBorderColor = if (isDark) DarkOutline else OutlineSoft
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            OutlinedTextField(
                                value = testBody,
                                onValueChange = { testBody = it },
                                label = { Text("测试短信原文") },
                                shape = RoundedCornerShape(16.dp),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = BrandGreen,
                                    unfocusedBorderColor = if (isDark) DarkOutline else OutlineSoft
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                maxLines = 4
                            )

                            Spacer(modifier = Modifier.height(10.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "测试卡槽:",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = primaryTextColor
                                )
                                listOf(0 to "卡槽 1 (SIM1)", 1 to "卡槽 2 (SIM2)").forEach { (slotIdx, label) ->
                                    val isSelected = testSimSlot == slotIdx
                                    Surface(
                                        onClick = { testSimSlot = slotIdx },
                                        shape = RoundedCornerShape(12.dp),
                                        color = if (isSelected) (if (isDark) Color(0xFF1B3322) else BrandGreenSoft) else (if (isDark) DarkSurface else Color(0xFFF1F5F9)),
                                        border = BorderStroke(1.dp, if (isSelected) BrandGreen else Color.Transparent),
                                        modifier = Modifier.height(32.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 11.5.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) BrandGreen else secondaryTextColor
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Text(
                                text = "📤 目标推送端（钉钉/企微/PushPlus/Bark）收到的最终样式：",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDark) Color(0xFFD1D5DB) else TextSecondary
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = if (isDark) Color(0xFF22262B) else Color(0xFFF8FAFC),
                                border = BorderStroke(1.dp, if (isDark) Color(0xFF2D333B) else Color(0xFFEEF2F6)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = previewOutput,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 12.sp,
                                    lineHeight = 19.sp,
                                    color = if (isDark) Color.White else TextPrimary,
                                    modifier = Modifier.padding(14.dp)
                                )
                            }
                        }
                    }
                }

                // 3. 智能规则列表与开关（与经典版实时双向同步）
                item {
                    val rulesConfig = remember { org.fossify.messages.forwarding.ForwardingRulesConfig(context) }
                    var rulesMasterEnabled by remember { mutableStateOf(rulesConfig.enabled) }
                    var currentRulesList by remember { mutableStateOf(rulesConfig.rules) }

                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = if (isDark) DarkSurface else SurfaceCard,
                        shadowElevation = 2.dp,
                        border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(15.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                    Text(
                                        text = "⚡ 智能过滤与分流规则",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isDark) Color.White else TextPrimary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (rulesMasterEnabled) "已启用 (${currentRulesList.size} 条自定义规则)" else "总开关已关闭 (所有短信全量转发)",
                                        fontSize = 13.sp,
                                        color = if (rulesMasterEnabled) BrandGreen else secondaryTextColor
                                    )
                                }
                                Switch(
                                    checked = rulesMasterEnabled,
                                    onCheckedChange = {
                                        rulesMasterEnabled = it
                                        rulesConfig.enabled = it
                                        Toast.makeText(context, "规则总开关已${if (it) "开启" else "关闭"}", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = BrandGreen
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            if (currentRulesList.isEmpty()) {
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isDark) Color(0xFF22262B) else Color(0xFFF8FAFC),
                                    border = BorderStroke(1.dp, if (isDark) Color(0xFF2D333B) else Color(0xFFEEF2F6)),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(
                                        text = "💡 提示：当前尚未创建自定义分流规则。点击下方按钮可针对 SIM卡槽、发件人白名单、短信关键词（如验证码/取件码）或正则表达式创建精细化分流策略。",
                                        fontSize = 11.sp,
                                        lineHeight = 18.sp,
                                        color = if (isDark) Color(0xFF9CA3AF) else TextSecondary,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            } else {
                                currentRulesList.forEachIndexed { index, rule ->
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = if (isDark) Color(0xFF22262B) else Color(0xFFF8FAFC),
                                        border = BorderStroke(1.dp, if (isDark) Color(0xFF2D333B) else Color(0xFFEEF2F6)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 3.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                                Text(
                                                    text = rule.name.ifBlank { "规则 #${index + 1}" },
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (isDark) Color.White else TextPrimary,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = buildString {
                                                        append(if (rule.simScope == org.fossify.messages.forwarding.ForwardingRule.SIM_1) "SIM1 " else if (rule.simScope == org.fossify.messages.forwarding.ForwardingRule.SIM_2) "SIM2 " else "双卡 ")
                                                        if (rule.includeKeywords.isNotEmpty()) append("包含:[${rule.includeKeywords.take(3).joinToString(",")}] ")
                                                        if (rule.excludeKeywords.isNotEmpty()) append("排除:[${rule.excludeKeywords.take(2).joinToString(",")}] ")
                                                        if (rule.includeRegex.isNotBlank()) append("正则 ")
                                                    }.ifBlank { "全量匹配" },
                                                    fontSize = 12.sp,
                                                    color = if (isDark) Color(0xFF9CA3AF) else TextSecondary,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            Switch(
                                                checked = rule.enabled,
                                                onCheckedChange = { isChecked ->
                                                    val updated = currentRulesList.toMutableList()
                                                    updated[index] = rule.copy(enabled = isChecked)
                                                    currentRulesList = updated
                                                    rulesConfig.rules = updated
                                                    Toast.makeText(context, "${rule.name} 已${if (isChecked) "启用" else "禁用"}", Toast.LENGTH_SHORT).show()
                                                },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color.White,
                                                    checkedTrackColor = BrandGreen
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            OutlinedButton(
                                onClick = {
                                    context.startActivity(android.content.Intent(context, org.fossify.messages.activities.ForwardingRulesSettingsActivity::class.java))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, if (isDark) DarkOutline else OutlineSoft)
                            ) {
                                Text(
                                    text = "打开高级规则与分流通道编辑器 🛠️",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color.White else TextPrimary
                                )
                            }
                        }
                    }
                }

                // 4. 短信远程发信与指令控制
                item {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = if (isDark) DarkSurface else SurfaceCard,
                        shadowElevation = 2.dp,
                        border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(15.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📡 短信远程发信与指令控制",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color.White else TextPrimary
                                )
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = if (isDark) Color(0xFF2C1E3A) else Color(0xFFF3E8FF)
                                ) {
                                    Text(
                                        text = "指令发信",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = GatewayPurple,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "支持通过短信指令（如 /发信 [SIM1|SIM2] 手机号 内容）或钉钉 Stream 机器人远程控制本机发信，支持多通道回执与授权白名单。",
                                fontSize = 13.sp,
                                color = if (isDark) Color(0xFFD1D5DB) else TextSecondary,
                                lineHeight = 19.sp
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            OutlinedButton(
                                onClick = {
                                    context.startActivity(android.content.Intent(context, org.fossify.messages.activities.RemoteForwardingActivity::class.java))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, if (isDark) DarkOutline else OutlineSoft)
                            ) {
                                Text(
                                    text = "配置短信远程指令与钉钉控制 ⚙️",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color.White else TextPrimary
                                )
                            }
                        }
                    }
                }

                // 5. 智能防对轰短信自动回复引擎
                item {
                    val autoReplyConfig = remember { org.fossify.messages.autoreply.AutoReplyConfig(context) }
                    var autoReplyEnabled by remember { mutableStateOf(autoReplyConfig.enabled) }

                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = if (isDark) DarkSurface else SurfaceCard,
                        shadowElevation = 2.dp,
                        border = BorderStroke(1.dp, if (isDark) DarkOutline else Color(0xFFF0F3F7)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(15.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "🤖 智能防对轰自动回复引擎",
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color.White else TextPrimary
                                )
                                Switch(
                                    checked = autoReplyEnabled,
                                    onCheckedChange = { isChecked ->
                                        autoReplyEnabled = isChecked
                                        autoReplyConfig.enabled = isChecked
                                        Toast.makeText(context, "自动回复已${if (isChecked) "启用" else "禁用"}", Toast.LENGTH_SHORT).show()
                                    },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = BrandGreen
                                    )
                                )
                            }

                            Spacer(modifier = Modifier.height(10.dp))

                            Text(
                                text = "满足副卡保号、运营商业务办理（如 10086 回复 Y）、备用机留言应答。内置 4 重防对轰熔断保护（同号 24h 冷却、发信延迟、日限额与回执推送）。",
                                fontSize = 13.sp,
                                color = if (isDark) Color(0xFFD1D5DB) else TextSecondary,
                                lineHeight = 19.sp
                            )

                            Spacer(modifier = Modifier.height(14.dp))

                            OutlinedButton(
                                onClick = {
                                    context.startActivity(android.content.Intent(context, org.fossify.messages.activities.AutoReplySettingsActivity::class.java))
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(44.dp),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, if (isDark) DarkOutline else OutlineSoft)
                            ) {
                                Text(
                                    text = "管理自动回复规则与风控策略 🤖",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDark) Color.White else TextPrimary
                                )
                            }
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(120.dp)) }
            }
        }
    }
}

@Composable
private fun TemplatePresetCard(
    preset: TemplatePreset,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isDark = isSystemInDarkTheme()
    val bgColor = if (isSelected) {
        if (isDark) Color(0xFF1B3322) else BrandGreenSoft
    } else {
        if (isDark) Color(0xFF22262B) else Color(0xFFF8FAFC)
    }

    val borderColor = if (isSelected) {
        BrandGreen
    } else {
        if (isDark) DarkOutline else OutlineSoft
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = bgColor,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, borderColor),
        modifier = modifier.height(56.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = preset.emoji, fontSize = 20.sp)
                Text(
                    text = preset.label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) BrandGreen else (if (isDark) Color.White else TextPrimary),
                    maxLines = 1,
                    softWrap = false
                )
            }

            if (isSelected) {
                Surface(
                    shape = CircleShape,
                    color = BrandGreen,
                    modifier = Modifier.size(18.dp)
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                        Text(text = "✓", fontSize = 11.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
