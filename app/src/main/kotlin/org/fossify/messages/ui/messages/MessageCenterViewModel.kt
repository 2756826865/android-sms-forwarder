package org.fossify.messages.ui.messages

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
    private var pendingRefresh: Job? = null
    private var loadJob: Job? = null

    fun refreshAfterChange() {
        pendingRefresh?.cancel()
        loadJob?.cancel()
        pendingRefresh = viewModelScope.launch {
            delay(300)
            loadJob = viewModelScope.launch {
                runCatching { getMessageHistoryUseCase(50) }
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

    fun loadMessageHistory() {
        pendingRefresh?.cancel()
        loadJob?.cancel()
        _uiState.value = UiState.Loading
        loadJob = viewModelScope.launch {
            try {
                val list = getMessageHistoryUseCase(50)
                _uiState.value = UiState.Success(list)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = UiState.Error(e.message ?: "Failed to load message history", e)
            }
        }
    }
}
