package org.fossify.messages.ui.compose.conversations

import android.app.Application
import android.app.role.RoleManager
import android.os.Build
import android.provider.Telephony
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.models.Conversation
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.syncThreadToLocal
import org.fossify.messages.helpers.SmsSyncProgress

data class ConversationsUiState(
    val conversations: List<Conversation> = emptyList(),
    val isLoading: Boolean = false,
    val isDefaultSmsApp: Boolean = true,
    val searchThreadIds: Set<Long> = emptySet(),
    val searchStatus: String = "搜索联系人/号码及已同步短信正文",
    val initialLoaded: Boolean = false,
    val loadError: String = ""
)

class ConversationsViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(ConversationsUiState())
    val uiState: StateFlow<ConversationsUiState> = _uiState.asStateFlow()
    private var refreshJob: Job? = null
    private var searchJob: Job? = null
    private var syncJob: Job? = null

    fun search(text: String) {
        searchJob?.cancel()
        if (text.isBlank()) { _uiState.value = _uiState.value.copy(searchThreadIds = emptySet(), searchStatus = "搜索联系人/号码及已同步短信正文"); return }
        searchJob = viewModelScope.launch {
            delay(250)
            _uiState.value = _uiState.value.copy(searchThreadIds = emptySet(), searchStatus = "正在搜索已同步正文…")
            try {
                val ids = withContext(Dispatchers.IO) { getApplication<Application>().messagesDB.searchConversationIds(text) }
                _uiState.value = _uiState.value.copy(searchThreadIds = ids.take(500).toSet(),
                    searchStatus = if (ids.size > 500) "正文结果仅展示最近500个会话；可缩小关键词范围" else "已搜索本地同步正文；未同步历史需先全量同步")
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { _uiState.value = _uiState.value.copy(searchThreadIds = emptySet(), searchStatus = "正文搜索失败，仍展示联系人/号码匹配") }
        }
    }

    fun resyncAll() {
        if (syncJob?.isActive == true || !SmsSyncProgress.tryStart()) return
        syncJob = viewModelScope.launch {
            val context = getApplication<Application>()
            var done = 0
            var failed = 0
            var total = 0
            try {
                _uiState.value = _uiState.value.copy(loadError = "")
                withContext(Dispatchers.IO) {
                    require(androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    context.config.fullHistorySyncedV2 = false
                    val threads = context.getConversations()
                    if (threads.isEmpty() && context.conversationsDB.getAllRegular().isNotEmpty()) {
                        throw IllegalStateException("系统短信列表暂时为空，请检查短信权限及默认短信应用后重试")
                    }
                    total = threads.size
                    SmsSyncProgress.update(SmsSyncProgress.State(true, 0, total, 0))
                    for (thread in threads) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        try { context.syncThreadToLocal(thread.threadId, loadAll = true) }
                        catch (e: CancellationException) { throw e }
                        catch (_: Exception) { failed++ }
                        done++
                        SmsSyncProgress.update(SmsSyncProgress.State(true, done, total, failed))
                    }
                    if (failed == 0) context.config.fullHistorySyncedV2 = true
                }
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) {
                failed++
                _uiState.value = _uiState.value.copy(loadError =
                    error.message?.takeIf { it.contains("系统短信列表") }
                        ?: "全量同步失败，请检查短信读取权限后重试")
            }
            finally { SmsSyncProgress.update(SmsSyncProgress.State(false, done, total, failed)) }
        }
    }

    init {
        refresh(isInitial = true)
    }

    fun refresh(isInitial: Boolean = false) {
        // 首次加载不能被连续刷新事件反复取消，否则空页面会一直显示转圈。
        if (refreshJob?.isActive == true && !_uiState.value.initialLoaded) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            if (!isInitial) delay(300)
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

            val cached = withContext(Dispatchers.IO) {
                runCatching { context.conversationsDB.getAllRegular() }.getOrDefault(emptyList())
            }
            if (cached.isNotEmpty() && _uiState.value.conversations.isEmpty()) {
                _uiState.value = _uiState.value.copy(conversations = cached, isLoading = false, initialLoaded = true)
            }

            val loadingWatchdog = launch {
                delay(15_000)
                if (_uiState.value.isLoading && _uiState.value.conversations.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        initialLoaded = true,
                        loadError = "系统短信读取耗时较长，请检查权限或稍后刷新"
                    )
                }
            }
            val provider = try {
                withContext(Dispatchers.IO) { context.getConversations() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    initialLoaded = true,
                    loadError = "系统短信读取失败，已保留本地记录，可稍后刷新"
                )
                return@launch
            } finally {
                loadingWatchdog.cancel()
            }

            val list = (provider + cached)
                .distinctBy { it.threadId }
                .sortedByDescending { it.date }
            _uiState.value = _uiState.value.copy(
                conversations = list,
                isLoading = false,
                initialLoaded = true,
                loadError = if (provider.isEmpty() && list.isNotEmpty())
                    "系统短信列表暂时为空，当前显示已同步记录" else ""
            )
        }
    }
}
