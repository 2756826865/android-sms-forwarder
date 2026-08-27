package org.fossify.messages.observability.crash

import android.content.Context
import org.fossify.messages.compatibility.CompatibilityManager
import org.fossify.messages.observability.log.RingBufferLogManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.PrintWriter
import java.io.StringWriter

data class CrashSnapshot(
    val timestamp: Long,
    val exceptionClass: String,
    val exceptionMessage: String,
    val stackTraceSummary: String,
    val recentLogs: List<String>,
    val deviceBrand: String,
    val romName: String
)

/**
 * 未捕获异常与崩溃诊断处理器
 */
class CrashDiagnosticsHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            captureCrashSnapshot(thread, throwable)
        } catch (_: Exception) {
            // 安全守卫：绝不允许崩溃捕获逻辑自身抛出二次异常
        } finally {
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun captureCrashSnapshot(thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val fullStackTrace = sw.toString()
        val topStackFrames = fullStackTrace.lines().take(15).joinToString("\n")

        val recentLogs = RingBufferLogManager.getRecentLogs(20).map {
            "[${it.level}] [${it.tag}] ${it.message}"
        }

        val profile = CompatibilityManager.deviceProfile

        val json = JSONObject().apply {
            put("timestamp", System.currentTimeMillis())
            put("threadName", thread.name)
            put("exceptionClass", throwable.javaClass.name)
            put("exceptionMessage", throwable.message ?: "No message")
            put("stackTraceSummary", topStackFrames)
            put("deviceBrand", profile.brand.name)
            put("romName", profile.romName)
            put("recentLogs", JSONArray(recentLogs))
        }

        val prefs = context.getSharedPreferences(PREFS_CRASH, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LAST_CRASH, json.toString()).commit()
    }

    companion object {
        private const val PREFS_CRASH = "sms_forwarder_crash_diagnostics"
        private const val KEY_LAST_CRASH = "last_crash_json"

        fun install(context: Context) {
            val currentHandler = Thread.getDefaultUncaughtExceptionHandler()
            if (currentHandler !is CrashDiagnosticsHandler) {
                val handler = CrashDiagnosticsHandler(context.applicationContext, currentHandler)
                Thread.setDefaultUncaughtExceptionHandler(handler)
            }
        }

        fun getLastCrashSnapshot(context: Context): CrashSnapshot? {
            val prefs = context.getSharedPreferences(PREFS_CRASH, Context.MODE_PRIVATE)
            val rawJson = prefs.getString(KEY_LAST_CRASH, null) ?: return null

            return try {
                val obj = JSONObject(rawJson)
                val logsList = mutableListOf<String>()
                val logsArr = obj.optJSONArray("recentLogs")
                if (logsArr != null) {
                    for (i in 0 until logsArr.length()) {
                        logsList.add(logsArr.optString(i))
                    }
                }

                CrashSnapshot(
                    timestamp = obj.optLong("timestamp"),
                    exceptionClass = obj.optString("exceptionClass"),
                    exceptionMessage = obj.optString("exceptionMessage"),
                    stackTraceSummary = obj.optString("stackTraceSummary"),
                    recentLogs = logsList,
                    deviceBrand = obj.optString("deviceBrand"),
                    romName = obj.optString("romName")
                )
            } catch (e: Exception) {
                null
            }
        }

        fun clearCrashSnapshot(context: Context) {
            context.getSharedPreferences(PREFS_CRASH, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_LAST_CRASH)
                .apply()
        }
    }
}
