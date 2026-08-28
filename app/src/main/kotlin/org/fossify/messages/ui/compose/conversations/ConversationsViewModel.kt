package org.fossify.messages.ui.compose.conversations

import android.app.Application
import android.app.role.RoleManager
import android.os.Build
import android.provider.Telephony
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.models.Conversation

data class ConversationsUiState(
    val conversations: List<Conversation> = emptyList(),
    val isLoading: Boolean = false,
    val isDefaultSmsApp: Boolean = true,
    val initialLoaded: Boolean = false
)

class ConversationsViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()

    init {
        refresh(isInitial = true)
    }

    fun refresh(isInitial: Boolean = false) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            val isDefault = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(RoleManager::class.java)
                roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
            } else {
                Telephony.Sms.getDefaultSmsPackage(context) == context.packageName
            }

            if (isInitial || !_uiState.value.initialLoaded) {
                _uiState.value = _uiState.value.copy(isLoading = true, isDefaultSmsApp = isDefault)
            } else {
                _uiState.value = _uiState.value.copy(isDefaultSmsApp = isDefault)
            }

            val list = withContext(Dispatchers.IO) {
                runCatching { context.getConversations() }.getOrDefault(arrayListOf())
            }

            _uiState.value = _uiState.value.copy(
                conversations = list,
                isLoading = false,
                initialLoaded = true
            )
        }
    }
}
