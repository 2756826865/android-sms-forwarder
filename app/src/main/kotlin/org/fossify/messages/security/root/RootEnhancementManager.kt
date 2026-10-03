package org.fossify.messages.security.root

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * Root 增强模式的唯一命令入口。
 *
 * 只允许固定的诊断和修复命令。这里不接受来自界面的任意 Shell 文本，避免把
 * Root 权限变成通用命令执行器。修改动作执行后必须回读验证。
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

    /** Fixed read-back commands; command acceptance alone does not prove a setting took effect. */
    fun verificationCommand(index: Int, packageName: String): String = when (index) {
        0 -> "cmd role get-role-holders --user current android.app.role.SMS"
        1 -> "settings get secure sms_default_application"
        2 -> "appops get $packageName WRITE_SMS"
        3 -> "appops get $packageName RECEIVE_SMS"
        4 -> "appops get $packageName SEND_SMS"
        5 -> "appops get $packageName RUN_ANY_IN_BACKGROUND"
        6 -> "dumpsys deviceidle whitelist"
        else -> error("Unknown fixed action")
    }

    fun verificationMatches(index: Int, packageName: String, output: String): Boolean = when (index) {
        0, 1 -> output.lineSequence().any { it.trim() == packageName }
        2, 3, 4, 5 -> {
            val operation = listOf("WRITE_SMS", "RECEIVE_SMS", "SEND_SMS", "RUN_ANY_IN_BACKGROUND")[index - 2]
            Regex("(?m)^\\s*${operation}:\\s*allow(?:[;\\s]|$)").containsMatchIn(output)
        }
        6 -> output.lineSequence().any { line -> line.split(',').any { it.trim() == packageName } }
        else -> false
    }

    fun brandGuidance(): String {
        val brand = (Build.MANUFACTURER + " " + Build.BRAND).lowercase(java.util.Locale.ROOT)
        return when {
            "xiaomi" in brand || "redmi" in brand -> "检查自启动、后台无限制和锁屏网络；系统短信网络发送尚未适配"
            "honor" in brand -> "检查应用启动管理中的自动启动、关联启动、后台活动"
            "huawei" in brand -> "检查应用启动管理和电池优化；不同 EMUI/HarmonyOS 版本需真机核验"
            listOf("oppo", "oneplus", "realme").any { it in brand } -> "检查自启动、后台活动及系统发送确认；不自动改写未知厂商设置"
            "vivo" in brand || "iqoo" in brand -> "检查自启动、高耗电后台和后台耗电管理"
            "meizu" in brand -> "检查后台管理、自启动和系统验证码短信的可读性"
            else -> "检查电池优化、后台权限和默认短信角色"
        }
    }

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
            "默认短信角色" to "cmd role get-role-holders --user current android.app.role.SMS",
            "底层短信路由" to "settings get secure sms_default_application",
            "短信写入权限" to "appops get $packageName WRITE_SMS",
            "短信接收权限" to "appops get $packageName RECEIVE_SMS",
            "短信发送权限" to "appops get $packageName SEND_SMS",
            "后台运行权限" to "appops get $packageName RUN_ANY_IN_BACKGROUND",
            "电池白名单" to "dumpsys deviceidle whitelist"
        )
        val lines = buildList {
            add("设备：${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
            add("应用：$packageName")
            add("品牌适配建议：${brandGuidance()}")
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
        val details = commands.mapIndexed { index, (label, command) ->
            val result = runFixedCommand(command, timeoutSeconds = 10)
            val verified = if (result?.exitCode == 0) {
                runFixedCommand(verificationCommand(index, packageName), timeoutSeconds = 10)
            } else null
            label to (verified?.exitCode == 0 && verificationMatches(index, packageName, verified.output))
        }
        val successCount = details.count { it.second }
        FixResult(successCount, details.size, details)
    }

    /** Restore declared SMS read access through the framework, never read or mutate its database file. */
    suspend fun restoreSmsReadAccess(context: Context): Boolean = withContext(Dispatchers.IO) {
        val packageName = context.packageName
        require(Regex("[A-Za-z0-9_.]+").matches(packageName))
        val user = android.os.Process.myUid() / 100000
        runFixedCommand("pm grant --user $user $packageName android.permission.READ_SMS", 10)
        runFixedCommand("appops set --user $user $packageName READ_SMS allow", 10)
        context.checkSelfPermission(android.Manifest.permission.READ_SMS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
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
