package org.fossify.messages.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.remote.model.RemoteControlCenterState
import org.fossify.messages.ui.remote.usecase.RemoteCommandHistoryUseCase

/**
 * 远程控制中心 ViewModel
 */
class RemoteControlViewModel(
    private val remoteCommandHistoryUseCase: RemoteCommandHistoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<RemoteControlCenterState>>(UiState.Idle)
    val uiState: StateFlow<UiState<RemoteControlCenterState>> = _uiState.asStateFlow()

    fun loadRemoteControl() {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                val state = remoteCommandHistoryUseCase.getFullState(50)
                _uiState.value = UiState.Success(state)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load remote control center", e)
            }
        }
    }
}
