package org.fossify.messages.ui.compose.rules.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fossify.messages.ui.compose.theme.BrandGreen
import org.fossify.messages.ui.compose.theme.DarkOutline
import org.fossify.messages.ui.compose.theme.OutlineSoft
import org.fossify.messages.ui.compose.theme.TextPrimary
import org.fossify.messages.ui.compose.theme.TextSecondary

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DndEditor(
    enabled: Boolean,
    start: String,
    end: String,
    days: List<Int>,
    onUpdate: (enabled: Boolean, start: String, end: String, days: List<Int>) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primaryText = if (isDark) Color.White else TextPrimary
    val secondaryText = if (isDark) Color(0xFF9CA3AF) else TextSecondary
    val borderColor = if (isDark) DarkOutline else OutlineSoft

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "开启规则免打扰时段",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = primaryText
                )
                Text(
                    text = "处于该时段时，规则命中将自动静默跳过发送",
                    fontSize = 12.sp,
                    color = secondaryText
                )
            }

            Switch(
                checked = enabled,
                onCheckedChange = { chk -> onUpdate(chk, start, end, days) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = BrandGreen
                )
            )
        }

        if (enabled) {
            Spacer(modifier = Modifier.height(14.dp))

            // 起止时间输入 (支持跨午夜如 22:00 ~ 06:00)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = start,
                    onValueChange = { onUpdate(enabled, it, end, days) },
                    label = { Text("开始时间") },
                    placeholder = { Text("22:00") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = borderColor
                    )
                )

                OutlinedTextField(
                    value = end,
                    onValueChange = { onUpdate(enabled, start, it, days) },
                    label = { Text("结束时间") },
                    placeholder = { Text("06:00") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = borderColor
                    )
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 生效星期勾选
            Text(text = "生效星期：", fontSize = 13.sp, color = secondaryText)
            Spacer(modifier = Modifier.height(6.dp))

            val weekDayLabels = listOf(
                1 to "周一", 2 to "周二", 3 to "周三",
                4 to "周四", 5 to "周五", 6 to "周六", 7 to "周日"
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                weekDayLabels.forEach { (dayNum, dayLabel) ->
                    val isSelected = days.contains(dayNum)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) BrandGreen else borderColor,
                        modifier = Modifier.clickable {
                            val newDays = if (isSelected) {
                                days - dayNum
                            } else {
                                days + dayNum
                            }
                            onUpdate(enabled, start, end, newDays)
                        }
                    ) {
                        Text(
                            text = dayLabel,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isSelected) Color.White else primaryText,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
