package org.fossify.messages.ui.common

/**
 * 统一 UI 状态机封装
 */
sealed class UiState<out T> {
    object Idle : UiState<Nothing>()
    object Loading : UiState<Nothing>()
    data class Success<out T>(val data: T) : UiState<T>()
    data class Error(val message: String, val throwable: Throwable? = null) : UiState<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isLoading: Boolean get() = this is Loading

    fun getOrNull(): T? = (this as? Success)?.data
}
