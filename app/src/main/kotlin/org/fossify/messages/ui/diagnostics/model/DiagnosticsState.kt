package org.fossify.messages.ui.diagnostics.model

import org.fossify.messages.compatibility.model.DeviceProfile
import org.fossify.messages.models.RecoveryRecordEntity
import org.fossify.messages.models.RecoverySummary
import org.fossify.messages.observability.bundle.DiagnosticBundleResult
import org.fossify.messages.observability.log.LogEntry
import org.fossify.messages.observability.perf.PerformanceMetricSummary

/**
 * 诊断与可观测性中心 UI 状态模型
 */
data class DiagnosticsState(
    val recoveryRecords: List<RecoveryRecordEntity> = emptyList(),
    val deviceProfile: DeviceProfile? = null,
    val isBatteryOptimized: Boolean = false,
    val isNotificationEnabled: Boolean = true,
    val brandTips: List<String> = emptyList(),
    val lastScanSummary: RecoverySummary? = null,
    val isScanning: Boolean = false,
    val performanceSummaries: Map<String, PerformanceMetricSummary> = emptyMap(),
    val recentLogs: List<LogEntry> = emptyList(),
    val lastExportedBundle: DiagnosticBundleResult? = null,
    val lastUpdated: Long = System.currentTimeMillis()
)
