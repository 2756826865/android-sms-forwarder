package org.fossify.messages.ui.compose.forwarding

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fossify.messages.autoreply.AutoReplyConfig
import org.fossify.messages.autoreply.AutoReplyRule
import org.fossify.messages.autofill.AutofillConfig
import org.fossify.messages.extensions.config
import org.fossify.messages.forwarding.CallForwardConfig
import org.fossify.messages.forwarding.HeartbeatConfig
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.TemplateDataRetriever
import org.fossify.messages.forwarding.repository.ChannelRepository
import org.fossify.messages.helpers.HeartbeatWorker
import org.fossify.messages.helpers.LowBatteryCheckWorker
import org.fossify.messages.ui.compose.theme.BrandGreen
import org.fossify.messages.ui.compose.theme.DarkOutline
import org.fossify.messages.ui.compose.theme.DarkSurface
import org.fossify.messages.ui.compose.theme.GatewayRed
import org.fossify.messages.ui.compose.theme.OutlineSoft
import org.fossify.messages.ui.compose.theme.SurfaceCard
import org.fossify.messages.ui.compose.theme.TextPrimary
import org.fossify.messages.ui.compose.theme.TextSecondary

@Composable
private fun FeatureCard(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = if (dark) DarkSurface else SurfaceCard,
        border = BorderStroke(1.dp, if (dark) DarkOutline else OutlineSoft),
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) { Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() } }
}

@Composable
private fun SettingSwitch(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val dark = isSystemInDarkTheme()
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = if (dark) Color.White else TextPrimary)
            Text(subtitle, fontSize = 12.sp, color = if (dark) Color(0xFF9CA3AF) else TextSecondary)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = BrandGreen))
    }
}

@Composable
fun AutoReplyEmbeddedScreen() {
    val context = LocalContext.current
    val cfg = remember { AutoReplyConfig(context) }
    var enabled by remember { mutableStateOf(cfg.enabled) }
    var dailyLimit by remember { mutableStateOf(cfg.dailyLimit) }
    var rules by remember { mutableStateOf(cfg.rules) }
    var editing by remember { mutableStateOf<AutoReplyRule?>(null) }
    var adding by remember { mutableStateOf(false) }
    val dark = isSystemInDarkTheme()

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            FeatureCard {
                SettingSwitch("自动回复", "符合规则时使用本机 SIM 自动回复短信", enabled) {
                    enabled = it; cfg.enabled = it
                }
                Text("每日上限：$dailyLimit 条", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(5, 10, 20, 50).forEach { limit ->
                        OutlinedButton(
                            onClick = { dailyLimit = limit; cfg.dailyLimit = limit },
                            contentPadding = PaddingValues(horizontal = 9.dp),
                            border = BorderStroke(1.dp, if (dailyLimit == limit) BrandGreen else if (dark) DarkOutline else OutlineSoft)
                        ) { Text("$limit", color = if (dailyLimit == limit) BrandGreen else MaterialTheme.colorScheme.onSurface, fontSize = 12.sp) }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("回复规则", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Button(onClick = { adding = true }, colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)) { Text("+ 新建规则") }
            }
        }
        if (rules.isEmpty()) item { FeatureCard { Text("暂无自动回复规则", color = TextSecondary, fontSize = 13.sp) } }
        items(rules.size, key = { rules[it].id }) { index ->
            val rule = rules[index]
            FeatureCard {
                SettingSwitch(rule.name.ifBlank { "未命名规则" }, "${rule.senderFilter.ifBlank { "任意号码" }} · 冷却 ${rule.formatCooldownLabel()}", rule.enabled) { checked ->
                    rules = rules.toMutableList().also { it[index] = rule.copy(enabled = checked) }; cfg.rules = rules
                }
                Text("匹配：${rule.includeKeywords.joinToString("、").ifBlank { "所有短信" }}", fontSize = 12.sp, color = TextSecondary)
                Text("回复：${rule.replyContent}", fontSize = 13.sp)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { editing = rule }) { Text("编辑") }
                    TextButton(onClick = {
                        rules = rules.filterNot { it.id == rule.id }; cfg.rules = rules
                    }) { Text("删除", color = GatewayRed) }
                }
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }

    if (adding || editing != null) {
        AutoReplyRuleDialog(
            existing = editing,
            onSave = { saved ->
                rules = rules.toMutableList().also { list ->
                    val index = list.indexOfFirst { it.id == saved.id }
                    if (index >= 0) list[index] = saved else list.add(saved)
                }
                cfg.rules = rules; adding = false; editing = null
            },
            onDismiss = { adding = false; editing = null }
        )
    }
}

@Composable
private fun AutoReplyRuleDialog(existing: AutoReplyRule?, onSave: (AutoReplyRule) -> Unit, onDismiss: () -> Unit) {
    var name by remember(existing?.id) { mutableStateOf(existing?.name.orEmpty()) }
    var sender by remember(existing?.id) { mutableStateOf(existing?.senderFilter.orEmpty()) }
    var keywords by remember(existing?.id) { mutableStateOf(existing?.includeKeywords?.joinToString(",").orEmpty()) }
    var excludes by remember(existing?.id) { mutableStateOf(existing?.excludeKeywords?.joinToString(",").orEmpty()) }
    var regex by remember(existing?.id) { mutableStateOf(existing?.includeRegex.orEmpty()) }
    var reply by remember(existing?.id) { mutableStateOf(existing?.replyContent.orEmpty()) }
    var cooldown by remember(existing?.id) { mutableStateOf((existing?.rateLimitMinutes ?: 1440).toString()) }
    var delay by remember(existing?.id) { mutableStateOf((existing?.delaySeconds ?: 3).toString()) }
    var simScope by remember(existing?.id) { mutableStateOf(existing?.simScope ?: AutoReplyRule.SIM_SAME) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "新建回复规则" else "编辑回复规则") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("规则名称") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(sender, { sender = it }, label = { Text("发件号码（留空为任意）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(keywords, { keywords = it }, label = { Text("关键词（逗号分隔）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(excludes, { excludes = it }, label = { Text("排除词（逗号分隔）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(regex, { regex = it }, label = { Text("匹配正则（选填）") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(reply, { reply = it }, label = { Text("回复内容") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            OutlinedTextField(cooldown, { cooldown = it.filter(Char::isDigit) }, label = { Text("同号码冷却分钟") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(delay, { delay = it.filter(Char::isDigit) }, label = { Text("回复延迟秒数") }, modifier = Modifier.fillMaxWidth())
            Text("发送卡槽", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                listOf(AutoReplyRule.SIM_SAME to "跟随接收卡", AutoReplyRule.SIM_1 to "SIM1", AutoReplyRule.SIM_2 to "SIM2").forEach { (value, label) ->
                    OutlinedButton(onClick = { simScope = value }, contentPadding = PaddingValues(horizontal = 8.dp), border = BorderStroke(1.dp, if (simScope == value) BrandGreen else OutlineSoft)) {
                        Text(label, fontSize = 11.sp, color = if (simScope == value) BrandGreen else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        } },
        confirmButton = { Button(onClick = {
            if (reply.isBlank()) { return@Button }
            onSave((existing ?: AutoReplyRule()).copy(
                name = name.trim(), senderFilter = sender.trim(),
                includeKeywords = keywords.split(',', '，').map(String::trim).filter(String::isNotBlank),
                excludeKeywords = excludes.split(',', '，').map(String::trim).filter(String::isNotBlank),
                includeRegex = regex.trim(), replyContent = reply.trim(),
                rateLimitMinutes = cooldown.toIntOrNull()?.coerceAtLeast(0) ?: 1440,
                delaySeconds = delay.toIntOrNull()?.coerceIn(0, 300) ?: 3,
                simScope = simScope
            ))
        }) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

@Composable
fun MissedCallEmbeddedScreen() {
    val context = LocalContext.current
    val cfg = remember { CallForwardConfig(context) }
    val channelRepository = remember { ChannelRepository.getInstance(context) }
    val allInstances by channelRepository.instancesFlow.collectAsState()
    val realInstances = allInstances.filterNot { it.id.startsWith("catalog:") }
    var enabled by remember { mutableStateOf(cfg.enabled) }
    var missedOnly by remember { mutableStateOf(cfg.missedCallOnly) }
    var answered by remember { mutableStateOf(cfg.forwardAnsweredCall) }
    var selectedIds by remember { mutableStateOf(cfg.channelInstanceIds) }
    var hasSelection by remember { mutableStateOf(cfg.hasChannelSelection) }
    var choosingChannels by remember { mutableStateOf(false) }
    val hasUnavailableSelection = hasSelection && selectedIds.any { selectedId ->
        realInstances.none { it.id == selectedId && it.enabled && it.hasDispatchConfiguration() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { FeatureCard {
            SettingSwitch("来电提醒", "将来电记录发送到指定推送通道", enabled) { checked ->
                if (checked && hasSelection && selectedIds.isEmpty()) choosingChannels = true
                else { enabled = checked; cfg.enabled = checked }
            }
            SettingSwitch("仅未接来电", "忽略已接听和主动拒接的来电", missedOnly) { missedOnly = it; cfg.missedCallOnly = it }
            SettingSwitch("已接来电也提醒", "接听后的通话记录也发送提醒", answered) { answered = it; cfg.forwardAnsweredCall = it }
        } }
        item { FeatureCard {
            Text("发送通道", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                if (!hasSelection) "全部已启用通道（兼容模式）" else realInstances.filter { it.id in selectedIds }.joinToString("、") { it.name }.ifBlank { "尚未选择通道" },
                fontSize = 12.sp,
                color = TextSecondary
            )
            if (hasUnavailableSelection) {
                Text("部分已选通道已停用、删除或配置不完整", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = { choosingChannels = true }, modifier = Modifier.fillMaxWidth()) { Text("选择通道实例") }
        } }
        item {
            Button(
                onClick = {
                    val selectedInstances = realInstances.filter { instance ->
                        instance.enabled && instance.hasDispatchConfiguration() &&
                            (!hasSelection || instance.id in selectedIds)
                    }
                    val selectedInstanceTypes = selectedInstances.mapTo(mutableSetOf()) { it.channelType }
                    val legacyChannels = if (hasSelection) {
                        emptySet()
                    } else {
                        MultiForwardConfig(context).enabledChannelIds().filterNotTo(mutableSetOf()) {
                            it in selectedInstanceTypes
                        }
                    }
                    if (selectedInstances.isEmpty() && legacyChannels.isEmpty()) {
                        android.widget.Toast.makeText(
                            context,
                            "请先选择并启用至少一个发送通道",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        val now = System.currentTimeMillis()
                        val testBody = "【未接来电提醒·模拟测试】\n来电号码：10086（中国移动客服）\n响铃时长：25秒\n发生时间：刚刚\n接收卡槽：SIM 1"
                        legacyChannels.forEach { channel ->
                            MultiChannelForwardWorker.enqueueSingle(
                                context = context,
                                sender = "10086",
                                body = testBody,
                                receivedAt = now,
                                subscriptionId = -1,
                                uniqueId = "test-call-$now-$channel",
                                targetChannel = channel,
                                allowedChannels = setOf(channel),
                                isTest = true
                            )
                        }
                        selectedInstances.forEach { instance ->
                            MultiChannelForwardWorker.enqueueSingle(
                                context = context,
                                sender = "10086",
                                body = testBody,
                                receivedAt = now,
                                subscriptionId = -1,
                                uniqueId = "test-call-$now-${instance.id}",
                                targetChannel = instance.channelType,
                                targetInstanceId = instance.id,
                                allowedChannels = setOf(instance.id),
                                isTest = true
                            )
                        }
                        android.widget.Toast.makeText(
                            context,
                            "来电提醒模拟测试已提交",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
            ) {
                Text("模拟测试")
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
    if (choosingChannels) {
        var selected by remember(choosingChannels) {
            mutableStateOf(
                if (hasSelection) selectedIds
                else realInstances.filter { it.enabled && it.hasDispatchConfiguration() }.map { it.id }.toSet()
            )
        }
        AlertDialog(
            onDismissRequest = { choosingChannels = false },
            title = { Text("选择来电提醒通道") },
            text = { LazyColumn {
                if (realInstances.isEmpty()) item { Text("请先添加并配置推送通道", color = TextSecondary) }
                items(realInstances, key = { it.id }) { instance ->
                    val usable = instance.enabled && instance.hasDispatchConfiguration()
                    val canToggle = usable || instance.id in selected
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = canToggle) {
                            selected = if (instance.id in selected) selected - instance.id else selected + instance.id
                        }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = instance.id in selected,
                            enabled = canToggle,
                            onCheckedChange = { checked -> selected = if (checked && usable) selected + instance.id else selected - instance.id }
                        )
                        Column {
                            Text(instance.name, fontSize = 13.sp)
                            Text(
                                when {
                                    !instance.enabled -> "已停用"
                                    !instance.hasDispatchConfiguration() -> "配置不完整"
                                    else -> getInstanceSummary(instance)
                                },
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            } },
            confirmButton = { Button(onClick = {
                selectedIds = selected
                hasSelection = true
                cfg.channelInstanceIds = selected
                if (selected.isEmpty()) { enabled = false; cfg.enabled = false }
                choosingChannels = false
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { choosingChannels = false }) { Text("取消") } }
        )
    }
}

@Composable
fun LowBatteryEmbeddedScreen() {
    val context = LocalContext.current
    val cfg = context.config
    val channelRepository = remember { ChannelRepository.getInstance(context) }
    val allInstances by channelRepository.instancesFlow.collectAsState()
    val realInstances = allInstances.filterNot { it.id.startsWith("catalog:") }
    var enabled by remember { mutableStateOf(cfg.enableLowBatteryReminder) }
    var threshold by remember { mutableStateOf(cfg.lowBatteryThreshold) }
    var selectedIds by remember { mutableStateOf(cfg.lowBatteryChannelInstanceIds) }
    var hasSelection by remember { mutableStateOf(cfg.hasLowBatteryInstanceSelection) }
    var choosingChannels by remember { mutableStateOf(false) }
    val hasUnavailableSelection = hasSelection && selectedIds.any { selectedId ->
        realInstances.none { it.id == selectedId && it.enabled && it.hasDispatchConfiguration() }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { FeatureCard {
            SettingSwitch("电量提醒", "电量低于阈值时发送一次提醒", enabled) { checked ->
                if (checked && hasSelection && selectedIds.isEmpty()) choosingChannels = true else {
                    enabled = checked; cfg.enableLowBatteryReminder = checked
                    if (!checked) cfg.lowBatteryLastNotifiedLevel = -1
                    LowBatteryCheckWorker.sync(context.applicationContext)
                }
            }
            Text("提醒电量：$threshold%", fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Slider(value = threshold.toFloat(), onValueChange = { threshold = it.toInt() }, onValueChangeFinished = {
                cfg.lowBatteryThreshold = threshold; cfg.lowBatteryLastNotifiedLevel = -1
            }, valueRange = 5f..50f, steps = 44)
        } }
        item { FeatureCard {
            Text("发送通道", fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                if (!hasSelection) "沿用经典版通道选择" else realInstances.filter { it.id in selectedIds }.joinToString("、") { it.name }.ifBlank { "尚未选择通道" },
                fontSize = 12.sp,
                color = TextSecondary
            )
            if (hasUnavailableSelection) {
                Text("部分已选通道已停用、删除或配置不完整", fontSize = 11.sp, color = MaterialTheme.colorScheme.error)
            }
            OutlinedButton(onClick = { choosingChannels = true }, modifier = Modifier.fillMaxWidth()) { Text("选择通道实例") }
        } }
        item { Spacer(Modifier.height(100.dp)) }
    }
    if (choosingChannels) {
        var selected by remember(choosingChannels) {
            mutableStateOf(
                if (hasSelection) selectedIds
                else realInstances.filter { it.enabled && it.hasDispatchConfiguration() }.map { it.id }.toSet()
            )
        }
        AlertDialog(
            onDismissRequest = { choosingChannels = false },
            title = { Text("选择发送通道") },
            text = { LazyColumn {
                if (realInstances.isEmpty()) item { Text("请先添加并配置推送通道", color = TextSecondary) }
                items(realInstances, key = { it.id }) { instance ->
                    val usable = instance.enabled && instance.hasDispatchConfiguration()
                    val canToggle = usable || instance.id in selected
                    Row(Modifier.fillMaxWidth().clickable(enabled = canToggle) { selected = if (instance.id in selected) selected - instance.id else selected + instance.id }.padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = instance.id in selected, enabled = canToggle, onCheckedChange = { checked -> selected = if (checked && usable) selected + instance.id else selected - instance.id })
                        Column {
                            Text(instance.name, fontSize = 13.sp)
                            Text(
                                when {
                                    !instance.enabled -> "已停用"
                                    !instance.hasDispatchConfiguration() -> "配置不完整"
                                    else -> getInstanceSummary(instance)
                                },
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }
                }
            } },
            confirmButton = { Button(onClick = {
                selectedIds = selected
                hasSelection = true
                cfg.lowBatteryChannelInstanceIds = selected
                if (selected.isEmpty()) { enabled = false; cfg.enableLowBatteryReminder = false }
                else if (!enabled) { enabled = true; cfg.enableLowBatteryReminder = true }
                LowBatteryCheckWorker.sync(context.applicationContext); choosingChannels = false
            }) { Text("确定") } },
            dismissButton = { TextButton(onClick = { choosingChannels = false }) { Text("取消") } }
        )
    }
}

@Composable
fun AutofillEmbeddedScreen() {
    val context = LocalContext.current
    val cfg = remember { AutofillConfig(context) }
    var enabled by remember { mutableStateOf(cfg.enabled) }
    var copyToClipboard by remember { mutableStateOf(cfg.copyToClipboard) }
    var floatingPill by remember { mutableStateOf(cfg.enableFloatingPill) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            FeatureCard {
                SettingSwitch("验证码写入", "识别短信验证码并写入系统剪贴板", enabled) {
                    enabled = it
                    cfg.enabled = it
                }
                SettingSwitch("写入剪贴板", "识别成功后可直接在其他应用粘贴", copyToClipboard) {
                    copyToClipboard = it
                    cfg.copyToClipboard = it
                }
                SettingSwitch("悬浮胶囊", "在其他应用上层显示验证码提示", floatingPill) { checked ->
                    floatingPill = checked
                    cfg.enableFloatingPill = checked
                    if (checked && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
                        !android.provider.Settings.canDrawOverlays(context)
                    ) {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}")
                            )
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
}

@Composable
fun HeartbeatEmbeddedScreen() {
    val context = LocalContext.current
    val cfg = remember { HeartbeatConfig(context) }
    var enabled by remember { mutableStateOf(cfg.enabled) }
    var intervalHours by remember { mutableStateOf(cfg.intervalHours) }
    val channelConfig = remember { MultiForwardConfig(context) }
    val channelRepository = remember { ChannelRepository.getInstance(context) }
    val allInstances by channelRepository.instancesFlow.collectAsState()
    val enabledInstances = allInstances.filter {
        it.enabled && !it.id.startsWith("catalog:") && it.hasDispatchConfiguration()
    }
    val realInstances = allInstances.filterNot { it.id.startsWith("catalog:") }
    var selectedIds by remember { mutableStateOf(cfg.channelInstanceIds) }
    var hasSelection by remember { mutableStateOf(cfg.hasChannelSelection) }
    var choosingChannels by remember { mutableStateOf(false) }
    val hasUnavailableSelection = hasSelection && selectedIds.any { selectedId ->
        realInstances.none { it.id == selectedId && it.enabled && it.hasDispatchConfiguration() }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            FeatureCard {
                SettingSwitch("定时心跳", "周期发送设备状态，确认手机仍在线", enabled) { checked ->
                    val hasTarget = if (hasSelection) {
                        enabledInstances.any { it.id in selectedIds }
                    } else {
                        enabledInstances.isNotEmpty() || channelConfig.enabledChannelIds().isNotEmpty()
                    }
                    if (checked && !hasTarget) {
                        android.widget.Toast.makeText(context, "请先启用至少一个发送通道", android.widget.Toast.LENGTH_SHORT).show()
                    } else {
                        enabled = checked
                        cfg.enabled = checked
                        HeartbeatWorker.sync(context.applicationContext)
                    }
                }
                Text("发送周期：每 $intervalHours 小时", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    listOf(1, 3, 6, 12, 24).forEach { hours ->
                        OutlinedButton(
                            onClick = {
                                intervalHours = hours
                                cfg.intervalHours = hours
                                if (enabled) HeartbeatWorker.sync(context.applicationContext)
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp),
                            border = BorderStroke(1.dp, if (intervalHours == hours) BrandGreen else OutlineSoft)
                        ) {
                            Text("$hours", fontSize = 11.sp, color = if (intervalHours == hours) BrandGreen else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
                Text("发送通道", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (!hasSelection) "全部已启用通道（兼容模式）"
                    else realInstances.filter { it.id in selectedIds }.joinToString("、") { it.name }
                        .ifBlank { "尚未选择通道" },
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                if (hasUnavailableSelection) {
                    Text(
                        "部分已选通道已停用或删除；心跳仅发送到仍可用的实例",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                OutlinedButton(
                    onClick = { choosingChannels = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("选择通道实例") }
                Button(
                    onClick = {
                        val targetInstances = enabledInstances.filter { !hasSelection || it.id in selectedIds }
                        val instanceTypes = targetInstances.mapTo(mutableSetOf()) { it.channelType }
                        val legacyChannels = if (hasSelection) emptySet() else {
                            channelConfig.enabledChannelIds().filterNotTo(mutableSetOf()) { it in instanceTypes }
                        }
                        if (targetInstances.isEmpty() && legacyChannels.isEmpty()) {
                            android.widget.Toast.makeText(
                                context,
                                if (hasSelection) {
                                    "已选择的心跳通道均已停用或删除，请重新选择"
                                } else {
                                    "请先启用至少一个发送通道"
                                },
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            val now = System.currentTimeMillis()
                            val uptimeMillis = android.os.SystemClock.elapsedRealtime()
                            val uptimeHours = java.util.concurrent.TimeUnit.MILLISECONDS.toHours(uptimeMillis)
                            val rx = android.net.TrafficStats.getTotalRxBytes()
                            val tx = android.net.TrafficStats.getTotalTxBytes()
                            val traffic = if (rx < 0L || tx < 0L) {
                                "设备不支持统计"
                            } else {
                                "接收 ${formatHeartbeatBytes(rx)} · 发送 ${formatHeartbeatBytes(tx)}"
                            }
                            val body = buildString {
                                appendLine("【设备心跳·模拟测试】")
                                appendLine("设备机型：${TemplateDataRetriever.getDeviceName()}")
                                appendLine("电池状态：${TemplateDataRetriever.getBatteryInfo(context)}")
                                appendLine("网络环境：${TemplateDataRetriever.getNetworkType(context)}")
                                appendLine("累计流量：$traffic")
                                appendLine("运行时间：${uptimeHours / 24}天 ${uptimeHours % 24}小时")
                                append("设备时间：${TemplateDataRetriever.getCurrentTime()}")
                            }
                            targetInstances.forEach { instance ->
                                MultiChannelForwardWorker.enqueueSingle(
                                    context = context,
                                    sender = "设备心跳",
                                    body = body,
                                    receivedAt = now,
                                    subscriptionId = -1,
                                    uniqueId = "test-heartbeat-$now-${instance.id}",
                                    targetChannel = instance.channelType,
                                    targetInstanceId = instance.id,
                                    allowedChannels = setOf(instance.id),
                                    isTest = true
                                )
                            }
                            legacyChannels.forEach { channel ->
                                MultiChannelForwardWorker.enqueueSingle(
                                    context = context,
                                    sender = "设备心跳",
                                    body = body,
                                    receivedAt = now,
                                    subscriptionId = -1,
                                    uniqueId = "test-heartbeat-$now-$channel",
                                    targetChannel = channel,
                                    allowedChannels = setOf(channel),
                                    isTest = true
                                )
                            }
                            android.widget.Toast.makeText(context, "心跳模拟测试已提交", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
                ) {
                    Text("模拟测试")
                }
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
    if (choosingChannels) {
        var selected by remember(choosingChannels) {
            mutableStateOf(if (hasSelection) selectedIds else enabledInstances.map { it.id }.toSet())
        }
        AlertDialog(
            onDismissRequest = { choosingChannels = false },
            title = { Text("选择心跳发送通道") },
            text = {
                LazyColumn {
                    if (realInstances.isEmpty()) {
                        item { Text("请先添加并配置推送通道", color = TextSecondary) }
                    }
                    items(realInstances, key = { it.id }) { instance ->
                        val usable = instance.enabled && instance.hasDispatchConfiguration()
                        val canToggle = usable || instance.id in selected
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(enabled = canToggle) {
                                    selected = if (instance.id in selected) selected - instance.id else selected + instance.id
                                }
                                .padding(vertical = 5.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = instance.id in selected,
                                enabled = canToggle,
                                onCheckedChange = { checked ->
                                    selected = if (checked && usable) selected + instance.id else selected - instance.id
                                }
                            )
                            Column {
                                Text(instance.name, fontSize = 13.sp)
                                Text(
                                    when {
                                        !instance.enabled -> "已停用"
                                        !instance.hasDispatchConfiguration() -> "配置不完整"
                                        else -> getInstanceSummary(instance)
                                    },
                                    fontSize = 11.sp,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    selectedIds = selected
                    hasSelection = true
                    cfg.channelInstanceIds = selected
                    if (selected.isEmpty()) {
                        enabled = false
                        cfg.enabled = false
                    }
                    HeartbeatWorker.sync(context.applicationContext)
                    choosingChannels = false
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { choosingChannels = false }) { Text("取消") }
            }
        )
    }
}

private fun formatHeartbeatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return String.format(java.util.Locale.getDefault(), "%.1f %s", value, units[unitIndex])
}

@Composable
fun ClassicFeatureEntryScreen(
    title: String,
    description: String,
    buttonText: String,
    activityClass: Class<*>,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            FeatureCard {
                Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(description, fontSize = 12.sp, color = TextSecondary)
                Button(
                    onClick = {
                        context.startActivity(android.content.Intent(context, activityClass))
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = BrandGreen)
                ) {
                    Text(buttonText)
                }
            }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }
}
