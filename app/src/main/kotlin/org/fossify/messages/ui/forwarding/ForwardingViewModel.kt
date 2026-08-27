package org.fossify.messages.ui.forwarding

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.forwarding.model.ForwardingCenterState
import org.fossify.messages.ui.forwarding.usecase.GetForwardingCenterStateUseCase

/**
 * 转发中心 ViewModel
 */
class ForwardingViewModel(
    private val getForwardingCenterStateUseCase: GetForwardingCenterStateUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<ForwardingCenterState>>(UiState.Idle)
    val uiState: StateFlow<UiState<ForwardingCenterState>> = _uiState.asStateFlow()

    fun loadForwardingCenter() {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                val state = getForwardingCenterStateUseCase()
                _uiState.value = UiState.Success(state)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load forwarding center", e)
            }
        }
    }
}
