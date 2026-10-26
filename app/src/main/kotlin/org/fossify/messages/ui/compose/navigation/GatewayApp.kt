package org.fossify.messages.ui.compose.navigation

import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Message
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Rule
import org.fossify.messages.ui.compose.ksu.FloatingBottomBarItem
import top.yukonga.miuix.kmp.blur.layerBackdrop
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.fossify.messages.ui.compose.conversations.ConversationsScreen
import org.fossify.messages.ui.compose.conversations.ConversationsViewModel
import org.fossify.messages.ui.compose.dashboard.DashboardScreen
import org.fossify.messages.ui.compose.diagnostics.OperationsScreen
import org.fossify.messages.ui.compose.forwarding.ChannelHubScreen
import org.fossify.messages.ui.dashboard.DashboardViewModel
import org.fossify.messages.ui.diagnostics.DiagnosticsViewModel
import org.fossify.messages.ui.messages.MessageCenterViewModel

enum class GatewayTab(val title: String, val emoji: String) {
    MESSAGES("信息", "💬"),
    DASHBOARD("大盘", "📊"),
    CHANNELS("通道", "🔌"),
    RULES("规则", "⚡"),
    OPERATIONS("设置", "⚙️")
}

private fun GatewayTab.navigationIcon() = when (this) {
    GatewayTab.MESSAGES -> Icons.Rounded.Message
    GatewayTab.DASHBOARD -> Icons.Rounded.BarChart
    GatewayTab.CHANNELS -> Icons.Rounded.Extension
    GatewayTab.OPERATIONS -> Icons.Rounded.Settings
    GatewayTab.RULES -> Icons.Rounded.Rule
}

/** Space reserved by gateway pages for the floating dock itself (system navigation inset excluded). */
val GatewayDockContentPadding = 96.dp

/** Extra lift applied to page FABs so they stay above the floating dock. */
val GatewayDockFabPadding = 84.dp

val LocalGatewayBottomPadding = androidx.compose.runtime.compositionLocalOf<androidx.compose.ui.unit.Dp> { 96.dp }

@Composable
fun GatewayApp(
    dashboardViewModel: DashboardViewModel,
    messageCenterViewModel: MessageCenterViewModel,
    diagnosticsViewModel: DiagnosticsViewModel,
    conversationsViewModel: ConversationsViewModel,
    onRequestDefaultSmsRole: () -> Unit = {},
    onSwitchToClassic: () -> Unit = {}
) {
    val dockBackdrop = if (android.os.Build.VERSION.SDK_INT >= 33) top.yukonga.miuix.kmp.blur.rememberLayerBackdrop() else null
    var selectedTab by remember { mutableStateOf(GatewayTab.MESSAGES) }
    var settingsTarget by remember { mutableStateOf<String?>(null) }
    var settingsRequest by remember { mutableStateOf(0) }
    var editingRuleId by remember { mutableStateOf<String?>(null) }
    var isEditingRule by remember { mutableStateOf(false) }
    var isInlineEditorOpen by remember { mutableStateOf(false) }

    val safeBottom = WindowInsets.safeDrawing
        .only(WindowInsetsSides.Bottom)
        .asPaddingValues()
        .calculateBottomPadding()

    val isDockVisible = !isEditingRule && !isInlineEditorOpen
    val dynamicBottomPadding = if (isDockVisible) safeBottom + 86.dp else safeBottom + 16.dp

    androidx.compose.runtime.CompositionLocalProvider(LocalGatewayBottomPadding provides dynamicBottomPadding) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            // 主体内容页面：全屏沉浸穿透滚动，子页面通过 LocalGatewayBottomPadding 动态预留避让空间
            Box(
                modifier = Modifier.fillMaxSize().then(if (dockBackdrop != null) Modifier.layerBackdrop(dockBackdrop) else Modifier)
            ) {
                when (selectedTab) {
                    GatewayTab.MESSAGES -> ConversationsScreen(
                        conversationsViewModel = conversationsViewModel,
                        onRequestDefaultSms = onRequestDefaultSmsRole,
                        onSwitchToClassic = onSwitchToClassic
                    )
                    GatewayTab.DASHBOARD -> DashboardScreen(
                        viewModel = dashboardViewModel,
                        onRequestDefaultSms = onRequestDefaultSmsRole,
                        onNavigateToOperations = { settingsTarget = "保活设置"; settingsRequest++; selectedTab = GatewayTab.OPERATIONS },
                        onSwitchToClassic = onSwitchToClassic
                    )
                    GatewayTab.CHANNELS -> ChannelHubScreen(
                        onInlineEditorVisibilityChanged = { isInlineEditorOpen = it }
                    )
                    GatewayTab.RULES -> {
                        if (isEditingRule) {
                            org.fossify.messages.ui.compose.rules.RuleEditorScreen(
                                ruleId = editingRuleId,
                                onNavigateBack = { isEditingRule = false }
                            )
                        } else {
                            org.fossify.messages.ui.compose.rules.RuleManagementScreen(
                                // 规则已经是独立底部标签，不再显示容易挤压标题的“返回通道”。
                                onNavigateBack = null,
                                onNavigateToEditor = { id ->
                                    editingRuleId = id
                                    isEditingRule = true
                                }
                            )
                        }
                    }
                    GatewayTab.OPERATIONS -> OperationsScreen(viewModel = diagnosticsViewModel, initialSection = settingsTarget, navigationRequest = settingsRequest)
                }
            }

            if (isDockVisible) {
                val tabs = GatewayTab.values().filterNot { it == GatewayTab.RULES }
                if (org.fossify.messages.ui.compose.theme.LocalAppearance.current.material) {
                    androidx.compose.material3.NavigationBar(Modifier.align(Alignment.BottomCenter)) {
                        tabs.forEach { tab ->
                            NavigationBarItem(selected = selectedTab == tab,
                                onClick = { selectedTab = tab; if (tab == GatewayTab.OPERATIONS) { settingsTarget = null; settingsRequest = 0 } },
                                icon = { androidx.compose.material3.Icon(tab.navigationIcon(), null) },
                                label = { Text(tab.title) })
                        }
                    }
                } else if (dockBackdrop == null || !org.fossify.messages.ui.compose.theme.LocalAppearance.current.floating) {
                    Surface(Modifier.align(Alignment.BottomCenter).padding(start = 28.dp, end = 28.dp, bottom = safeBottom + 8.dp),
                        shape = CircleShape, color = MaterialTheme.colorScheme.surface, shadowElevation = 8.dp) {
                        Row(Modifier.fillMaxWidth().padding(4.dp)) {
                            tabs.forEach { tab ->
                                Surface(onClick = { selectedTab = tab; if (tab == GatewayTab.OPERATIONS) { settingsTarget = null; settingsRequest = 0 } }, shape = CircleShape,
                                    color = if (selectedTab == tab) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                                    modifier = Modifier.weight(1f).height(56.dp)) {
                                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                        androidx.compose.material3.Icon(tab.navigationIcon(),
                                contentDescription = null, modifier = Modifier.size(24.dp),
                                tint = MaterialTheme.colorScheme.onSurface)
                                        Text(tab.title, fontSize = 11.sp, color = if (selectedTab == tab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                } else org.fossify.messages.ui.compose.ksu.FloatingBottomBar(
                    modifier = Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(start = 28.dp, end = 28.dp, bottom = safeBottom + 8.dp),
                    selectedIndex = tabs.indexOf(selectedTab).coerceAtLeast(0),
                    onSelected = { selectedTab = tabs[it]; if (selectedTab == GatewayTab.OPERATIONS) { settingsTarget = null; settingsRequest = 0 } },
                    backdrop = dockBackdrop,
                    tabsCount = tabs.size,
                    isBlurEnabled = org.fossify.messages.ui.compose.theme.LocalAppearance.current.blur && android.os.Build.VERSION.SDK_INT >= 33
                ) { activate ->
                    tabs.forEachIndexed { index, tab ->
                        FloatingBottomBarItem(
                            selected = selectedTab == tab,
                            onClick = { activate(index) },
                            modifier = Modifier.defaultMinSize(minWidth = 64.dp)
                        ) {
                            androidx.compose.material3.Icon(tab.navigationIcon(),
                                contentDescription = null, modifier = Modifier.size(24.dp),
                                tint = top.yukonga.miuix.kmp.theme.LocalContentColor.current)
                            Text(tab.title, fontSize = 11.sp,
                                color = top.yukonga.miuix.kmp.theme.LocalContentColor.current)
                        }
                    }
                }
            }
        }
    }
}
