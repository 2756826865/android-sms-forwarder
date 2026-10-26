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
internal data class Appearance(val mode: Int = 0, val dynamic: Boolean = false, val accent: Int = 0, val amoled: Boolean = false, val blur: Boolean = false, val keyColor: Int = 0, val palette: String = "TonalSpot", val spec2025: Boolean = true, val floating: Boolean = true, val material: Boolean = false)

@Composable
fun AppearanceSettingsButton(embedded: Boolean = false) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val value = LocalAppearance.current
    val update = LocalSetAppearance.current
    var hex by remember(value.keyColor) { mutableStateOf(if (value.keyColor == 0) "" else "%06X".format(value.keyColor and 0xFFFFFF)) }
    val parsed = hex.removePrefix("#").takeIf { it.length == 6 }?.toLongOrNull(16)?.toInt()
    val editor: @Composable () -> Unit = {
            Column(if (embedded) Modifier else Modifier.verticalScroll(rememberScrollState())) {
                top.yukonga.miuix.kmp.basic.Card(Modifier.fillMaxWidth()) {
                    top.yukonga.miuix.kmp.preference.OverlayDropdownPreference(
                        title = "明暗模式", items = listOf("跟随系统", "浅色", "深色"),
                        selectedIndex = value.mode.coerceIn(0, 2), onSelectedIndexChange = { update(value.copy(mode = it)) })
                    top.yukonga.miuix.kmp.preference.SwitchPreference(title = "纯黑背景", summary = "在深色模式下使用纯黑页面背景",
                        checked = value.amoled, onCheckedChange = { update(value.copy(amoled = it)) })
                    top.yukonga.miuix.kmp.preference.SwitchPreference(title = "系统动态配色", summary = "跟随系统壁纸配色，需要 Android 12 及以上",
                        checked = value.dynamic, enabled = android.os.Build.VERSION.SDK_INT >= 31,
                        onCheckedChange = { update(value.copy(dynamic = it, keyColor = 0)) })
                    top.yukonga.miuix.kmp.preference.SwitchPreference(title = "液态玻璃胶囊", summary = "模糊、折射和按压高光，需要 Android 13 及以上",
                        checked = value.blur, enabled = android.os.Build.VERSION.SDK_INT >= 33,
                        onCheckedChange = { update(value.copy(blur = it)) })
                }
                top.yukonga.miuix.kmp.basic.Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                    top.yukonga.miuix.kmp.preference.OverlayDropdownPreference(
                        title = "预设主题色", items = listOf("绿色", "蓝色", "紫色"),
                        selectedIndex = value.accent.coerceIn(0, 2),
                        onSelectedIndexChange = { update(value.copy(accent = it, dynamic = false, keyColor = 0)) })
                    val palettes = top.yukonga.miuix.kmp.theme.ThemePaletteStyle.entries
                    top.yukonga.miuix.kmp.preference.OverlayDropdownPreference(
                        title = "调色板风格", items = palettes.map { it.name },
                        selectedIndex = palettes.indexOfFirst { it.name == value.palette }.coerceAtLeast(0),
                        onSelectedIndexChange = { update(value.copy(palette = palettes[it].name)) })
                    top.yukonga.miuix.kmp.preference.OverlayDropdownPreference(
                        title = "色彩规范", items = listOf("2021", "2025"), selectedIndex = if (value.spec2025) 1 else 0,
                        onSelectedIndexChange = { update(value.copy(spec2025 = it == 1)) })
                }
                OutlinedTextField(value = hex, onValueChange = { hex = it.take(7) },
                    label = { Text("自定义颜色 RRGGBB") }, singleLine = true,
                    isError = hex.isNotEmpty() && parsed == null)
                TextButton(enabled = parsed != null, onClick = {
                    parsed?.let { update(value.copy(keyColor = it or 0xFF000000.toInt(), dynamic = false)) }
                }) { Text("应用自定义颜色") }
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
