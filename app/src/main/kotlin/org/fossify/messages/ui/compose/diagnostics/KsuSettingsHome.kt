// Adapted from tiann/KernelSU v3.3.1 SettingsMiuix.kt (GPL-3.0-or-later).
// Original author: weishu. Kernel/root actions replaced with SMS Forwarder settings.
package org.fossify.messages.ui.compose.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.ui.graphics.vector.ImageVector
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
internal fun KsuSettingsHome(onOpen: (String) -> Unit) {
    val appearance = org.fossify.messages.ui.compose.theme.LocalAppearance.current
    val update = org.fossify.messages.ui.compose.theme.LocalSetAppearance.current
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = { TopAppBar(title = "设置", scrollBehavior = scrollBehavior) },
        popupHost = {},
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal)
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxHeight().scrollEndHaptic().overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection).padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null
        ) {
            item {
                Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    top.yukonga.miuix.kmp.preference.OverlayDropdownPreference(
                        title = "界面风格", items = listOf("Miuix", "Material"), selectedIndex = 0,
                        onSelectedIndexChange = { update(appearance.copy(material = it == 1)) })
                    KsuSettingEntry("外观与主题", "明暗模式、配色与胶囊效果", Icons.Rounded.Palette, onOpen)
                    KsuSettingEntry("短信与显示", "显示密度、拦截名单与回收站", Icons.Rounded.Message, onOpen)
                }
                Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    KsuSettingEntry("后台运行", "电池优化、自启动与前台服务", Icons.Rounded.Settings, onOpen)
                    KsuSettingEntry("备份与恢复", "配置文件导出与导入", Icons.Rounded.Save, onOpen)
                }
                Card(Modifier.padding(top = 12.dp).fillMaxWidth()) {
                    KsuSettingEntry("诊断与报告", "短信链路、队列、Root 状态与日志", Icons.Rounded.BugReport, onOpen)
                }
                Spacer(Modifier.height(12.dp))
                SupportSettings()
                Spacer(Modifier.height(org.fossify.messages.ui.compose.navigation.LocalGatewayBottomPadding.current + 24.dp))
            }
        }
    }
}

@Composable
private fun KsuSettingEntry(title: String, summary: String, icon: ImageVector, onOpen: (String) -> Unit) {
    ArrowPreference(title = title, summary = summary,
        startAction = {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(end = 6.dp),
                tint = MiuixTheme.colorScheme.onBackground)
        }, onClick = { onOpen(title) })
}

/** Shared secondary-page chrome, using the same Miuix scaffold as upstream. */
@Composable
internal fun KsuSettingsPage(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    if (org.fossify.messages.ui.compose.theme.LocalAppearance.current.material) {
        MaterialSettingsPage(title, onBack, content)
        return
    }
    Scaffold(
        topBar = {
            top.yukonga.miuix.kmp.basic.SmallTopAppBar(title = title, navigationIcon = {
                androidx.compose.material3.IconButton(onClick = onBack) {
                    Icon(androidx.compose.material.icons.Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "返回", tint = MiuixTheme.colorScheme.onBackground)
                }
            })
        }, popupHost = {},
        contentWindowInsets = WindowInsets.systemBars.add(WindowInsets.displayCutout).only(WindowInsetsSides.Horizontal),
        content = content
    )
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun MaterialSettingsPage(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    org.fossify.messages.ui.compose.ksu.material.ExpressiveScaffold(topBar = {
        androidx.compose.material3.TopAppBar(title = { androidx.compose.material3.Text(title) }, navigationIcon = {
            androidx.compose.material3.IconButton(onClick = onBack) {
                androidx.compose.material3.Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
            }
        })
    }, content = content)
}
