package org.fossify.messages.ui.diagnostics

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fossify.messages.compatibility.CompatibilityManager
import org.fossify.messages.observability.bundle.DiagnosticBundleGenerator
import org.fossify.messages.observability.bundle.DiagnosticBundleResult
import org.fossify.messages.observability.log.RingBufferLogManager
import org.fossify.messages.observability.perf.PerformanceTracker
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.diagnostics.model.DiagnosticsState
import org.fossify.messages.ui.usecase.GetRecoveryRecordsUseCase
import org.fossify.messages.ui.usecase.RunManualRecoveryUseCase

/**
 * 诊断与可观测性中心 ViewModel
 */
class DiagnosticsViewModel(
    private val context: Context,
    private val getRecoveryRecordsUseCase: GetRecoveryRecordsUseCase,
    private val runManualRecoveryUseCase: RunManualRecoveryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<DiagnosticsState>>(UiState.Idle)
    val uiState: StateFlow<UiState<DiagnosticsState>> = _uiState.asStateFlow()

    fun loadDiagnostics() {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                val records = getRecoveryRecordsUseCase(50)
                val profile = CompatibilityManager.deviceProfile
                val isBatteryOptimized = !CompatibilityManager.backgroundCompat.isBatteryOptimizationIgnored(context)
                val isNotificationEnabled = CompatibilityManager.backgroundCompat.isNotificationEnabled(context)
                val tips = CompatibilityManager.backgroundCompat.getBrandTips()
                val perfSummaries = PerformanceTracker.getAllSummaries()
                val recentLogs = RingBufferLogManager.getRecentLogs(50)

                val state = DiagnosticsState(
                    recoveryRecords = records,
                    deviceProfile = profile,
                    isBatteryOptimized = isBatteryOptimized,
                    isNotificationEnabled = isNotificationEnabled,
                    brandTips = tips,
                    isScanning = false,
                    performanceSummaries = perfSummaries,
                    recentLogs = recentLogs,
                    lastUpdated = System.currentTimeMillis()
                )
                _uiState.value = UiState.Success(state)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load diagnostics", e)
            }
        }
    }

    fun triggerManualScan() {
        val currentState = (_uiState.value as? UiState.Success)?.data
        _uiState.value = UiState.Success(currentState?.copy(isScanning = true) ?: DiagnosticsState(isScanning = true))

        viewModelScope.launch {
            try {
                val summary = PerformanceTracker.trace(PerformanceTracker.METRIC_RECOVERY_SCAN) {
                    runManualRecoveryUseCase()
                }
                val records = getRecoveryRecordsUseCase(50)
                val profile = CompatibilityManager.deviceProfile
                val isBatteryOptimized = !CompatibilityManager.backgroundCompat.isBatteryOptimizationIgnored(context)
                val isNotificationEnabled = CompatibilityManager.backgroundCompat.isNotificationEnabled(context)
                val tips = CompatibilityManager.backgroundCompat.getBrandTips()
                val perfSummaries = PerformanceTracker.getAllSummaries()
                val recentLogs = RingBufferLogManager.getRecentLogs(50)

                val updatedState = DiagnosticsState(
                    recoveryRecords = records,
                    deviceProfile = profile,
                    isBatteryOptimized = isBatteryOptimized,
                    isNotificationEnabled = isNotificationEnabled,
                    brandTips = tips,
                    lastScanSummary = summary,
                    isScanning = false,
                    performanceSummaries = perfSummaries,
                    recentLogs = recentLogs,
                    lastUpdated = System.currentTimeMillis()
                )
                _uiState.value = UiState.Success(updatedState)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Manual recovery scan failed", e)
            }
        }
    }

    fun exportDiagnosticBundle(encryptWithKeyStore: Boolean = false) {
        viewModelScope.launch {
            try {
                val bundleResult = DiagnosticBundleGenerator.generateBundle(context, encryptWithKeyStore)
                val currentState = (_uiState.value as? UiState.Success)?.data
                if (currentState != null) {
                    _uiState.value = UiState.Success(currentState.copy(lastExportedBundle = bundleResult))
                }
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to export diagnostic bundle", e)
            }
        }
    }
}
