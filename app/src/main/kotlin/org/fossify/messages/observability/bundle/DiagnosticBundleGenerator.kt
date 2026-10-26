package org.fossify.messages.observability.bundle

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.fossify.messages.compatibility.CompatibilityManager
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.observability.crash.CrashDiagnosticsHandler
import org.fossify.messages.observability.log.RingBufferLogManager
import org.fossify.messages.observability.perf.PerformanceTracker
import org.fossify.messages.security.audit.SecurityAuditManager
import org.fossify.messages.security.crypto.KeyStoreHelper
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

data class DiagnosticBundleResult(
    val exportTimestamp: Long,
    val isEncrypted: Boolean,
    val bundleContent: String,
    val checksumSha256: String,
    val summaryItemCount: Int
)

/**
 * 加密诊断包生成器 (用于一键导出运维与排障数据)
 */
object DiagnosticBundleGenerator {

    suspend fun generateBundle(
        context: Context,
        encryptWithKeyStore: Boolean = true
    ): DiagnosticBundleResult = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val db = context.getMessagesDB()

        val root = JSONObject().apply {
            put("bundleVersion", "1.2.0")
            put("generatedAt", now)
        }

        // Read-only checks: never request permissions, change roles or send test messages.
        val collectionStatus = JSONObject()
        root.put("collectionStatus", collectionStatus)
        fun collectionError(section: String, error: Throwable) {
            collectionStatus.put(section, JSONObject().put("status", "collection_failed")
                .put("errorType", error.javaClass.simpleName))
        }
        root.put("application", JSONObject().apply {
            put("packageName", context.packageName)
            put("versionName", org.fossify.messages.BuildConfig.VERSION_NAME)
            put("versionCode", org.fossify.messages.BuildConfig.VERSION_CODE)
        })
        val permissions = JSONObject()
        listOf("READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_PHONE_STATE",
            "READ_CONTACTS", "RECEIVE_MMS", "RECEIVE_WAP_PUSH").forEach { name ->
            permissions.put(name, context.checkSelfPermission("android.permission.$name") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            permissions.put("POST_NOTIFICATIONS", context.checkSelfPermission("android.permission.POST_NOTIFICATIONS") ==
                android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
        root.put("permissions", permissions)
        root.put("smsRole", JSONObject().apply {
            try {
                val holder = android.provider.Telephony.Sms.getDefaultSmsPackage(context)
                put("defaultPackage", holder ?: JSONObject.NULL)
                put("isDefault", holder == context.packageName)
                put("status", if (holder == null) "unknown" else "observed")
            } catch (e: Exception) {
                put("status", "collection_failed")
                put("errorType", e.javaClass.simpleName)
            }
            put("vendorRestrictions", "not_verified")
        })

        // 1. 设备画像与兼容状态
        val profile = CompatibilityManager.deviceProfile
        val oemObj = JSONObject().apply {
            put("brand", profile.brand.name)
            put("manufacturer", profile.manufacturer)
            put("model", profile.model)
            put("romName", profile.romName)
            put("romVersion", profile.romVersion.ifBlank { "unknown" })
            put("androidVersion", android.os.Build.VERSION.RELEASE)
            put("apiLevel", profile.apiLevel)
            put("isBatteryOptimized", !CompatibilityManager.backgroundCompat.isBatteryOptimizationIgnored(context))
        }
        root.put("deviceProfile", oemObj)

        // 2. 性能遥测数据
        val perfMap = PerformanceTracker.getAllSummaries()
        val perfObj = JSONObject()
        perfMap.forEach { (name, summary) ->
            perfObj.put(name, JSONObject().apply {
                put("count", summary.count)
                put("avgMs", "%.2f".format(summary.avgDurationMs))
                put("minMs", summary.minDurationMs)
                put("maxMs", summary.maxDurationMs)
            })
        }
        root.put("performanceMetrics", perfObj)
        root.put("performanceScope", "Only explicitly instrumented operations in the current process; not all SMS or channel activity")

        // 3. 自愈审计记录
        val recoveryRecords = runCatching { db.RecoveryRecordDao().queryLatest(50) }
            .onFailure { collectionError("recoveryAudit", it) }.getOrDefault(emptyList())
        val recArr = JSONArray()
        recoveryRecords.forEach { rec ->
            recArr.put(JSONObject().apply {
                put("scanTime", rec.scanTime)
                put("triggerSource", rec.triggerSource)
                put("objectType", rec.objectType)
                put("actionTaken", rec.actionTaken)
                put("details", rec.detailMessage)
            })
        }
        root.put("recoveryAudit", recArr)

        // 4. Outbox 队列深度
        val outboxObj = JSONObject().apply {
            put("pendingCount", runCatching { db.OutboxTaskDao().getPendingTaskCount() }
                .onFailure { collectionError("outboxStatus.pendingCount", it) }.getOrNull() ?: JSONObject.NULL)
            put("retryCount", runCatching { db.OutboxTaskDao().getRetryTaskCount() }
                .onFailure { collectionError("outboxStatus.retryCount", it) }.getOrNull() ?: JSONObject.NULL)
            put("failedCount", runCatching { db.OutboxTaskDao().getFailedTaskCount() }
                .onFailure { collectionError("outboxStatus.failedCount", it) }.getOrNull() ?: JSONObject.NULL)
        }
        root.put("outboxStatus", outboxObj)

        // 5. 内存环形日志 (最近 100 条脱敏日志)
        val logs = RingBufferLogManager.getRecentLogs(100)
        val logsArr = JSONArray()
        logs.forEach { log ->
            logsArr.put(JSONObject().put("timestamp", log.timestamp)
                .put("level", log.level.name).put("tag", log.tag).put("message", log.message))
        }
        root.put("recentLogs", logsArr)

        // 6. 安全审计事件 (脱敏摘要)
        val securityEvents = SecurityAuditManager.getRecentAuditEvents(30)
        val secArr = JSONArray()
        securityEvents.forEach { sec ->
            secArr.put(JSONObject().put("timestamp", sec.timestamp)
                .put("eventType", sec.eventType.name).put("targetHash", sec.targetKeyHash))
        }
        root.put("securityAuditEvents", secArr)

        // 7. 最近崩溃快照 (如果有)
        CrashDiagnosticsHandler.getLastCrashSnapshot(context)?.let { crash ->
            val crashObj = JSONObject().apply {
                put("timestamp", crash.timestamp)
                put("exceptionClass", crash.exceptionClass)
                put("exceptionMessage", crash.exceptionMessage)
                put("stackTraceSummary", crash.stackTraceSummary)
            }
            root.put("lastCrash", crashObj)
        }

        listOf("performanceMetrics" to "process_memory_no_samples",
            "recentLogs" to "process_memory_no_events", "securityAuditEvents" to "process_memory_no_events",
            "recoveryAudit" to "no_records").forEach { (section, emptyReason) ->
            if (!collectionStatus.has(section)) {
                val value = root.get(section)
                val empty = when (value) {
                    is JSONObject -> value.length() == 0
                    is JSONArray -> value.length() == 0
                    else -> false
                }
                collectionStatus.put(section, JSONObject().put("status", if (empty) emptyReason else "collected"))
            }
        }
        root.put("sendLogs", SendLogCollector.collect(context))
        val plainJson = root.toString(2)
        val checksum = calculateSha256(plainJson)

        val finalContent = if (encryptWithKeyStore) {
            KeyStoreHelper.encrypt(plainJson)
        } else {
            plainJson
        }

        DiagnosticBundleResult(
            exportTimestamp = now,
            isEncrypted = encryptWithKeyStore,
            bundleContent = finalContent,
            checksumSha256 = checksum,
            summaryItemCount = recoveryRecords.size + logs.size + securityEvents.size
        )
    }

    private fun calculateSha256(text: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            md.digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            "unknown_checksum"
        }
    }
}
