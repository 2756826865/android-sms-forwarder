package org.fossify.messages.ui.compose.rules.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fossify.messages.R
import org.fossify.messages.forwarding.ChannelRegistry
import org.fossify.messages.forwarding.ForwardingChannelInstance
import org.fossify.messages.forwarding.ForwardingRule
import org.fossify.messages.ui.compose.components.GatewayCard
import org.fossify.messages.ui.compose.theme.BrandGreen
import org.fossify.messages.ui.compose.theme.GatewayBlue
import org.fossify.messages.ui.compose.theme.GatewayGreen
import org.fossify.messages.ui.compose.theme.GatewayOrange
import org.fossify.messages.ui.compose.theme.GatewayPurple
import org.fossify.messages.ui.compose.theme.GatewayRed
import org.fossify.messages.ui.compose.theme.TextPrimary
import org.fossify.messages.ui.compose.theme.TextSecondary
import org.fossify.messages.ui.compose.theme.TextTertiary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleCard(
    rule: ForwardingRule,
    availableInstances: List<ForwardingChannelInstance>,
    onToggleEnabled: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDuplicate: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primaryColor = if (isDark) Color.White else TextPrimary
    val secondaryColor = if (isDark) Color(0xFF9CA3AF) else TextSecondary
    var menuExpanded by remember { mutableStateOf(false) }

    GatewayCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 头部：规则名、启用开关与菜单
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = rule.name.ifBlank { "未命名规则" },
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = primaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = rule.enabled,
                        onCheckedChange = onToggleEnabled,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = BrandGreen
                        ),
                        modifier = Modifier.padding(end = 4.dp)
                    )

                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more),
                                contentDescription = "更多选项",
                                tint = secondaryColor
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("复制规则") },
                                onClick = {
                                    menuExpanded = false
                                    onDuplicate()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("上移") },
                                onClick = {
                                    menuExpanded = false
                                    onMoveUp()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("下移") },
                                onClick = {
                                    menuExpanded = false
                                    onMoveDown()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("删除规则", color = GatewayRed) },
                                onClick = {
                                    menuExpanded = false
                                    onDelete()
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 条件与卡槽摘要
            val simLabel = when (rule.simScope) {
                ForwardingRule.SIM_1 -> "SIM 1"
                ForwardingRule.SIM_2 -> "SIM 2"
                else -> "全部卡槽"
            }

            val conditionSummary = if (rule.conditions.isNotEmpty()) {
                val cond = rule.conditions.first()
                val extra = if (rule.conditions.size > 1) " 等 ${rule.conditions.size} 个条件" else ""
                "${cond.field.displayName}${cond.operator.displayName}'${cond.value}'$extra"
            } else if (rule.includeKeywords.isNotEmpty()) {
                "包含关键词: ${rule.includeKeywords.joinToString(",")}"
            } else {
                "无明确条件 (需配置)"
            }

            Text(
                text = "📍 $simLabel · $conditionSummary",
                fontSize = 13.sp,
                color = secondaryColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 底部属性徽标群
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // 目标实例徽标
                if (rule.actions.isNotEmpty()) {
                    rule.actions.forEach { action ->
                        val inst = availableInstances.firstOrNull { it.id == action.targetInstanceId }
                        val name = when {
                            inst == null -> "已删除实例"
                            !inst.enabled -> "${inst.name}（已停用）"
                            else -> inst.name
                        }
                        val emoji = ChannelRegistry.getIconEmoji(action.channelType)
                        RuleChip(
                            label = "$emoji $name",
                            color = if (inst?.enabled == true) GatewayBlue else GatewayOrange
                        )
                    }
                } else if (rule.channels.isNotEmpty()) {
                    rule.channels.forEach { ch ->
                        val name = ChannelRegistry.getDisplayName(ch)
                        val emoji = ChannelRegistry.getIconEmoji(ch)
                        RuleChip(label = "$emoji $name", color = GatewayBlue)
                    }
                } else {
                    RuleChip(label = "⚠️ 未选择通道实例", color = GatewayOrange)
                }

                // 模板状态
                if (rule.customTemplate.isNotBlank()) {
                    RuleChip(label = "🛠️ 自定义模板", color = GatewayPurple)
                }

                // 正则替换
                if (rule.regexReplacements.isNotEmpty()) {
                    RuleChip(label = "🔤 ${rule.regexReplacements.size}组正则", color = GatewayGreen)
                }

                // 免打扰
                if (rule.doNotDisturbEnabled) {
                    RuleChip(label = "🌙 ${rule.dndStart}~${rule.dndEnd}", color = TextTertiary)
                }
            }
        }
    }
}

@Composable
fun RuleChip(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color
    ) {
        Text(
            text = label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}
