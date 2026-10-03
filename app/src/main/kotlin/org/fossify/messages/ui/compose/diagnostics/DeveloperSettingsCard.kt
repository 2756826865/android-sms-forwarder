package org.fossify.messages.ui.compose.diagnostics

import android.content.Intent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.helpers.*
import org.fossify.messages.models.Conversation

@Composable
fun DeveloperSettingsCard() {
    val context = LocalContext.current
    val config = context.config
    val scope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var liveIsland by remember { mutableStateOf(config.enableLiveIsland) }
    var recycleEnabled by remember { mutableStateOf(config.useRecycleBin) }
    var density by remember { mutableStateOf(config.homeListDensity) }
    var editingList by remember { mutableStateOf<String?>(null) }
    var draft by remember { mutableStateOf("") }
    var recycle by remember { mutableStateOf<List<Conversation>?>(null) }
    var recycleStatus by remember { mutableStateOf("") }
    fun editList(kind: String) {
        editingList = kind
        draft = when (kind) { "关键词" -> config.blockedKeywords; "黑名单" -> config.blacklistedNumbers; else -> config.whitelistedNumbers }.joinToString("\n")
    }
    OperationsSection("显示与拦截设置") {
        TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "收起设置" else "展开设置") }
        if (expanded) {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("灵动岛", Modifier.weight(1f))
                Switch(liveIsland, onCheckedChange = { liveIsland = it; config.enableLiveIsland = it })
            }
            Text("系统支持和相关权限仍需在设备设置中满足。", style = MaterialTheme.typography.bodySmall)
            Text("会话显示密度（两版共用）：$density 行", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(4, 6, 8, 10).forEach { n -> FilterChip(selected = density == n, onClick = { density = n; config.homeListDensity = n }, label = { Text("$n") }) }
            }
            Text("应用短信拦截：关键词、号码黑名单与白名单共用经典版配置。系统来电拦截仍由系统管理。", style = MaterialTheme.typography.bodySmall)
            listOf("关键词", "黑名单", "白名单").forEach { kind ->
                OutlinedButton(onClick = { editList(kind) }, modifier = Modifier.fillMaxWidth()) { Text("管理$kind") }
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("启用短信回收站", Modifier.weight(1f))
                Switch(recycleEnabled, onCheckedChange = { recycleEnabled = it; config.useRecycleBin = it })
            }
            OutlinedButton(onClick = {
                scope.launch {
                    try {
                        recycle = withContext(Dispatchers.IO) { context.conversationsDB.getRecentRecycledConversations() }
                        recycleStatus = ""
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { recycleStatus = "读取回收站失败，请重试" }
                }
            }) { Text("查看回收站") }
            if (recycleStatus.isNotBlank()) Text(recycleStatus)
        }
    }
    if (editingList != null) AlertDialog(onDismissRequest = { editingList = null },
        title = { Text("管理${editingList}") },
        text = { Column { Text("每行一项；留空并保存将清空此名单。")
            OutlinedTextField(value = draft, onValueChange = { draft = it }, modifier = Modifier.heightIn(max = 280.dp), label = { Text("名单内容") }) } },
        confirmButton = { TextButton(onClick = {
            val values = draft.lines().map(String::trim).filter(String::isNotBlank).toSet()
            when (editingList) { "关键词" -> config.blockedKeywords = values; "黑名单" -> config.blacklistedNumbers = values; else -> config.whitelistedNumbers = values }
            editingList = null
        }) { Text("保存") } }, dismissButton = { TextButton(onClick = { editingList = null }) { Text("取消") } })
    if (recycle != null) AlertDialog(onDismissRequest = { recycle = null }, title = { Text("短信回收站") },
        text = { Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
            Text("最近50个回收站会话。点击进入详情可恢复；本页不执行永久删除。")
            if (recycle!!.isEmpty()) Text("暂无回收站会话")
            recycle!!.forEach { conversation -> TextButton(onClick = {
                context.startActivity(Intent(context, org.fossify.messages.activities.ThreadActivity::class.java).apply {
                    putExtra(THREAD_ID, conversation.threadId); putExtra(THREAD_TITLE, conversation.title); putExtra(IS_RECYCLE_BIN, true)
                })
                recycle = null
            }) { Text(conversation.title) } }
        } }, confirmButton = { TextButton(onClick = { recycle = null }) { Text("关闭") } })
}
