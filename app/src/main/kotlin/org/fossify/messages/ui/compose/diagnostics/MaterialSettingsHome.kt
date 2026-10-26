// Adapted from tiann/KernelSU v3.3.1 SettingsMaterial.kt; GPL-3.0-or-later.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package org.fossify.messages.ui.compose.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import org.fossify.messages.ui.compose.ksu.material.ExpressiveScaffold
import org.fossify.messages.ui.compose.ksu.material.SegmentedColumn
import org.fossify.messages.ui.compose.ksu.material.SegmentedDropdownItem
import org.fossify.messages.ui.compose.ksu.material.SegmentedListItem
import org.fossify.messages.ui.compose.theme.LocalAppearance
import org.fossify.messages.ui.compose.theme.LocalSetAppearance

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MaterialSettingsHome(onOpen: (String) -> Unit) {
    val behavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val appearance = LocalAppearance.current
    val update = LocalSetAppearance.current
    ExpressiveScaffold(topBar = { LargeFlexibleTopAppBar(title = { Text("设置") }, scrollBehavior = behavior) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).nestedScroll(behavior.nestedScrollConnection)
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SegmentedColumn(content = listOf({
                SegmentedDropdownItem(icon = Icons.Rounded.DisplaySettings, title = "界面风格", items = listOf("Miuix", "Material"),
                    selectedIndex = 1, onItemSelected = { update(appearance.copy(material = it == 1)) })
            }))
            Spacer(Modifier.height(13.dp))
            SegmentedColumn(content = listOf(
                { MaterialEntry("外观与主题", "明暗模式、配色与导航效果", Icons.Rounded.Palette, onOpen) },
                { MaterialEntry("短信与显示", "显示密度、拦截名单与回收站", Icons.Rounded.Message, onOpen) }
            ))
            Spacer(Modifier.height(13.dp))
            SegmentedColumn(content = listOf(
                { MaterialEntry("后台运行", "电池优化、自启动与前台服务", Icons.Rounded.Settings, onOpen) },
                { MaterialEntry("备份与恢复", "配置文件导出与导入", Icons.Rounded.Save, onOpen) },
                { MaterialEntry("诊断与报告", "短信链路、队列、Root 状态与日志", Icons.Rounded.BugReport, onOpen) }
            ))
            Spacer(Modifier.height(13.dp))
            SupportSettings()
            Spacer(Modifier.height(org.fossify.messages.ui.compose.navigation.LocalGatewayBottomPadding.current + 24.dp))
        }
    }
}

@Composable
private fun MaterialEntry(title: String, summary: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onOpen: (String) -> Unit) {
    SegmentedListItem(onClick = { onOpen(title) }, headlineContent = { Text(title) },
        supportingContent = { Text(summary) }, leadingContent = { Icon(icon, null) })
}
