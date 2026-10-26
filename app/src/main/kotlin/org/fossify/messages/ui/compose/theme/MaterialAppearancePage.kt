// Uses the directly ported KernelSU v3.3.1 Material segmented controls (GPL-3.0-or-later).
package org.fossify.messages.ui.compose.theme

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.fossify.messages.ui.compose.diagnostics.KsuSettingsPage
import org.fossify.messages.ui.compose.ksu.material.*

@Composable
internal fun MaterialAppearancePage(onBack: () -> Unit) {
    val value = LocalAppearance.current
    val update = LocalSetAppearance.current
    KsuSettingsPage("主题设置", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Spacer(Modifier.height(32.dp))
            ThemePreviewCard(keyColor = value.keyColor, isDark = org.fossify.messages.ui.compose.ksu.isInDarkTheme(),
                isAmoled = value.amoled,
                paletteStyle = com.materialkolor.PaletteStyle.entries.firstOrNull { it.name == value.palette } ?: com.materialkolor.PaletteStyle.TonalSpot,
                colorSpec = if (value.spec2025) com.materialkolor.dynamiccolor.ColorSpec.SpecVersion.SPEC_2025 else com.materialkolor.dynamiccolor.ColorSpec.SpecVersion.SPEC_2021)
            Spacer(Modifier.height(72.dp))
            SegmentedColumn(content = listOf(
                { SegmentedDropdownItem(title = "明暗模式", items = listOf("跟随系统", "浅色", "深色"), selectedIndex = value.mode.coerceIn(0,2), onItemSelected = { update(value.copy(mode = it)) }) },
                { SegmentedSwitchItem(title = "纯黑背景", checked = value.amoled, onCheckedChange = { update(value.copy(amoled = it)) }) },
                { SegmentedSwitchItem(title = "启用 Monet 颜色", checked = value.dynamic, enabled = android.os.Build.VERSION.SDK_INT >= 31, onCheckedChange = { update(value.copy(dynamic = it)) }) }
            ))
            Spacer(Modifier.height(13.dp))
            SegmentedColumn(content = listOf(
                { SegmentedDropdownItem(title = "主题色", items = listOf("绿色", "蓝色", "紫色"), selectedIndex = value.accent.coerceIn(0,2), onItemSelected = { update(value.copy(accent = it, keyColor = 0, dynamic = false)) }) },
                { val entries = top.yukonga.miuix.kmp.theme.ThemePaletteStyle.entries
                    SegmentedDropdownItem(title = "调色板风格", items = entries.map { it.name }, selectedIndex = entries.indexOfFirst { it.name == value.palette }.coerceAtLeast(0), onItemSelected = { update(value.copy(palette = entries[it].name)) }) },
                { SegmentedDropdownItem(title = "色彩规范", items = listOf("2021", "2025"), selectedIndex = if(value.spec2025) 1 else 0, onItemSelected = { update(value.copy(spec2025 = it == 1)) }) }
            ))
            Spacer(Modifier.height(org.fossify.messages.ui.compose.navigation.LocalGatewayBottomPadding.current + 24.dp))
        }
    }
}
