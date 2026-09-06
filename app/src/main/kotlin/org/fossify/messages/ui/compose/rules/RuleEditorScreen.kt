package org.fossify.messages.ui.compose.rules

import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fossify.messages.R
import org.fossify.messages.forwarding.ForwardingRule
import org.fossify.messages.forwarding.RuleConditionRelation
import org.fossify.messages.ui.compose.components.GatewayCard
import org.fossify.messages.ui.compose.rules.components.ActionInstanceSelector
import org.fossify.messages.ui.compose.rules.components.ConditionRow
import org.fossify.messages.ui.compose.rules.components.DndEditor
import org.fossify.messages.ui.compose.rules.components.RegexReplacementList
import org.fossify.messages.ui.compose.rules.components.RuleTestSection
import org.fossify.messages.ui.compose.theme.AppBackground
import org.fossify.messages.ui.compose.theme.BrandGreen
import org.fossify.messages.ui.compose.theme.DarkBackground
import org.fossify.messages.ui.compose.theme.DarkOutline
import org.fossify.messages.ui.compose.theme.GatewayRed
import org.fossify.messages.ui.compose.theme.OutlineSoft
import org.fossify.messages.ui.compose.theme.TextPrimary
import org.fossify.messages.ui.compose.theme.TextSecondary

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RuleEditorScreen(
    ruleId: String?,
    onNavigateBack: () -> Unit
) {
    val context = LocalContext.current
    val viewModel = remember(ruleId) { RuleEditorViewModel(context, ruleId) }
    val uiState by viewModel.uiState.collectAsState()

    var showExitConfirmDialog by remember { mutableStateOf(false) }

    val isDark = isSystemInDarkTheme()
    val pageBg = if (isDark) DarkBackground else AppBackground
    val primaryText = if (isDark) Color.White else TextPrimary
    val secondaryText = if (isDark) Color(0xFF9CA3AF) else TextSecondary
    val borderColor = if (isDark) DarkOutline else OutlineSoft

    BackHandler {
        showExitConfirmDialog = true
    }

    LaunchedEffect(uiState.isSavedSuccess) {
        if (uiState.isSavedSuccess) {
            Toast.makeText(context, "规则已成功保存", Toast.LENGTH_SHORT).show()
            onNavigateBack()
        }
    }

    Scaffold(
        containerColor = pageBg,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (uiState.isNew) "新建规则" else "编辑规则",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { showExitConfirmDialog = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_chevron_left),
                            contentDescription = "返回",
                            tint = primaryText
                        )
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.saveRule() },
                        colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                        modifier = Modifier
                            .padding(end = 12.dp)
                            .height(36.dp)
                    ) {
                        Text(text = "保存", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = pageBg,
                    titleContentColor = primaryText
                ),
                modifier = Modifier.statusBarsPadding()
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            // ==========================================
            // 分组 A：基本信息
            // ==========================================
            item {
                GatewayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "📌 基本信息",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryText
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        OutlinedTextField(
                            value = uiState.name,
                            onValueChange = viewModel::updateName,
                            label = { Text("规则名称 (必填)") },
                            placeholder = { Text("例如: 银行与验证码优先转发") },
                            isError = uiState.nameError != null,
                            supportingText = uiState.nameError?.let { { Text(it, color = GatewayRed) } },
                            singleLine = true,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = borderColor
                            )
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "启用此规则",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = primaryText
                                )
                                Text(
                                    text = "保存后立即生效匹配",
                                    fontSize = 11.sp,
                                    color = secondaryText
                                )
                            }
                            Switch(
                                checked = uiState.enabled,
                                onCheckedChange = viewModel::updateEnabled,
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = BrandGreen
                                )
                            )
                        }
                    }
                }
            }

            // ==========================================
            // 分组 B：如果 (匹配条件)
            // ==========================================
            item {
                GatewayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "🔍 匹配条件",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryText
                        )

                        Spacer(modifier = Modifier.height(10.dp))

                        // 条件逻辑关系单独占一行，避免窄屏下文字被挤成竖排。
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                RuleConditionRelation.ALL to "全部满足",
                                RuleConditionRelation.ANY to "任一满足"
                            ).forEach { (relation, label) ->
                                val isSelected = uiState.conditionRelation == relation
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) BrandGreen else borderColor,
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(38.dp)
                                ) {
                                    TextButton(
                                        onClick = { viewModel.updateConditionRelation(relation) },
                                        modifier = Modifier.fillMaxWidth(),
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                    ) {
                                        Text(
                                            text = label,
                                            fontSize = 12.sp,
                                            color = if (isSelected) Color.White else primaryText
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // 卡槽匹配选择
                        Text(text = "匹配卡槽：", fontSize = 12.sp, color = secondaryText)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf(
                                ForwardingRule.SIM_ALL to "全部卡槽",
                                ForwardingRule.SIM_1 to "SIM 1",
                                ForwardingRule.SIM_2 to "SIM 2"
                            ).forEach { (simKey, simLabel) ->
                                val isSelected = uiState.simScope == simKey
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) MaterialTheme.colorScheme.primary else borderColor,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    TextButton(
                                        onClick = { viewModel.updateSimScope(simKey) },
                                        contentPadding = PaddingValues(vertical = 4.dp)
                                    ) {
                                        Text(
                                            text = simLabel,
                                            fontSize = 11.sp,
                                            color = if (isSelected) Color.White else primaryText
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 条件列表
                        if (uiState.conditions.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                uiState.conditions.forEach { cond ->
                                    ConditionRow(
                                        condition = cond,
                                        onUpdate = { field, op, value, ic ->
                                            viewModel.updateCondition(cond.id, field, op, value, ic)
                                        },
                                        onRemove = { viewModel.removeCondition(cond.id) }
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                        }

                        if (uiState.conditionError != null) {
                            Text(
                                text = uiState.conditionError.orEmpty(),
                                fontSize = 12.sp,
                                color = GatewayRed,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }

                        OutlinedButton(
                            onClick = { viewModel.addCondition() },
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_plus),
                                contentDescription = "添加条件",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(text = "添加匹配条件", fontSize = 12.sp)
                        }
                    }
                }
            }

            // ==========================================
            // 分组 C：就执行 (发送动作)
            // ==========================================
            item {
                GatewayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "⚡ 发送通道",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryText
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "只有用户主动添加并保存的通道实例才会出现在此选择器中，规则仅向所选实例投递",
                            fontSize = 11.sp,
                            color = secondaryText
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        if (uiState.actionError != null) {
                            Text(
                                text = uiState.actionError.orEmpty(),
                                fontSize = 12.sp,
                                color = GatewayRed,
                                modifier = Modifier.padding(bottom = 6.dp)
                            )
                        }

                        ActionInstanceSelector(
                            actions = uiState.actions,
                            availableInstances = uiState.availableInstances,
                            onAddAction = viewModel::addAction,
                            onRemoveAction = viewModel::removeAction
                        )
                    }
                }
            }

            // ==========================================
            // 分组 D：内容处理与模板变量
            // ==========================================
            item {
                GatewayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "📝 内容处理",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryText
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "留空则使用全局模板；填写则覆盖当前规则，支持按顺序执行多组正则提取与替换",
                            fontSize = 11.sp,
                            color = secondaryText
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        Text(text = "模板模式", fontSize = 11.sp, color = secondaryText)
                        Spacer(modifier = Modifier.height(6.dp))
                        val templatePresets = listOf(
                            "继承全局" to "",
                            "验证码" to "【{{CONTACT_NAME}}】验证码：{{CODE}}\n{{SMS}}",
                            "紧凑" to "{{CONTACT_NAME}}：{{SMS}}",
                            "标准" to "【{{SIM_SLOT}}】{{CONTACT_NAME}}\n{{SMS}}\n{{RECEIVE_TIME}}",
                            "详细" to "发件人：{{CONTACT_NAME}}（{{FROM}}）\n接收卡：{{SIM_SLOT}}\n时间：{{RECEIVE_TIME}}\n内容：{{SMS}}"
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            templatePresets.forEach { (name, template) ->
                                val selected = uiState.customTemplate == template
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else borderColor
                                ) {
                                    TextButton(
                                        onClick = { viewModel.updateCustomTemplate(template) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = name,
                                            fontSize = 11.sp,
                                            color = if (selected) MaterialTheme.colorScheme.primary else primaryText
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // 模板变量快捷插入 Chips
                        val varChips = listOf(
                            "{{FROM}}" to "发信人",
                            "{{CONTACT_NAME}}" to "联系人",
                            "{{CODE}}" to "验证码",
                            "{{SMS}}" to "短信正文",
                            "{{RECEIVE_TIME}}" to "完整时间",
                            "{{DATE_YMD}}" to "日期",
                            "{{DATE_HMS}}" to "时间",
                            "{{SIM_SLOT}}" to "卡槽",
                            "{{SIM_INDEX}}" to "卡槽序号",
                            "{{RECEIVER_NUMBER}}" to "本机号码",
                            "{{DEVICE_NAME}}" to "设备名称",
                            "{{DEVICE_BRAND}}" to "设备品牌",
                            "{{DEVICE_MODEL}}" to "设备型号",
                            "{{BATTERY_INFO}}" to "电量信息",
                            "{{BATTERY_PCT}}" to "电量百分比",
                            "{{NET_TYPE}}" to "网络类型",
                            "{{IP_LIST}}" to "IP 地址",
                            "{{APP_VERSION}}" to "应用版本"
                        )
                        Text(text = "点击插入变量：", fontSize = 11.sp, color = secondaryText)
                        Spacer(modifier = Modifier.height(6.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            varChips.forEach { (variable, label) ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = borderColor
                                ) {
                                    TextButton(
                                        onClick = {
                                            viewModel.updateCustomTemplate(uiState.customTemplate + variable)
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(text = "$label $variable", fontSize = 10.sp, color = primaryText)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        OutlinedTextField(
                            value = uiState.customTemplate,
                            onValueChange = viewModel::updateCustomTemplate,
                            label = { Text("规则专属自定义模板 (可选)") },
                            placeholder = { Text("例如: 【{{SIM_SLOT}}】收到验证码: {{CODE}}\n原文: {{SMS}}") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3,
                            shape = RoundedCornerShape(10.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = borderColor
                            )
                        )

                        Text(
                            text = if (uiState.customTemplate.isBlank()) {
                                "当前：继承全局模板"
                            } else {
                                "当前：规则独立模板 · ${uiState.customTemplate.length} 字符"
                            },
                            fontSize = 11.sp,
                            color = secondaryText,
                            modifier = Modifier.padding(top = 6.dp)
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // 正则替换列表
                        RegexReplacementList(
                            replacements = uiState.regexReplacements,
                            onAdd = { viewModel.addRegexReplacement() },
                            onUpdate = viewModel::updateRegexReplacement,
                            onRemove = viewModel::removeRegexReplacement
                        )
                    }
                }
            }

            // ==========================================
            // 分组 E：免打扰
            // ==========================================
            item {
                GatewayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        DndEditor(
                            enabled = uiState.doNotDisturbEnabled,
                            start = uiState.dndStart,
                            end = uiState.dndEnd,
                            days = uiState.dndDays,
                            onUpdate = viewModel::updateDoNotDisturb
                        )
                    }
                }
            }

            // ==========================================
            // 分组 F：实时测试
            // ==========================================
            item {
                GatewayCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        RuleTestSection(
                            sender = uiState.testSender,
                            body = uiState.testBody,
                            simSlot = uiState.testSimSlot,
                            resultSummary = uiState.testResultSummary,
                            renderedContent = uiState.testRenderedContent,
                            onUpdate = viewModel::updateTestInputs
                        )
                    }
                }
            }
        }
    }

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = { Text("放弃未保存的修改？") },
            text = { Text("当前规则的更改尚未保存，退出后更改将丢失。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirmDialog = false
                        onNavigateBack()
                    }
                ) {
                    Text("放弃退出", color = GatewayRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmDialog = false }) {
                    Text("继续编辑")
                }
            }
        )
    }
}
