package org.fossify.messages.ui.compose.diagnostics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun DeveloperHelpPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val entries = listOf("使用教程与排障" to "app:guide", "常见问题" to "app:faq", "已知问题" to "app:known", "加入 QQ 群 · 569321348" to "app:qq")
    KsuSettingsPage("帮助与反馈", onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(12.dp).verticalScroll(rememberScrollState())) {
            if (org.fossify.messages.ui.compose.theme.LocalAppearance.current.material) {
                org.fossify.messages.ui.compose.ksu.material.SegmentedColumn(content = entries.map { (title, destination) ->
                    { org.fossify.messages.ui.compose.ksu.material.SegmentedListItem(
                        onClick = { openSupportDestination(context, destination) }, headlineContent = { Text(title) }) }
                })
            } else {
                top.yukonga.miuix.kmp.basic.Card(Modifier.fillMaxWidth()) {
                    entries.forEach { (title, destination) ->
                        top.yukonga.miuix.kmp.preference.ArrowPreference(title = title, onClick = { openSupportDestination(context, destination) })
                    }
                }
            }
            Spacer(Modifier.height(org.fossify.messages.ui.compose.navigation.LocalGatewayBottomPadding.current + 24.dp))
        }
    }
}
