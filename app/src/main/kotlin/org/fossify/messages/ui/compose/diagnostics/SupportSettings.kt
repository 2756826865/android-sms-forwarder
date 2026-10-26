package org.fossify.messages.ui.compose.diagnostics

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
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth()) {
            TextButton(onClick = { logsOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("发送日志    ›") }
            TextButton(onClick = { aboutOpen = true }, modifier = Modifier.fillMaxWidth()) { Text("关于    ›") }
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
    if (logsOpen) ModalBottomSheet(onDismissRequest = { logsOpen = false }) {
        Column(Modifier.fillMaxWidth().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("发送日志", style = MaterialTheme.typography.titleLarge)
            Text("包含设备环境和诊断记录。请先预览并检查内容，再保存或调用系统分享。", style = MaterialTheme.typography.bodySmall)
            if (busy) CircularProgressIndicator()
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            content?.let { snapshot ->
                OutlinedTextField(value = snapshot, onValueChange = {}, readOnly = true,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp), label = { Text("内容预览") })
                OutlinedButton(onClick = { save.launch("sms-forwarder-diagnostics-${System.currentTimeMillis()}.json") }, modifier = Modifier.fillMaxWidth()) { Text("保存日志") }
                Button(onClick = {
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
                }, modifier = Modifier.fillMaxWidth()) { Text("分享日志") }
            }
            TextButton(onClick = { logsOpen = false }, modifier = Modifier.fillMaxWidth()) { Text("取消") }
            Spacer(Modifier.navigationBarsPadding())
        }
    }
    if (aboutOpen) Dialog(onDismissRequest = { aboutOpen = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                TextButton(onClick = { aboutOpen = false }) { Text("‹ 返回设置") }
                Text(context.applicationInfo.loadLabel(context.packageManager).toString(), style = MaterialTheme.typography.headlineLarge)
                val info = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0) }
                Text("版本 ${info.versionName.orEmpty()}", style = MaterialTheme.typography.titleMedium)
                Text("短信收发与转发", style = MaterialTheme.typography.bodyMedium)
                Surface(shape = MaterialTheme.shapes.large) {
                    Column {
                        listOf(
                            "GitHub 源码" to "https://github.com/2756826865/android-sms-forwarder",
                            "更新日志" to "https://github.com/2756826865/android-sms-forwarder/releases",
                            "使用教程" to "https://github.com/2756826865/android-sms-forwarder#readme"
                        ).forEach { (label, url) ->
                            TextButton(onClick = { try { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { failed() } }, modifier = Modifier.fillMaxWidth()) { Text("$label    ›") }
                        }
                        Text("QQ 群：569321348", Modifier.padding(16.dp))
                        Text("开源组件：KernelSU v3.3.1 导航（GPL-3.0-or-later）、Miuix 0.9.4；原始声明随源码保留。", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
