package org.fossify.messages.ui.compose.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
    onRequestDefaultSmsRole: () -> Unit = {}
) {
    var selectedTab by remember { mutableStateOf(GatewayTab.MESSAGES) }

    Scaffold(
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                GatewayTab.values().forEach { tab ->
                    val isSelected = selectedTab == tab
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { selectedTab = tab },
                        icon = {
                            Text(
                                text = tab.emoji,
                                fontSize = if (isSelected) 20.sp else 16.sp
                            )
                        },
                        label = {
                            Text(
                                text = tab.title,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 11.sp
                            )
                        }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                GatewayTab.MESSAGES -> ConversationsScreen(
                    conversationsViewModel = conversationsViewModel,
                    onRequestDefaultSms = onRequestDefaultSmsRole
                )
                GatewayTab.DASHBOARD -> DashboardScreen(
                    viewModel = dashboardViewModel,
                    onNavigateToOperations = { selectedTab = GatewayTab.OPERATIONS }
                )
                GatewayTab.RULES -> RuleStudioScreen()
                GatewayTab.CHANNELS -> ChannelHubScreen()
                GatewayTab.OPERATIONS -> OperationsScreen(viewModel = diagnosticsViewModel)
            }
        }
    }
}
