package org.fossify.messages.ui.compose.diagnostics

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.messages.helpers.ConfigBackupHelper

@Composable
internal fun OperationsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

internal class ConfigBackupUiModel : androidx.lifecycle.ViewModel() {
    val busy = mutableStateOf(false)
    val pendingJson = mutableStateOf<String?>(null)
    val preview = mutableStateOf("")
    val importing = mutableStateOf(false)
    val status = mutableStateOf("选择文件保存或恢复转发配置。")
    val restoreStatus = mutableStateOf("")
    override fun onCleared() { pendingJson.value = null; preview.value = "" }
}

@Composable
fun ConfigBackupCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val owner = remember(context) {
        var current = context
        while (current !is androidx.lifecycle.ViewModelStoreOwner && current is android.content.ContextWrapper) {
            val base = current.baseContext
            check(base !== current)
            current = base
        }
        checkNotNull(current as? androidx.lifecycle.ViewModelStoreOwner)
    }
    val model = remember(owner) {
        androidx.lifecycle.ViewModelProvider(owner)["configuration-backup", ConfigBackupUiModel::class.java]
    }
    var busy by model.busy
    var pendingJson by model.pendingJson
    var preview by model.preview
    var importing by model.importing
    var status by model.status
    var restoreStatus by model.restoreStatus
    LaunchedEffect(context) { restoreStatus = org.fossify.messages.helpers.ConfigRestoreGuard.status(context) }
    fun runAction(action: suspend () -> Unit) {
        busy = true
        scope.launch {
            try { action() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { status = "操作失败，未确认完成。请检查文件及存储权限后重试。" }
            finally { busy = false }
        }
    }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        busy = false
        val json = pendingJson
        pendingJson = null
        if (uri != null && json != null) runAction {
            withContext(Dispatchers.IO) {
                checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).bufferedWriter(Charsets.UTF_8).use { it.write(json) }
            }
            status = "备份已保存到所选文件位置。"
        } else if (uri != null) status = "导出已中断，请重新导出；本次文件未写入备份内容。"
    }
    val readFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runAction {
            val json = withContext(Dispatchers.IO) {
                // Bounded input; do not load arbitrarily large documents into the UI process.
                checkNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 2 * 1024 * 1024) {
                        val count = input.read(buffer, 0, minOf(buffer.size, 2 * 1024 * 1024 + 1 - output.size()))
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                    val bytes = output.toByteArray()
                    require(bytes.size <= 2 * 1024 * 1024)
                    bytes.toString(Charsets.UTF_8)
                }
            }
            preview = withContext(Dispatchers.IO) { ConfigBackupHelper.preview(json) }
            importing = true
            pendingJson = json
        }
    }
    OperationsSection("备份与恢复") {
        Text("通道 · 转发规则 · 远程来源 · 自动回复", style = MaterialTheme.typography.bodyMedium)
        Text("备份包含密码和密钥，请自行保管。包含SIM、备用通道及常用设置；不包含短信正文、历史流水和系统授权。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && pendingJson == null, onClick = { runAction {
                val json = withContext(Dispatchers.IO) { ConfigBackupHelper.exportToJson(context) }
                preview = withContext(Dispatchers.IO) { ConfigBackupHelper.preview(json) }
                importing = false
                pendingJson = json
            } }, modifier = Modifier.weight(1f)) { Text("导出文件") }
            OutlinedButton(enabled = !busy && pendingJson == null, onClick = { readFile.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                modifier = Modifier.weight(1f)) { Text("从文件恢复") }
        }
        TextButton(enabled = !busy && pendingJson == null, onClick = { runAction {
            val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
            val json = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
            require(json.isNotBlank() && json.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024)
            preview = withContext(Dispatchers.IO) { ConfigBackupHelper.preview(json) }
            importing = true
            pendingJson = json
        } }) { Text("兼容旧备份：从剪贴板导入") }
        Text(if (busy) "处理中…" else status, style = MaterialTheme.typography.bodySmall)
        Text(restoreStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (pendingJson != null) AlertDialog(
        onDismissRequest = { if (!busy) pendingJson = null },
        title = { Text(if (importing) "恢复前预览" else "导出前预览") },
        text = { Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            Text(preview)
            if (!importing) TextButton(enabled = !busy, onClick = {
                val clipboard = context.getSystemService(android.content.ClipboardManager::class.java)
                val clip = android.content.ClipData.newPlainText("SMS_Forwarder_Config", pendingJson.orEmpty())
                clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                clipboard?.setPrimaryClip(clip)
                pendingJson = null
                status = "配置已复制，包含凭据，请妥善保管。"
            }) { Text("兼容旧方式：复制到剪贴板") }
            if (importing) Text("恢复前自动保存本机加密快照，失败时尝试回滚；仍建议先导出一份文件。")
        } },
        confirmButton = { TextButton(enabled = !busy, onClick = {
            if (importing) {
                val json = pendingJson ?: return@TextButton
                runAction {
                    val success = withContext(Dispatchers.IO) { ConfigBackupHelper.importFromJson(context, json) }
                    pendingJson = null
                    restoreStatus = org.fossify.messages.helpers.ConfigRestoreGuard.status(context)
                    status = if (success) "配置恢复完成，请核对通道、规则与远程来源。" else "恢复未完成。" + org.fossify.messages.helpers.ConfigRestoreGuard.status(context)
                    Toast.makeText(context, status, Toast.LENGTH_LONG).show()
                }
            } else {
                busy = true
                saveFile.launch("SMS-Forwarder-config-${System.currentTimeMillis()}.json")
            }
        }) { Text(if (importing) "确认恢复" else "选择保存位置") } },
        dismissButton = { TextButton(enabled = !busy, onClick = { pendingJson = null }) { Text("取消") } })
}

@Composable
fun ClassicSettingsAccessCard() {
    val context = LocalContext.current
    OperationsSection("更多设置") {
        Text("系统兼容、关于及更多短信设置仍可通过完整设置页管理。", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { context.startActivity(Intent(context, org.fossify.messages.activities.UserGuideActivity::class.java)) }) {
            Text("使用教程与排障")
        }
        OutlinedButton(onClick = { context.startActivity(Intent(context, org.fossify.messages.activities.AboutActivity::class.java)) }) {
            Text("关于与 QQ 交流群")
        }
        OutlinedButton(onClick = { context.startActivity(Intent(context, org.fossify.messages.activities.SettingsActivity::class.java)) }) {
            Text("打开完整设置")
        }
    }
}
