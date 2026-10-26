package org.fossify.messages.ui.compose.diagnostics

import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Info
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.ui.graphics.asImageBitmap
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.messages.observability.bundle.DiagnosticBundleGenerator

/** Support actions do not depend on the live diagnostics screen loading successfully. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SupportSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logsOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun failed() { Toast.makeText(context, "操作失败，请重试", Toast.LENGTH_LONG).show() }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val snapshot = content
        if (uri != null && snapshot != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).bufferedWriter().use { it.write(snapshot) }
                }
                Toast.makeText(context, "日志已保存", Toast.LENGTH_SHORT).show()
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { failed() }
        }
    }
    val shareLogs: () -> Unit = {
        content?.let { snapshot ->

                    scope.launch {
                        try {
                            val file = withContext(Dispatchers.IO) {
                                val directory = java.io.File(context.cacheDir, "diagnostics").apply { mkdirs() }
                                java.io.File.createTempFile("sms-diagnostics-", ".json", directory).apply { writeText(snapshot) }
                            }
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "application/json"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                clipData = android.content.ClipData.newRawUri("诊断日志", uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }, "分享诊断日志"))
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (_: Exception) { failed() }
                    }

        }
    }
    if (org.fossify.messages.ui.compose.theme.LocalAppearance.current.material) {
        org.fossify.messages.ui.compose.ksu.material.SegmentedColumn(content = listOf(
            { org.fossify.messages.ui.compose.ksu.material.SegmentedListItem(onClick = { logsOpen = true }, headlineContent = { Text("发送日志") }) },
            { org.fossify.messages.ui.compose.ksu.material.SegmentedListItem(onClick = { aboutOpen = true }, headlineContent = { Text("关于") }) }
        ))
    } else {
    top.yukonga.miuix.kmp.basic.Card(Modifier.fillMaxWidth()) {
        top.yukonga.miuix.kmp.preference.ArrowPreference(title = "发送日志",
            startAction = {
                top.yukonga.miuix.kmp.basic.Icon(androidx.compose.material.icons.Icons.Rounded.BugReport,
                    modifier = Modifier.padding(end = 6.dp), contentDescription = null,
                    tint = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary)
            }, onClick = { logsOpen = true })
        top.yukonga.miuix.kmp.preference.ArrowPreference(title = "关于",
            startAction = {
                top.yukonga.miuix.kmp.basic.Icon(androidx.compose.material.icons.Icons.Rounded.Info,
                    modifier = Modifier.padding(end = 6.dp), contentDescription = null,
                    tint = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.primary)
            }, onClick = { aboutOpen = true })
    }
    }
    LaunchedEffect(logsOpen) {
        if (logsOpen) {
            busy = true
            content = null
            error = null
            try {
                content = withContext(Dispatchers.IO) {
                    DiagnosticBundleGenerator.generateBundle(context, encryptWithKeyStore = false).bundleContent
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { error = "日志生成失败，关闭后可重新尝试。" }
            finally { busy = false }
        }
    }
    if (logsOpen && org.fossify.messages.ui.compose.theme.LocalAppearance.current.material) {
        MaterialLogSheet(onDismissRequest = { logsOpen = false },
            onSave = { if (content != null) save.launch("sms-forwarder-diagnostics-${System.currentTimeMillis()}.json") },
            onShare = shareLogs,
            preview = {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    if (busy) CircularProgressIndicator()
                    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    content?.let { OutlinedTextField(value = it, onValueChange = {}, readOnly = true,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp), label = { Text("分享前请检查内容") }) }
                }
            })
    } else if (logsOpen) top.yukonga.miuix.kmp.overlay.OverlayDialog(
        show = true, renderInRootScaffold = false, onDismissRequest = { logsOpen = false }, insideMargin = androidx.compose.ui.unit.DpSize(0.dp, 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("发送日志", style = MaterialTheme.typography.titleLarge)
            Text("包含设备环境和诊断记录。请先预览并检查内容，再保存或调用系统分享。", style = MaterialTheme.typography.bodySmall)
            if (busy) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            content?.let { snapshot ->
                OutlinedTextField(value = snapshot, onValueChange = {}, readOnly = true,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp), label = { Text("内容预览") })
                OutlinedButton(onClick = { save.launch("sms-forwarder-diagnostics-${System.currentTimeMillis()}.json") }, modifier = Modifier.fillMaxWidth()) { Text("保存日志") }
                Button(onClick = { shareLogs() }, modifier = Modifier.fillMaxWidth()) { Text("分享日志") }

            }
            TextButton(onClick = { logsOpen = false }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
    if (aboutOpen) Dialog(onDismissRequest = { aboutOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        if (org.fossify.messages.ui.compose.theme.LocalAppearance.current.material) {
            AboutScreenMaterial(onBack = { aboutOpen = false }, onOpenLink = { url ->
                try { openSupportDestination(context, url) } catch (_: Exception) { failed() }
            })
        } else KsuSettingsPage("关于", onBack = { aboutOpen = false }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Spacer(Modifier.height(56.dp))
                val appIcon = remember(context) {
                    context.applicationInfo.loadIcon(context.packageManager).toBitmap(144, 144).asImageBitmap()
                }
                androidx.compose.foundation.Image(appIcon, contentDescription = null, modifier = Modifier.size(96.dp))
                Text(context.applicationInfo.loadLabel(context.packageManager).toString(), style = MaterialTheme.typography.headlineLarge)
                val info = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
                Text("版本 ${info.versionName.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                Text("短信收发与转发", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(36.dp))
                top.yukonga.miuix.kmp.basic.Card(Modifier.fillMaxWidth()) {
                    Column {
                        listOf(
                            "GitHub 源码" to "https://github.com/2756826865/android-sms-forwarder",
                            "更新日志" to "app:changelog",
                            "使用声明" to "app:notice", "开源许可" to "app:licenses", "开发者主页" to "https://www.coolapk.com/u/3270136"
                        ).forEach { (label, url) ->
                            top.yukonga.miuix.kmp.preference.ArrowPreference(title = label,
                                onClick = { try { openSupportDestination(context, url) } catch (_: Exception) { failed() } })
                        }
                        top.yukonga.miuix.kmp.preference.ArrowPreference(title = "加入 QQ 群", summary = "569321348", onClick = { openSupportDestination(context, "app:qq") })
                    }
                }
            }
        }
    }
}
