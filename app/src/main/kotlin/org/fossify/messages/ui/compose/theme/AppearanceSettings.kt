package org.fossify.messages.ui.compose.theme

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier

internal val LocalAppearance = staticCompositionLocalOf { Appearance() }
internal val LocalSetAppearance = staticCompositionLocalOf<(Appearance) -> Unit> { {} }
internal data class Appearance(val mode: Int = 0, val dynamic: Boolean = false, val accent: Int = 0, val amoled: Boolean = false, val blur: Boolean = false, val keyColor: Int = 0, val palette: String = "TonalSpot", val spec2025: Boolean = true)

@Composable
fun AppearanceSettingsButton(embedded: Boolean = false) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val value = LocalAppearance.current
    val update = LocalSetAppearance.current
    var hex by remember(value.keyColor) { mutableStateOf(if (value.keyColor == 0) "" else "%06X".format(value.keyColor and 0xFFFFFF)) }
    val parsed = hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.toInt()
    val editor: @Composable () -> Unit = {
            Column(if (embedded) Modifier else Modifier.verticalScroll(rememberScrollState())) {
                listOf("跟随系统", "浅色", "深色").forEachIndexed { index, label ->
                    TextButton(onClick = { update(value.copy(mode = index)) }) {
                        Text(if (value.mode == index) "✓ $label" else label)
                    }
                }
                TextButton(onClick = { update(value.copy(amoled = !value.amoled)) }) {
                    Text("纯黑深色背景：${if (value.amoled) "开" else "关"}")
                }
                if (android.os.Build.VERSION.SDK_INT >= 31) {
                    TextButton(onClick = { update(value.copy(dynamic = !value.dynamic)) }) {
                        Text("系统动态配色：${if (value.dynamic) "开" else "关"}")
                    }
                }
                TextButton(onClick = { update(value.copy(blur = !value.blur)) }) {
                    Text("胶囊液态玻璃（Android 13+）：${if (value.blur) "开" else "关"}")
                }
                listOf("绿色", "蓝色", "紫色").forEachIndexed { index, label ->
                    TextButton(onClick = { update(value.copy(accent = index, dynamic = false, keyColor = 0)) }) {
                        Text(if (!value.dynamic && value.accent == index) "✓ $label" else label)
                    }
                }
                OutlinedTextField(value = hex, onValueChange = { hex = it.take(7) },
                    label = { Text("自定义颜色 RRGGBB") }, singleLine = true,
                    isError = hex.isNotEmpty() && parsed == null)
                TextButton(enabled = parsed != null, onClick = {
                    parsed?.let { update(value.copy(keyColor = it or 0xFF000000.toInt(), dynamic = false)) }
                }) { Text("应用自定义颜色") }
                Text("调色板风格")
                top.yukonga.miuix.kmp.theme.ThemePaletteStyle.entries.forEach { style ->
                    TextButton(onClick = { update(value.copy(palette = style.name)) }) {
                        Text(if (value.palette == style.name) "✓ ${style.name}" else style.name)
                    }
                }
                TextButton(onClick = { update(value.copy(spec2025 = !value.spec2025)) }) {
                    Text("色彩规范：${if (value.spec2025) "2025" else "2021"}")
                }
                TextButton(onClick = { update(Appearance()) }) { Text("恢复默认外观") }
                Text("设置立即保存。部分页面仍使用固定配色，尚未全部适配。")
            }
        }
    if (embedded) {
        editor()
    } else {
    OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 6.dp)) {
            Text("外观与主题")
            Text("${listOf("跟随系统", "浅色", "深色").getOrElse(value.mode) { "跟随系统" }} · ${if (value.dynamic) "系统动态配色" else "自定义配色"} · ${if (value.blur) "玻璃胶囊" else "实色胶囊"}",
                style = MaterialTheme.typography.bodySmall)
        }
    }
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Text("外观与主题") },
        text = editor,
        confirmButton = { TextButton(onClick = { open = false }) { Text("完成") } }
    )
}
}
