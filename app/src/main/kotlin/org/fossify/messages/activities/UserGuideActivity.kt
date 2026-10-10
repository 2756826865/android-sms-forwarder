package org.fossify.messages.activities

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import org.fossify.messages.ui.compose.forwarding.ChannelFullTutorialDialog
import org.fossify.messages.ui.compose.theme.GatewayTheme

/** Same tutorial for both interfaces; no duplicate protocol instructions. */
class UserGuideActivity : SimpleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GatewayTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ChannelFullTutorialDialog(onDismiss = { finish() })
                }
            }
        }
    }
}
