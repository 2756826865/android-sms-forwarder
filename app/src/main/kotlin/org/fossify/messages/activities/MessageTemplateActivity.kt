package org.fossify.messages.activities

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import org.fossify.messages.ui.compose.rules.RuleStudioScreen
import org.fossify.messages.ui.compose.theme.GatewayTheme

class MessageTemplateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GatewayTheme {
                RuleStudioScreen(
                    onBack = { finish() }
                )
            }
        }
    }
}
