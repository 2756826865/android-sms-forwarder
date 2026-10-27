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
import org.fossify.messages.extensions.deleteConversation
import org.fossify.messages.extensions.moveConversationToRecycleBin
import org.fossify.messages.messaging.cancelScheduleSendPendingIntent
import org.fossify.commons.extensions.notificationManager
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.models.Conversation
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.syncThreadToLocal
import org.fossify.messages.extensions.markThreadMessagesRead
import org.fossify.messages.helpers.syncSmsHistory
import org.fossify.messages.helpers.SmsSyncProgress

data class ConversationsUiState(
    val conversations: List<Conversation> = emptyList(),
    val isLoading: Boolean = false,
    val isDefaultSmsApp: Boolean = true,
    val isNotificationSmsDenied: Boolean = false,
    val searchThreadIds: Set<Long> = emptySet(),
    val searchStatus: String = "搜索联系人/号码及已同步短信正文",
    val initialLoaded: Boolean = false,
    val deletingThreadId: Long? = null,
    val loadError: String = "",
    val diagnosticTraceId: String? = null,
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

    fun deleteConversation(threadId: Long) {
        if (_uiState.value.deletingThreadId != null) return
        refreshJob?.cancel()
        _uiState.value = _uiState.value.copy(deletingThreadId = threadId, loadError = "")
        viewModelScope.launch {
            val context = getApplication<Application>()
            try {
                withContext(Dispatchers.IO) {
                    check(org.fossify.messages.helpers.DeviceCompatHelper.isDefaultSmsApp(context)) {
                        "请先设为默认短信应用，再删除会话"
                    }
                    if (context.config.useRecycleBin) {
                        // Include unsynced history, so moving a thread does not remove only its cached page.
                        context.syncThreadToLocal(threadId, loadAll = true)
                        context.moveConversationToRecycleBin(threadId)
                        check(context.messagesDB.getNonRecycledThreadMessages(threadId).isEmpty()) {
                            "部分短信未能移入回收站，请刷新后重试"
                        }
                    } else {
                        context.messagesDB.getNonRecycledThreadMessages(threadId)
                            .filter { it.isScheduled }
                            .forEach { context.cancelScheduleSendPendingIntent(it.id) }
                        context.deleteConversation(threadId, failOnError = true)
                    }
                    context.notificationManager.cancel(threadId.hashCode())
                }
                _uiState.value = _uiState.value.copy(
                    conversations = _uiState.value.conversations.filterNot { it.threadId == threadId },
                    searchThreadIds = _uiState.value.searchThreadIds - threadId
                )
                org.fossify.messages.helpers.refreshConversations()
                org.fossify.messages.helpers.refreshMessages()
                refresh()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.value = _uiState.value.copy(loadError = error.message ?: "删除失败，请检查短信权限后重试")
            } finally {
                _uiState.value = _uiState.value.copy(deletingThreadId = null)
            }
        }
    }

    fun resyncAll() {
        if (syncJob?.isActive == true || !SmsSyncProgress.tryStart()) return
        syncJob = viewModelScope.launch {
            val context = getApplication<Application>()
            try {
                _uiState.value = _uiState.value.copy(loadError = "")
                withContext(Dispatchers.IO) {
                    require(androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
                    context.config.fullHistorySyncedV2 = false
                    val syncResult = context.syncSmsHistory("compose_resync_all", strict = true)
                    if (!syncResult.success) {
                        throw IllegalStateException("全量同步未成功: ${syncResult.outcome}")
                    }
                    if (syncResult.readCount == 0 && context.conversationsDB.getAllRegular().isNotEmpty()) {
                        throw IllegalStateException("系统短信列表暂时为空，请检查短信权限及默认短信应用后重试")
                    }
                    context.config.fullHistorySyncedV2 = true
                }
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (error: Exception) {
                _uiState.value = _uiState.value.copy(loadError =
                    error.message?.takeIf { it.contains("系统短信列表") || it.contains("全量同步未成功") }
                        ?: "全量同步失败，请检查短信读取权限后重试")
            }
            finally {
                val current = SmsSyncProgress.state.value
                SmsSyncProgress.update(current.copy(running = false))
            }
        }
    }

    init {
        refresh(isInitial = true)
    }

    fun markThreadReadLocally(threadId: Long) {
        _uiState.value = _uiState.value.copy(
            conversations = _uiState.value.conversations.map {
                if (it.threadId == threadId) it.copy(read = true, unreadCount = 0) else it
            }
        )
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            context.markThreadMessagesRead(threadId)
        }
    }

    fun refresh(isInitial: Boolean = false) {
        val diagnostics = org.fossify.messages.helpers.SmsSyncDiagnostics.recent()
        // 首次加载不能被连续刷新事件反复取消，否则空页面会一直显示转圈。
        if (refreshJob?.isActive == true && !_uiState.value.initialLoaded) {
            diagnostics?.log("refresh", "status=skipped_initial_in_progress")
            return
        }
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            diagnostics?.log("refresh", "status=started initial=$isInitial")
            if (!isInitial) delay(300)
            val context = getApplication<Application>()
            val isDefault = org.fossify.messages.helpers.DeviceCompatHelper.isDefaultSmsApp(context)
            val isNotificationDenied = org.fossify.messages.helpers.DeviceCompatHelper.isNotificationSmsDenied(context)

            if (isInitial || !_uiState.value.initialLoaded) {
                _uiState.value = _uiState.value.copy(
                    isLoading = true,
                    isDefaultSmsApp = isDefault,
                    isNotificationSmsDenied = isNotificationDenied
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    isDefaultSmsApp = isDefault,
                    isNotificationSmsDenied = isNotificationDenied
                )
            }

            val cached = withContext(Dispatchers.IO) {
                runCatching { context.conversationsDB.getAllRegular() }.getOrDefault(emptyList())
            }
            diagnostics?.conversations("refresh_cached_before_provider", cached)
            if (cached.isNotEmpty() && _uiState.value.conversations.isEmpty()) {
                _uiState.value = _uiState.value.copy(
                    conversations = org.fossify.messages.helpers.sortConversations(cached, context.config.pinnedConversations),
                    isLoading = false, initialLoaded = true
                )
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
                withContext(Dispatchers.IO) {
                    val threads = context.getConversations(strict = false, diagnostics = diagnostics)
                    // If local DB is empty, run history sync to populate local messages and threads
                    if (cached.isEmpty() && threads.isNotEmpty()) {
                        context.syncSmsHistory("compose_initial_sync")
                    }
                    for (thread in threads) {
                        context.conversationsDB.insertOrUpdate(thread)
                    }
                    threads
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                diagnostics?.log("refresh", "status=failed result=previous_state")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    initialLoaded = true,
                    loadError = "系统短信读取失败，已保留本地记录，可稍后刷新"
                )
                return@launch
            } finally {
                loadingWatchdog.cancel()
            }

            val recycledThreads = withContext(Dispatchers.IO) {
                context.messagesDB.getFullyRecycledThreadIds().toHashSet()
            }
            diagnostics?.conversations("refresh_provider", provider)
            diagnostics?.conversations("refresh_merged", org.fossify.messages.helpers.mergeConversationSources(provider, cached))
            val rawList = org.fossify.messages.helpers.mergeConversationSources(provider, cached)
                .filterNot { it.threadId in recycledThreads }
            val list = org.fossify.messages.helpers.sortConversations(
                rawList,
                context.config.pinnedConversations
            )
            val hasCommercialSms = list.any { 
                org.fossify.messages.helpers.YellowPageHelper.isCommercialOrSpecialAddress(it.phoneNumber) 
            }
            val effectiveNotificationDenied = if (hasCommercialSms) false else isNotificationDenied

            _uiState.value = _uiState.value.copy(
                conversations = list,
                isDefaultSmsApp = isDefault,
                isNotificationSmsDenied = effectiveNotificationDenied,
                diagnosticTraceId = diagnostics?.traceId,
                isLoading = false,
                initialLoaded = true,
                loadError = if (provider.isEmpty() && list.isNotEmpty())
                    "系统短信列表暂时为空，当前显示已同步记录" else ""
            )
            diagnostics?.conversations("viewmodel_final", list, recycledThreads)
            diagnostics?.log("refresh", "status=state_assigned scope=data_source")
        }
        refreshJob?.invokeOnCompletion { error ->
            if (error != null) diagnostics?.log("refresh", "status=terminated kind=${error.javaClass.simpleName}")
        }
    }
}
