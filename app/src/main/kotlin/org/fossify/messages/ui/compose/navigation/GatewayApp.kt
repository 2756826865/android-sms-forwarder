package org.fossify.messages.ui.compose.navigation

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
    OPERATIONS("运维", "🛠️")
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
    var selectedTab by remember { mutableStateOf(GatewayTab.MESSAGES) }
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
                modifier = Modifier.fillMaxSize()
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
                        onNavigateToOperations = { selectedTab = GatewayTab.OPERATIONS },
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
                    GatewayTab.OPERATIONS -> OperationsScreen(viewModel = diagnosticsViewModel)
                }
            }

            // 悬浮白色大圆角底部导航栏 (Modern Floating Capsule Dock)
            val isDark = androidx.compose.foundation.isSystemInDarkTheme()
            if (isDockVisible) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(
                            start = 18.dp,
                            end = 18.dp,
                            bottom = safeBottom + 12.dp
                        ),
                    shape = RoundedCornerShape(34.dp),
                    color = if (isDark) org.fossify.messages.ui.compose.theme.DarkSurface else Color.White,
                    shadowElevation = 10.dp,
                    border = BorderStroke(1.dp, if (isDark) org.fossify.messages.ui.compose.theme.DarkOutline else org.fossify.messages.ui.compose.theme.OutlineSoft)
                ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GatewayTab.values().filterNot { it == GatewayTab.RULES }.forEach { tab ->
                    val isSelected = selectedTab == tab

                    val itemBgColor by animateColorAsState(
                        targetValue = if (isSelected) {
                            if (isDark) Color(0xFF1B3322) else org.fossify.messages.ui.compose.theme.BrandGreenSoft
                        } else Color.Transparent,
                        animationSpec = tween(200),
                        label = "tabBg"
                    )

                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) org.fossify.messages.ui.compose.theme.BrandGreen else Color(0xFF667085),
                        animationSpec = tween(200),
                        label = "tabText"
                    )

                    Surface(
                        onClick = {
                            if (tab == GatewayTab.RULES && selectedTab == GatewayTab.RULES) {
                                isEditingRule = false
                            }
                            selectedTab = tab
                        },
                        shape = RoundedCornerShape(20.dp),
                        color = itemBgColor,
                        modifier = Modifier
                            .padding(horizontal = 1.dp, vertical = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 8.dp, vertical = 5.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = tab.emoji,
                                fontSize = if (isSelected) 17.sp else 15.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tab.title,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 11.sp,
                                color = textColor,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            }
        }
    }
}
}
}
