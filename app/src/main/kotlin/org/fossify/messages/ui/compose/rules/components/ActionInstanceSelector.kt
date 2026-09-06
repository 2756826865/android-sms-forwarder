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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fossify.messages.R
import org.fossify.messages.forwarding.ChannelRegistry
import org.fossify.messages.forwarding.ForwardingChannelInstance
import org.fossify.messages.forwarding.ForwardingRuleAction
import org.fossify.messages.ui.compose.theme.BrandGreen
import org.fossify.messages.ui.compose.theme.DarkOutline
import org.fossify.messages.ui.compose.theme.DarkSurface
import org.fossify.messages.ui.compose.theme.GatewayBlue
import org.fossify.messages.ui.compose.theme.GatewayOrange
import org.fossify.messages.ui.compose.theme.GatewayRed
import org.fossify.messages.ui.compose.theme.OutlineSoft
import org.fossify.messages.ui.compose.theme.SurfaceCard
import org.fossify.messages.ui.compose.theme.TextPrimary
import org.fossify.messages.ui.compose.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ActionInstanceSelector(
    actions: List<ForwardingRuleAction>,
    availableInstances: List<ForwardingChannelInstance>,
    onAddAction: (instanceId: String) -> Unit,
    onRemoveAction: (actionId: String) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primaryText = if (isDark) Color.White else TextPrimary
    val secondaryText = if (isDark) Color(0xFF9CA3AF) else TextSecondary
    val cardBg = if (isDark) DarkSurface else SurfaceCard
    val borderColor = if (isDark) DarkOutline else OutlineSoft

    var pickerExpanded by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (availableInstances.isEmpty()) {
            // 没有已保存实例：强提示引导添加
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = GatewayOrange.copy(alpha = 0.1f),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "⚠️ 尚未添加任何发送通道实例",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = GatewayOrange
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "本系统遵循「用户主动加入」原则，规则无法发送到未配置的通道。请先在「通道」标签页中添加并保存您的具体机器人/Webhook，然后在此处勾选。",
                        fontSize = 13.sp,
                        color = secondaryText
                    )
                }
            }
        } else {
            // 已选中的动作实例列表
            if (actions.isNotEmpty()) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    actions.forEach { action ->
                        val inst = availableInstances.firstOrNull { it.id == action.targetInstanceId }
                        val name = inst?.name ?: "已删除的通道实例"
                        val emoji = ChannelRegistry.getIconEmoji(action.channelType)

                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = cardBg,
                            shadowElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(text = emoji, fontSize = 18.sp)
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column {
                                        Text(
                                            text = name,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = primaryText
                                        )
                                        Text(
                                            text = ChannelRegistry.getDisplayName(action.channelType),
                                            fontSize = 12.sp,
                                            color = secondaryText
                                        )
                                        if (inst == null || !inst.enabled) {
                                            Text(
                                                text = if (inst == null) "关联已失效，请移除" else "已停用，执行时将跳过",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Medium,
                                                color = GatewayOrange
                                            )
                                        }
                                    }
                                }

                                IconButton(
                                    onClick = { onRemoveAction(action.id) },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = "移除目标",
                                        tint = GatewayRed,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(10.dp))
            }

            // 选择添加实例按钮
            Box {
                OutlinedButton(
                    onClick = { pickerExpanded = true },
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_plus),
                        contentDescription = "选择发送通道实例",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "添加发送通道实例", fontSize = 14.sp)
                }

                DropdownMenu(
                    expanded = pickerExpanded,
                    onDismissRequest = { pickerExpanded = false }
                ) {
                    val unselected = availableInstances.filter { inst ->
                        actions.none { it.targetInstanceId == inst.id }
                    }
                    if (unselected.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text("已选完所有通道实例", color = secondaryText) },
                            onClick = { pickerExpanded = false }
                        )
                    } else {
                        unselected.forEach { inst ->
                            val emoji = ChannelRegistry.getIconEmoji(inst.channelType)
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(text = emoji, fontSize = 16.sp)
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = if (inst.enabled) inst.name else "${inst.name}（已停用）",
                                                fontWeight = FontWeight.Medium,
                                                color = if (inst.enabled) primaryText else GatewayOrange
                                            )
                                            Text(
                                                text = ChannelRegistry.getDisplayName(inst.channelType),
                                                fontSize = 11.sp,
                                                color = secondaryText
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    pickerExpanded = false
                                    onAddAction(inst.id)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
