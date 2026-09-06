package org.fossify.messages.ui.compose.rules

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fossify.messages.R
import org.fossify.messages.forwarding.repository.ChannelRepository
import org.fossify.messages.forwarding.repository.RuleRepository
import org.fossify.messages.ui.compose.rules.components.RuleCard
import org.fossify.messages.ui.compose.theme.AppBackground
import org.fossify.messages.ui.compose.theme.BrandGreen
import org.fossify.messages.ui.compose.theme.BrandGreenSoft
import org.fossify.messages.ui.compose.theme.DarkBackground
import org.fossify.messages.ui.compose.theme.GatewayRed
import org.fossify.messages.ui.compose.theme.TextPrimary
import org.fossify.messages.ui.compose.theme.TextSecondary
import org.fossify.messages.ui.compose.theme.TextTertiary

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RuleManagementScreen(
    onNavigateBack: (() -> Unit)? = null,
    onNavigateToEditor: (ruleId: String?) -> Unit
) {
    val context = LocalContext.current
    val repository = remember { RuleRepository.getInstance(context) }
    val channelRepository = remember { ChannelRepository.getInstance(context) }
    val rules by repository.rulesFlow.collectAsState()
    val channelInstances by channelRepository.instancesFlow.collectAsState()
    // 列表展示全部实例；停用实例仍需用于解析规则中的稳定 instanceId。
    val availableInstances = channelInstances

    var isRulesEnabled by remember { mutableStateOf(repository.isRulesEnabled()) }
    var ruleToDelete by remember { mutableStateOf<String?>(null) }

    val isDark = isSystemInDarkTheme()
    val pageBg = if (isDark) DarkBackground else AppBackground
    val primaryText = if (isDark) Color.White else TextPrimary
    val secondaryText = if (isDark) Color(0xFF9CA3AF) else TextSecondary

    Scaffold(
        containerColor = pageBg,
        floatingActionButton = {
            if (rules.isNotEmpty()) {
                FloatingActionButton(
                    onClick = { onNavigateToEditor(null) },
                    containerColor = BrandGreen,
                    contentColor = Color.White,
                    shape = CircleShape,
                    modifier = Modifier.padding(bottom = 76.dp) // 预留底部浮动导航栏避让
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_plus),
                        contentDescription = "新建规则",
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .statusBarsPadding()
        ) {
            // 顶栏与全局开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (onNavigateBack != null) {
                        TextButton(onClick = onNavigateBack) {
                            Text("‹ 通道", color = BrandGreen, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Column {
                    Text(
                        text = "规则管理",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = primaryText
                    )
                    Text(
                        text = if (isRulesEnabled) "符合条件的短信将发送到指定通道" else "规则系统未启用 · 所有短信按通道配置发送",
                        fontSize = 12.sp,
                        color = secondaryText
                    )
                    }
                }

                Switch(
                    checked = isRulesEnabled,
                    onCheckedChange = { checked ->
                        isRulesEnabled = checked
                        repository.setRulesEnabled(checked)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = BrandGreen
                    )
                )
            }

            if (rules.isEmpty()) {
                // 空状态：没有规则时显示规范引导
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(start = 24.dp, top = 32.dp, end = 24.dp, bottom = 120.dp),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Top
                    ) {
                        Text(
                            text = "尚未创建规则",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = primaryText
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "创建规则后，符合条件的短信才会发送到所选通道",
                            fontSize = 13.sp,
                            color = secondaryText,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 20.dp)
                        )

                        Spacer(modifier = Modifier.height(28.dp))

                        Button(
                            onClick = { onNavigateToEditor(null) },
                            colors = ButtonDefaults.buttonColors(containerColor = BrandGreen),
                            shape = RoundedCornerShape(24.dp),
                            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 14.dp)
                        ) {
                            Text(text = "新建规则", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            } else {
                // 规则列表：必须留足底部 120dp 间距，严防悬浮胶囊底栏遮挡
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(rules, key = { it.id }) { rule ->
                        RuleCard(
                            rule = rule,
                            availableInstances = availableInstances,
                            onToggleEnabled = { enabled ->
                                repository.saveRule(rule.copy(enabled = enabled))
                            },
                            onClick = { onNavigateToEditor(rule.id) },
                            onDuplicate = {
                                repository.duplicateRule(rule.id)
                            },
                            onMoveUp = {
                                repository.moveRule(rule.id, -1)
                            },
                            onMoveDown = {
                                repository.moveRule(rule.id, 1)
                            },
                            onDelete = {
                                ruleToDelete = rule.id
                            }
                        )
                    }
                }
            }
        }
    }

    // 删除二次确认 Dialog
    if (ruleToDelete != null) {
        AlertDialog(
            onDismissRequest = { ruleToDelete = null },
            title = { Text("确认删除规则？") },
            text = { Text("删除后无法恢复，该规则关联的通道将不再接收此类过滤消息。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        ruleToDelete?.let { repository.deleteRule(it) }
                        ruleToDelete = null
                    }
                ) {
                    Text("删除", color = GatewayRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { ruleToDelete = null }) {
                    Text("取消")
                }
            }
        )
    }
}
