package org.fossify.messages.security.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.messages.security.root.RootEnhancementManager
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit

/**
 * Shizuku 免 Root 特权管理中心。
 *
 * 通过 Shizuku 提供的 ADB (uid 2000) 权限，实现免 Root 场景下静默绑定 SMS Role、
 * 写入短信路由、放行 AppOps 短信广播与注入系统电池白名单。
 */
object ShizukuEnhancementManager {

    enum class ShizukuState(val description: String) {
        NOT_INSTALLED("未安装 Shizuku 应用"),
        NOT_RUNNING("Shizuku 服务未运行（请启动无线调试）"),
        WAITING_PERMISSION("Shizuku 服务就绪，等待用户授权"),
        READY("Shizuku 特权已就绪 (ADB 级)")
    }

    data class ShizukuStatus(
        val state: ShizukuState,
        val detail: String
    )

    data class ShizukuDiagnosticReport(
        val status: ShizukuStatus,
        val lines: List<String>
    )

    fun isInstalled(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.getPackageInfo(
                "moe.shizuku.privileged.api",
                PackageManager.PackageInfoFlags.of(0)
            ) != null
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0) != null
        }
    }.getOrDefault(false)

    fun checkStatus(context: Context): ShizukuStatus {
        if (!isInstalled(context)) {
            return ShizukuStatus(ShizukuState.NOT_INSTALLED, "未安装 Shizuku 客户端")
        }

        val isRunning = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!isRunning) {
            return ShizukuStatus(ShizukuState.NOT_RUNNING, "Shizuku 未运行 · 请在应用中启动无线调试")
        }

        val isGranted = runCatching {
            if (Shizuku.isPreV11()) {
                false
            } else {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }
        }.getOrDefault(false)

        return if (isGranted) {
            val uid = runCatching { Shizuku.getUid() }.getOrDefault(2000)
            val version = runCatching { Shizuku.getVersion() }.getOrDefault(0)
            ShizukuStatus(ShizukuState.READY, "Shizuku 已授权 (v$version · uid=$uid)")
        } else {
            ShizukuStatus(ShizukuState.WAITING_PERMISSION, "Shizuku 服务就绪，点击申请权限")
        }
    }

    fun requestPermission(requestCode: Int = 10086) {
        runCatching {
            if (!Shizuku.isPreV11()) {
                Shizuku.requestPermission(requestCode)
            }
        }
    }

    suspend fun collectShizukuDiagnostics(context: Context): ShizukuDiagnosticReport = withContext(Dispatchers.IO) {
        val status = checkStatus(context)
        if (status.state != ShizukuState.READY) {
            return@withContext ShizukuDiagnosticReport(status, emptyList())
        }

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
            add("执行引擎：Shizuku (ADB Shell 环境)")
            add("应用：$packageName")
            commands.forEach { (label, command) ->
                val result = runFixedShizukuCommand(command, timeoutSeconds = 8)
                val output = result?.output?.trim().orEmpty()
                val safeOutput = if (label == "电池白名单") {
                    output.lineSequence().filter { it.contains(packageName) }.joinToString().ifBlank { "未发现应用" }
                } else {
                    output.ifBlank { "无返回" }.take(500)
                }
                add("$label：$safeOutput")
            }
        }
        ShizukuDiagnosticReport(status, lines)
    }

    suspend fun applyShizukuFix(context: Context): RootEnhancementManager.FixResult = withContext(Dispatchers.IO) {
        val packageName = context.packageName
        val commands = RootEnhancementManager.getStandardFixCommands(packageName)
        val details = commands.map { (label, command) ->
            val result = runFixedShizukuCommand(command, timeoutSeconds = 10)
            label to (result?.exitCode == 0)
        }
        val successCount = details.count { it.second }
        RootEnhancementManager.FixResult(successCount, details.size, details)
    }

    private data class ShizukuCommandResult(val exitCode: Int, val output: String)

    private val newProcessMethod by lazy {
        runCatching {
            Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply { isAccessible = true }
        }.getOrNull()
    }

    private fun runFixedShizukuCommand(command: String, timeoutSeconds: Long): ShizukuCommandResult? = runCatching {
        val method = newProcessMethod ?: return@runCatching null
        val process = method.invoke(
            null,
            arrayOf("sh", "-c", command),
            null,
            null
        ) as java.lang.Process

        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            return@runCatching ShizukuCommandResult(-1, "Shizuku 命令执行超时")
        }
        ShizukuCommandResult(process.exitValue(), process.inputStream.bufferedReader().use { it.readText() })
    }.getOrNull()
}
