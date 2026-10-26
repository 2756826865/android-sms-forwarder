// Ported from tiann/KernelSU v3.3.1 AboutMaterial.kt; GPL-3.0-or-later.
@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)
package org.fossify.messages.ui.compose.diagnostics

import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.FixedScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.fossify.messages.ui.compose.ksu.material.ExpressiveScaffold
import org.fossify.messages.ui.compose.ksu.material.SegmentedColumn
import org.fossify.messages.ui.compose.ksu.material.SegmentedListItem
import org.fossify.messages.ui.compose.ksu.material.TopBarBackButton
import org.fossify.messages.ui.compose.ksu.material.expressiveTopAppBarColors

@Composable
fun AboutScreenMaterial(
    onBack: () -> Unit,
    onOpenLink: (String) -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val info = androidx.compose.runtime.remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
    val links = listOf("GitHub 源码" to "https://github.com/2756826865/android-sms-forwarder", "更新日志" to "https://github.com/2756826865/android-sms-forwarder/releases", "使用教程" to "https://github.com/2756826865/android-sms-forwarder#readme")
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())

    ExpressiveScaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Text("关于") },
                navigationIcon = {
                    TopBarBackButton(onClick = onBack)
                },
                colors = expressiveTopAppBarColors(),
                scrollBehavior = scrollBehavior
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                    ) {
                        Image(
                            bitmap = androidx.compose.runtime.remember(context) { context.applicationInfo.loadIcon(context.packageManager).toBitmap(144, 144).asImageBitmap() },
                            contentDescription = null,
                            contentScale = FixedScale(1f)
                        )
                    }
                    Text(
                        modifier = Modifier.padding(top = 12.dp),
                        text = context.applicationInfo.loadLabel(context.packageManager).toString(),
                        fontWeight = FontWeight.Medium,
                        fontSize = MaterialTheme.typography.headlineMedium.fontSize
                    )
                    Text(
                        text = info.versionName.orEmpty(),
                        fontSize = MaterialTheme.typography.bodyMedium.fontSize
                    )
                }
            }
            item {
                SegmentedColumn(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    content = links.map { linkInfo ->
                        {
                            SegmentedListItem(
                                onClick = { onOpenLink(linkInfo.second) },
                                headlineContent = { Text(linkInfo.first) }
                            )
                        }
                    }
                )
                Text("QQ 群：569321348", Modifier.padding(16.dp))
                Text("KernelSU 导航与界面：GPL-3.0-or-later；Miuix 0.9.4。原始声明随源码保留。", Modifier.padding(16.dp))
                Spacer(
                    Modifier.height(
                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                WindowInsets.captionBar.asPaddingValues().calculateBottomPadding()
                    )
                )
            }
        }
    }
}

