package org.fossify.messages.ui.messages

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.messages.model.MessageHistoryItem
import org.fossify.messages.ui.messages.usecase.GetMessageHistoryUseCase

/**
 * 消息中心 ViewModel
 */
class MessageCenterViewModel(
    private val getMessageHistoryUseCase: GetMessageHistoryUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow<UiState<List<MessageHistoryItem>>>(UiState.Idle)
    val uiState: StateFlow<UiState<List<MessageHistoryItem>>> = _uiState.asStateFlow()

    fun loadMessageHistory() {
        _uiState.value = UiState.Loading
        viewModelScope.launch {
            try {
                val list = getMessageHistoryUseCase(50)
                _uiState.value = UiState.Success(list)
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load message history", e)
            }
        }
    }
}
