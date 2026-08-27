package org.fossify.messages.ui.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.dashboard.model.DashboardStats
import org.fossify.messages.ui.usecase.GetDashboardStatsUseCase

class DashboardViewModel(
    private val getDashboardStatsUseCase: GetDashboardStatsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<DashboardStats>>(UiState.Idle)
    val uiState: StateFlow<UiState<DashboardStats>> = _uiState.asStateFlow()

    fun loadStats() {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                val stats = getDashboardStatsUseCase()
                _uiState.value = UiState.Success(stats)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load dashboard stats", e)
            }
        }
    }

    suspend fun refreshSync(): DashboardStats {
        val stats = getDashboardStatsUseCase()
        _uiState.value = UiState.Success(stats)
        return stats
    }
}
