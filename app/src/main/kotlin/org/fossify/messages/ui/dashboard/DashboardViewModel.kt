package org.fossify.messages.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.dashboard.model.DashboardStats
import org.fossify.messages.ui.usecase.GetDashboardStatsUseCase

class DashboardViewModel(
    private val getDashboardStatsUseCase: GetDashboardStatsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<DashboardStats>>(UiState.Idle)
    val uiState: StateFlow<UiState<DashboardStats>> = _uiState.asStateFlow()
    private var pendingRefresh: Job? = null
    private var loadJob: Job? = null

    fun refreshAfterChange() {
        pendingRefresh?.cancel()
        loadJob?.cancel()
        pendingRefresh = viewModelScope.launch {
            delay(300)
            loadJob = viewModelScope.launch {
                runCatching { getDashboardStatsUseCase() }
                    .onSuccess { _uiState.value = UiState.Success(it) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        if (_uiState.value !is UiState.Success) {
                            _uiState.value = UiState.Error("加载失败，请重试", it)
                        }
                    }
            }
        }
    }

    fun loadStats() {
        pendingRefresh?.cancel()
        loadJob?.cancel()
        _uiState.value = UiState.Loading
        loadJob = viewModelScope.launch {
            try {
                val stats = getDashboardStatsUseCase()
                _uiState.value = UiState.Success(stats)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load dashboard stats", e)
            }
        }
    }

    suspend fun refreshSync(): DashboardStats {
        pendingRefresh?.cancel()
        loadJob?.cancel()
        val stats = getDashboardStatsUseCase()
        _uiState.value = UiState.Success(stats)
        return stats
    }
}
