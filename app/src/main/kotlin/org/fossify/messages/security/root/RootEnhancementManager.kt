package org.fossify.messages.security.root

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Root 增强模式的唯一命令入口。
 *
 * 当前阶段只允许固定的只读检测命令。这里不接受来自界面的任意 Shell 文本，避免把
 * Root 权限变成通用命令执行器。任何后续修改型操作都必须单独实现白名单动作与回滚。
 */
object RootEnhancementManager {

    data class RootStatus(
        val available: Boolean,
        val granted: Boolean,
        val detail: String
    )

    data class DiagnosticReport(
        val rootStatus: RootStatus,
        val lines: List<String>
    )

    data class FixResult(
        val successCount: Int,
        val totalCount: Int,
        val details: List<Pair<String, Boolean>>
    )

    fun getStandardFixCommands(packageName: String): List<Pair<String, String>> = listOf(
        "绑定默认短信角色 (SMS Role)" to "cmd role add-role-holder --user current android.app.role.SMS $packageName",
        "切换底层短信路由 (Settings)" to "settings put secure sms_default_application $packageName",
        "放行短信写入权限 (WRITE_SMS)" to "appops set $packageName WRITE_SMS allow",
        "放行短信接收广播 (RECEIVE_SMS)" to "appops set $packageName RECEIVE_SMS allow",
        "放行短信发送权限 (SEND_SMS)" to "appops set $packageName SEND_SMS allow",
        "放行后台执行权限 (RUN_ANY_IN_BACKGROUND)" to "appops set $packageName RUN_ANY_IN_BACKGROUND allow",
        "加入系统电池白名单" to "dumpsys deviceidle whitelist +$packageName"
    )

    suspend fun checkRoot(): RootStatus = withContext(Dispatchers.IO) {
        val result = runFixedCommand("id", timeoutSeconds = 8)
        when {
            result == null -> RootStatus(false, false, "未找到 su，或 Root 管理器未响应")
            result.exitCode == 0 && result.output.contains("uid=0") ->
                RootStatus(true, true, "Root 已授权（uid=0）")
            else -> RootStatus(true, false, result.output.ifBlank { "Root 请求被拒绝或已超时" })
        }
    }

    suspend fun collectReadOnlyDiagnostics(context: Context): DiagnosticReport = withContext(Dispatchers.IO) {
        val status = checkRoot()
        if (!status.granted) return@withContext DiagnosticReport(status, emptyList())

        val packageName = context.packageName
        val commands = listOf(
            "默认短信角色" to "cmd role get-role-holders android.app.role.SMS",
            "底层短信路由" to "settings get secure sms_default_application",
            "短信写入权限" to "appops get $packageName WRITE_SMS",
            "短信接收权限" to "appops get $packageName RECEIVE_SMS",
            "后台运行权限" to "appops get $packageName RUN_ANY_IN_BACKGROUND",
            "电池白名单" to "dumpsys deviceidle whitelist"
        )
        val lines = buildList {
            add("设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            add("应用：$packageName")
            commands.forEach { (label, command) ->
                val result = runFixedCommand(command, timeoutSeconds = 10)
                val output = result?.output?.trim().orEmpty()
                val safeOutput = if (label == "电池白名单") {
                    output.lineSequence().filter { it.contains(packageName) }.joinToString().ifBlank { "未发现应用" }
                } else {
                    output.ifBlank { "无返回" }.take(500)
                }
                add("$label：$safeOutput")
            }
        }
        DiagnosticReport(status, lines)
    }

    suspend fun applyRootFix(context: Context): FixResult = withContext(Dispatchers.IO) {
        val packageName = context.packageName
        val commands = getStandardFixCommands(packageName)
        val details = commands.map { (label, command) ->
            val result = runFixedCommand(command, timeoutSeconds = 10)
            label to (result?.exitCode == 0)
        }
        val successCount = details.count { it.second }
        FixResult(successCount, details.size, details)
    }

    private data class CommandResult(val exitCode: Int, val output: String)

    private fun runFixedCommand(command: String, timeoutSeconds: Long): CommandResult? = runCatching {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return@runCatching CommandResult(-1, "命令执行超时")
        }
        CommandResult(process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
    }.getOrNull()
}
