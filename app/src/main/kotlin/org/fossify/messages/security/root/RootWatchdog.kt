package org.fossify.messages.security.root

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

/** Detached root shell watchdog. Does not change ROM settings or clear a force-stopped package. */
object RootWatchdog {
    suspend fun start(context: Context): Boolean = withContext(Dispatchers.IO) {
        val pkg = context.packageName
        require(Regex("[A-Za-z0-9_.]+").matches(pkg))
        val directory = File(context.filesDir, "root-watchdog").apply { mkdirs() }
        val script = File(directory, "watch.sh")
        val enabled = File(directory, "enabled")
        val lock = File(directory, "lock")
        val heartbeat = File(directory, "heartbeat")
        val user = android.os.Process.myUid() / 100000
        val service = "$pkg/org.fossify.messages.services.SmsKeepAliveService"
        script.writeText("""
            #!/system/bin/sh
            mkdir '${lock.absolutePath}' 2>/dev/null || exit 0
            trap "rmdir '${lock.absolutePath}' 2>/dev/null" EXIT
            while [ -f '${enabled.absolutePath}' ]; do
                date +%s > '${heartbeat.absolutePath}'
                state=${'$'}(dumpsys package '$pkg' | grep 'User $user:' | head -n 1)
                case "${'$'}state" in
                    *stopped=true*) rm -f '${enabled.absolutePath}'; break ;;
                    *stopped=false*)
                        if ! pidof '$pkg' >/dev/null 2>&1; then
                            am start-foreground-service --user $user -n '$service' >/dev/null 2>&1
                        fi ;;
                esac
                sleep 60
            done
        """.trimIndent())
        enabled.writeText("enabled")
        // Root shell lives outside the app process; all output is discarded, no SMS data is logged.
        // Stale lock is removed only when the previous PID is no longer alive.
        val pidFile = File(directory, "pid")
        val command = "if [ -f '${pidFile.absolutePath}' ] && kill -0 \$(cat '${pidFile.absolutePath}') 2>/dev/null && tr '\\000' ' ' < /proc/\$(cat '${pidFile.absolutePath}')/cmdline | grep -F '${script.absolutePath}' >/dev/null; then exit 0; fi; " +
            "rm -rf '${lock.absolutePath}'; nohup sh '${script.absolutePath}' </dev/null >/dev/null 2>&1 & echo \$! > '${pidFile.absolutePath}'"
        try {
            val process = ProcessBuilder("su", "-c", command).redirectErrorStream(true).start()
            val finished = process.waitFor(8, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            finished && process.exitValue() == 0
        } catch (_: Exception) { false }
    }

    fun observedStatus(context: Context): String {
        val directory = File(context.filesDir, "root-watchdog")
        if (!File(directory, "enabled").exists()) return "未启用或已停止"
        val lastBeat = runCatching { File(directory, "heartbeat").readText().trim().toLong() }.getOrNull()
            ?: return "已请求启动，尚无心跳"
        val age = System.currentTimeMillis() / 1000 - lastBeat
        return if (age in 0..90) "最近90秒有守护心跳" else "心跳过期或设备时间变化，存活未确认"
    }

    fun stop(context: Context) {
        File(context.filesDir, "root-watchdog/enabled").delete()
        File(context.filesDir, "root-watchdog/heartbeat").delete()
    }
}
