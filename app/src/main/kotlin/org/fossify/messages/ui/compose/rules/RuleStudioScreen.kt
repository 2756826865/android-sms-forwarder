package org.fossify.messages.ui.compose.rules

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fossify.messages.forwarding.ForwardingMessageFormatter
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.rule.template.TemplateRenderer
import org.fossify.messages.ui.compose.components.GatewayCard
import org.fossify.messages.ui.compose.components.StatusBadge
import org.fossify.messages.ui.compose.theme.GatewayBlue
import org.fossify.messages.ui.compose.theme.GatewayGreen
import org.fossify.messages.ui.compose.theme.GatewayOrange
import org.fossify.messages.ui.compose.theme.GatewayPurple

enum class TemplatePreset(val mode: Int, val label: String, val emoji: String) {
    COMPACT(MultiForwardConfig.TEMPLATE_COMPACT, "紧凑模式", "📱"),
    STANDARD(MultiForwardConfig.TEMPLATE_STANDARD, "标准模式", "📑"),
    DETAILED(MultiForwardConfig.TEMPLATE_DETAILED, "详细模式", "🔍"),
    EMOJI(MultiForwardConfig.TEMPLATE_EMOJI, "Emoji增强", "✨"),
    CUSTOM(MultiForwardConfig.TEMPLATE_CUSTOM, "自定义", "🛠️")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleStudioScreen() {
    val context = LocalContext.current
    val config = remember { MultiForwardConfig(context) }

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

    val extractedCode = remember(testBody) {
        val code = TemplateRenderer.extractVerificationCode(testBody)
        if (code.isBlank()) "未检测到验证码" else code
    }

    // 实时预览渲染结果
    val previewOutput = remember(currentMode, customTemplateText, testSender, testBody) {
        if (currentMode == MultiForwardConfig.TEMPLATE_CUSTOM) {
            var res = customTemplateText
                .replace("{{FROM}}", testSender)
                .replace("{sender}", testSender)
                .replace("{{SMS}}", testBody)
                .replace("{body}", testBody)
                .replace("{{CODE}}", TemplateRenderer.extractVerificationCode(testBody))
                .replace("{{RECEIVE_TIME}}", "2026-08-28 11:24:00")
                .replace("{time}", "2026-08-28 11:24:00")
                .replace("{{SIM_SLOT}}", "SIM 1 (中国移动)")
                .replace("{sim}", "SIM 1 (中国移动)")
                .replace("{{DEVICE_NAME}}", "HUAWEI EBG-AN00")
                .replace("{{BATTERY_INFO}}", "85% (充电中)")
            res
        } else {
            // 使用系统内置 formatter
            val prevMode = config.templateMode
            config.templateMode = currentMode
            val payload = ForwardingMessageFormatter.format(
                context = context,
                sender = testSender,
                body = testBody,
                receivedAt = System.currentTimeMillis(),
                subscriptionId = 1
            )
            config.templateMode = prevMode
            "${payload.title}\n\n${payload.content}"
        }
    }

    // 常用模板标签
    val placeholderTags = listOf(
        "{{FROM}}" to "发信人",
        "{{SMS}}" to "短信正文",
        "{{CODE}}" to "智能提取验证码",
        "{{RECEIVE_TIME}}" to "接收时间",
        "{{SIM_SLOT}}" to "卡槽信息",
        "{{DEVICE_NAME}}" to "设备型号",
        "{{BATTERY_INFO}}" to "剩余电量"
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "转发模板与规则编排",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "自定义推送消息格式 · 智能验证码提取沙箱",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Spacer(modifier = Modifier.height(2.dp)) }

            // 1. 模板预设选择
            item {
                GatewayCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🎨 转发消息模板预设",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            StatusBadge(
                                text = TemplatePreset.values().firstOrNull { it.mode == currentMode }?.label ?: "自定义",
                                color = GatewayPurple
                            )
                        }

                        // 模板模式切换 Chips
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            TemplatePreset.values().forEach { preset ->
                                FilterChip(
                                    selected = currentMode == preset.mode,
                                    onClick = { currentMode = preset.mode },
                                    label = { Text("${preset.emoji} ${preset.label}", fontSize = 12.sp) }
                                )
                            }
                        }

                        // 如果选择自定义模式，显示编辑器与标签插入
                        if (currentMode == MultiForwardConfig.TEMPLATE_CUSTOM) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "💡 点击下方标签可快速插入变量：",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                placeholderTags.forEach { (tag, desc) ->
                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        modifier = Modifier.clickable {
                                            customTemplateText += tag
                                        }
                                    ) {
                                        Text(
                                            text = "+ $tag ($desc)",
                                            fontSize = 11.sp,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                            }

                            OutlinedTextField(
                                value = customTemplateText,
                                onValueChange = { customTemplateText = it },
                                label = { Text("自定义模板表达式") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 4,
                                maxLines = 8
                            )
                        }

                        // 保存按钮
                        Button(
                            onClick = {
                                config.templateMode = currentMode
                                if (currentMode == MultiForwardConfig.TEMPLATE_CUSTOM) {
                                    config.customTemplate = customTemplateText
                                }
                                Toast.makeText(context, "转发模板配置已保存成功并即时生效！", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("💾 保存模板配置")
                        }
                    }
                }
            }

            // 2. 实时沙箱沙盘预览
            item {
                GatewayCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "🧪 实时渲染预览沙箱",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            if (extractedCode != "未检测到验证码") {
                                StatusBadge("验证码: $extractedCode", GatewayGreen)
                            }
                        }

                        OutlinedTextField(
                            value = testSender,
                            onValueChange = { testSender = it },
                            label = { Text("测试发信号码 / 名称") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = testBody,
                            onValueChange = { testBody = it },
                            label = { Text("测试短信原文") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 4
                        )

                        Text(
                            text = "📤 目标推送端（钉钉/企微/PushPlus/Bark）收到的最终样式：",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = previewOutput,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }
                }
            }

            // 3. 智能规则列表与开关
            item {
                GatewayCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "⚡ 智能过滤与分流规则",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )

                        RuleItemView(
                            name = "银行验证码极速通道",
                            desc = "匹配包含 [验证码/动态码] 且发件人为 955/106 的短信，优先提取 {{CODE}} 并高优先级推送",
                            initEnabled = true
                        )

                        RuleItemView(
                            name = "快递取件码聚合通知",
                            desc = "识别菜鸟驿站、丰巢快递柜取件码，自动高亮并推送到指定通道",
                            initEnabled = true
                        )

                        RuleItemView(
                            name = "垃圾营销短信拦截过滤",
                            desc = "含 [回复TD退订/代开发票/办理贷款] 等营销特征短信，不触发对外转发",
                            initEnabled = false
                        )
                    }
                }
            }

            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@Composable
fun RuleItemView(
    name: String,
    desc: String,
    initEnabled: Boolean
) {
    var enabled by remember { mutableStateOf(initEnabled) }
    val context = LocalContext.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        Switch(
            checked = enabled,
            onCheckedChange = {
                enabled = it
                Toast.makeText(context, "$name 已${if (it) "启用" else "禁用"}", Toast.LENGTH_SHORT).show()
            }
        )
    }
}
