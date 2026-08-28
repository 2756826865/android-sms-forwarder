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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import org.fossify.messages.ui.compose.rules.RuleStudioScreen
import org.fossify.messages.ui.dashboard.DashboardViewModel
import org.fossify.messages.ui.diagnostics.DiagnosticsViewModel
import org.fossify.messages.ui.messages.MessageCenterViewModel

enum class GatewayTab(val title: String, val emoji: String) {
    MESSAGES("信息", "💬"),
    DASHBOARD("大盘", "📊"),
    RULES("规则", "⚡"),
    CHANNELS("通道", "🔌"),
    OPERATIONS("运维", "🛠️")
}

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 主体内容页面
        Box(modifier = Modifier.fillMaxSize()) {
            when (selectedTab) {
                GatewayTab.MESSAGES -> ConversationsScreen(
                    conversationsViewModel = conversationsViewModel,
                    onRequestDefaultSms = onRequestDefaultSmsRole,
                    onSwitchToClassic = onSwitchToClassic
                )
                GatewayTab.DASHBOARD -> DashboardScreen(
                    viewModel = dashboardViewModel,
                    onNavigateToOperations = { selectedTab = GatewayTab.OPERATIONS },
                    onSwitchToClassic = onSwitchToClassic
                )
                GatewayTab.RULES -> RuleStudioScreen()
                GatewayTab.CHANNELS -> ChannelHubScreen()
                GatewayTab.OPERATIONS -> OperationsScreen(viewModel = diagnosticsViewModel)
            }
        }

        // 悬浮毛玻璃胶囊底部导航栏 (Floating Capsule Dock)
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 10.dp)
                .navigationBarsPadding(),
            shape = RoundedCornerShape(36.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
            shadowElevation = 16.dp,
            border = BorderStroke(1.dp, Color(0xFFE2E8F0))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceAround,
                verticalAlignment = Alignment.CenterVertically
            ) {
                GatewayTab.values().forEach { tab ->
                    val isSelected = selectedTab == tab
                    val interactionSource = remember { MutableInteractionSource() }

                    val itemBgColor by animateColorAsState(
                        targetValue = if (isSelected) Color(0xFFE8F5E9) else Color.Transparent,
                        animationSpec = tween(200),
                        label = "tabBg"
                    )

                    val textColor by animateColorAsState(
                        targetValue = if (isSelected) Color(0xFF159447) else Color(0xFF718096),
                        animationSpec = tween(200),
                        label = "tabText"
                    )

                    Surface(
                        onClick = { selectedTab = tab },
                        shape = RoundedCornerShape(20.dp),
                        color = itemBgColor,
                        modifier = Modifier
                            .padding(horizontal = 2.dp, vertical = 2.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = tab.emoji,
                                fontSize = if (isSelected) 18.sp else 16.sp
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = tab.title,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                fontSize = 11.sp,
                                color = textColor
                            )
                        }
                    }
                }
            }
        }
    }
}
