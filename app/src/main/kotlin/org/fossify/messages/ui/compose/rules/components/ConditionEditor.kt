package org.fossify.messages.ui.compose.rules.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import org.fossify.messages.forwarding.ForwardingRuleCondition
import org.fossify.messages.forwarding.RuleOperator
import org.fossify.messages.forwarding.RuleTargetField
import org.fossify.messages.ui.compose.theme.DarkOutline
import org.fossify.messages.ui.compose.theme.DarkSurface
import org.fossify.messages.ui.compose.theme.GatewayRed
import org.fossify.messages.ui.compose.theme.OutlineSoft
import org.fossify.messages.ui.compose.theme.SurfaceCard
import org.fossify.messages.ui.compose.theme.TextPrimary
import org.fossify.messages.ui.compose.theme.TextSecondary

@Composable
fun ConditionRow(
    condition: ForwardingRuleCondition,
    onUpdate: (field: RuleTargetField, operator: RuleOperator, value: String, ignoreCase: Boolean) -> Unit,
    onRemove: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val cardBg = if (isDark) DarkSurface else SurfaceCard
    val borderColor = if (isDark) DarkOutline else OutlineSoft
    val primaryText = if (isDark) Color.White else TextPrimary
    val secondaryText = if (isDark) Color(0xFF9CA3AF) else TextSecondary

    var fieldMenuExpanded by remember { mutableStateOf(false) }
    var opMenuExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = cardBg,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 字段选择 (发件人 / 正文)
                Box {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                        modifier = Modifier.clickable { fieldMenuExpanded = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = condition.field.displayName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
                        }
                    }

                    DropdownMenu(
                        expanded = fieldMenuExpanded,
                        onDismissRequest = { fieldMenuExpanded = false }
                    ) {
                        RuleTargetField.values().forEach { f ->
                            DropdownMenuItem(
                                text = { Text(f.displayName) },
                                onClick = {
                                    fieldMenuExpanded = false
                                    onUpdate(f, condition.operator, condition.value, condition.ignoreCase)
                                }
                            )
                        }
                    }
                }

                // 操作符选择 (包含 / 完全匹配 / 正则匹配)
                Box {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = borderColor,
                        modifier = Modifier.clickable { opMenuExpanded = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = condition.operator.displayName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = primaryText
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(text = "▾", fontSize = 12.sp, color = secondaryText)
                        }
                    }

                    DropdownMenu(
                        expanded = opMenuExpanded,
                        onDismissRequest = { opMenuExpanded = false }
                    ) {
                        RuleOperator.values().forEach { op ->
                            DropdownMenuItem(
                                text = { Text(op.displayName) },
                                onClick = {
                                    opMenuExpanded = false
                                    onUpdate(condition.field, op, condition.value, condition.ignoreCase)
                                }
                            )
                        }
                    }
                }

                IconButton(
                    onClick = onRemove,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = "删除条件",
                        tint = GatewayRed,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedTextField(
                value = condition.value,
                onValueChange = { newVal ->
                    onUpdate(condition.field, condition.operator, newVal, condition.ignoreCase)
                },
                placeholder = {
                    Text(
                        if (condition.operator == RuleOperator.REGEX) "输入正则表达式 (例如: 验证码[:：]?\\s*\\d{4,8})" else "输入匹配关键词或号码"
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = condition.operator != RuleOperator.REGEX,
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = borderColor
                )
            )
        }
    }
}
