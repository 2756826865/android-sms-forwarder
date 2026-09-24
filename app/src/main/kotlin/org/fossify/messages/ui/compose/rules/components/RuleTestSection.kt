package org.fossify.messages.ui.compose.rules.components

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fossify.messages.ui.compose.theme.DarkOutline
import org.fossify.messages.ui.compose.theme.DarkSurface
import org.fossify.messages.ui.compose.theme.GatewayBlue
import org.fossify.messages.ui.compose.theme.GatewayGreen
import org.fossify.messages.ui.compose.theme.OutlineSoft
import org.fossify.messages.ui.compose.theme.SurfaceCard
import org.fossify.messages.ui.compose.theme.TextPrimary
import org.fossify.messages.ui.compose.theme.TextSecondary

@Composable
fun RuleTestSection(
    sender: String,
    body: String,
    simSlot: Int?,
    resultSummary: String?,
    renderedContent: String?,
    onUpdate: (sender: String, body: String, simSlot: Int?) -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val primaryText = if (isDark) Color.White else TextPrimary
    val secondaryText = if (isDark) Color(0xFF9CA3AF) else TextSecondary
    val cardBg = if (isDark) DarkSurface else SurfaceCard
    val borderColor = if (isDark) DarkOutline else OutlineSoft

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "🧪 实时规则匹配与正文渲染测试",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = primaryText
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "输入模拟短信，系统将实时计算匹配结果、过滤分支并展示替换及模板渲染后的最终投递内容",
            fontSize = 12.sp,
            color = secondaryText
        )

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = sender,
            onValueChange = { onUpdate(it, body, simSlot) },
            label = { Text("模拟发件人号码") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = borderColor
            )
        )

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = body,
            onValueChange = { onUpdate(sender, it, simSlot) },
            label = { Text("模拟短信正文") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = borderColor
            )
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = "测试接收卡槽",
            fontSize = 12.sp,
            color = secondaryText
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                0 to "SIM 1",
                1 to "SIM 2",
                null to "未知"
            ).forEach { (slot, label) ->
                val selected = simSlot == slot
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) MaterialTheme.colorScheme.primary else borderColor,
                    modifier = Modifier.weight(1f)
                ) {
                    TextButton(
                        onClick = { onUpdate(sender, body, slot) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            color = if (selected) Color.White else primaryText
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 匹配诊断状态卡
        if (resultSummary != null) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (resultSummary.startsWith("✅")) GatewayGreen.copy(alpha = 0.1f) else borderColor,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = resultSummary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = primaryText,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (!renderedContent.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = cardBg,
                shadowElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text(
                        text = "规则处理后的正文预览：",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = GatewayBlue
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = renderedContent,
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = primaryText
                    )
                }
            }
        }
    }
}
