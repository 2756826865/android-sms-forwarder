package org.fossify.messages.activities

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.fossify.messages.ui.compose.diagnostics.ConfigBackupCard
import org.fossify.messages.ui.compose.theme.GatewayTheme

class ConfigBackupActivity : SimpleActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GatewayTheme {
                Surface {
                    Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()
                        .verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        TextButton(onClick = { finish() }) { Text("返回") }
                        ConfigBackupCard()
                    }
                }
            }
        }
    }
}
