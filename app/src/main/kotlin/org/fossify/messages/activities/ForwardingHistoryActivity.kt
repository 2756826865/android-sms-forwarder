package org.fossify.messages.activities

import android.graphics.Typeface
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import org.fossify.messages.models.Events
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityForwardingHistoryBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.showSmsStyled
import org.fossify.messages.forwarding.ForwardingChannels
import org.fossify.messages.forwarding.ForwardingHistoryRecord
import org.fossify.messages.forwarding.ForwardingHistoryStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ForwardingHistoryActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityForwardingHistoryBinding::inflate)
    private val history by lazy { ForwardingHistoryStore(applicationContext) }
    private var lastRenderedSignature: String? = null
    private var visibleLimit = 50
    private var refreshJob: Job? = null
    private var diagnosticsJob: Job? = null

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onHistoryChanged(event: Events.RefreshDashboard) {
        refreshJob?.cancel()
        refreshJob = lifecycleScope.launch { delay(150); renderRecords() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.forwardingHistoryAppbar),
            padBottomImeAndSystem = listOf(binding.forwardingHistoryScrollview),
        )
        setupMaterialScrollListener(binding.forwardingHistoryScrollview, binding.forwardingHistoryAppbar)
        setupTopAppBar(binding.forwardingHistoryAppbar, NavigationIcon.Arrow)
        binding.forwardingHistoryToolbar.title = ""
        applyMiuiTopAppBarChrome(binding.forwardingHistoryAppbar, binding.forwardingHistoryToolbar)
        binding.forwardingHistoryClear.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.forwardingHistoryAppbar, binding.forwardingHistoryToolbar)
        EventBus.getDefault().register(this)
        refreshJob = lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                org.fossify.messages.forwarding.ForwardingQueueReconciler.run(applicationContext, allowRepair = false)
            }
            renderRecords()
        }
    }

    override fun onPause() {
        EventBus.getDefault().unregister(this)
        refreshJob?.cancel()
        diagnosticsJob?.cancel()
        super.onPause()
    }

    private suspend fun renderRecords() {
        val (records, total) = withContext(Dispatchers.IO) {
            history.records(limit = visibleLimit) to history.count()
        }
        val signature = records.joinToString("|") {
            "${it.recordId}:${it.status}:${it.attempts}:${it.detail}:${it.updatedAt}"
        }
        if ("$signature:$total" == lastRenderedSignature) return
        lastRenderedSignature = "$signature:$total"
        binding.forwardingHistoryList.removeAllViews()
        binding.forwardingHistoryEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        binding.forwardingHistoryClear.isEnabled = records.isNotEmpty()
        records
            .groupBy { it.workId.ifBlank { it.recordId } }
            .values
            .sortedByDescending { group -> group.maxOf(ForwardingHistoryRecord::updatedAt) }
            .forEach { binding.forwardingHistoryList.addView(createMessageView(it)) }
        if (records.size < total && visibleLimit < 2000) {
            binding.forwardingHistoryList.addView(TextView(this).apply {
                text = "加载更多（已显示 ${records.size} 条）"
                gravity = Gravity.CENTER
                setPadding(16, 24, 16, 24)
                setOnClickListener {
                    visibleLimit = (visibleLimit + 50).coerceAtMost(2000)
                    refreshJob?.cancel()
                    refreshJob = lifecycleScope.launch { renderRecords() }
                }
            })
        }
    }

    private fun createMessageView(records: List<ForwardingHistoryRecord>): View {
        val message = records.first()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = ContextCompat.getDrawable(context, R.drawable.settings_card_background)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }

            addView(TextView(context).apply {
                text = if (message.isTest) "测试消息" else message.sender.ifBlank { "未知发送方" }
                textSize = 17f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(ContextCompat.getColor(context, R.color.miui_primary_text))
            })
            addView(TextView(context).apply {
                text = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(message.receivedAt))
                textSize = 13f
                setTextColor(ContextCompat.getColor(context, R.color.miui_secondary_text))
                setPadding(0, dp(4), 0, 0)
            })
            addView(TextView(context).apply {
                text = message.body.ifBlank { "（无正文）" }
                textSize = 15f
                maxLines = 6
                ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 1.12f)
                setTextColor(ContextCompat.getColor(context, R.color.miui_primary_text))
                setPadding(0, dp(8), 0, 0)
            })

            records.sortedBy { ForwardingChannels.displayName(it.channel) }.forEachIndexed { index, record ->
                if (index > 0) {
                    addView(View(context).apply {
                        setBackgroundColor(ContextCompat.getColor(context, R.color.miui_divider))
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            dp(1),
                        ).apply { topMargin = dp(10) }
                    })
                }
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(if (index == 0) 12 else 10), 0, 0)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(TextView(context).apply {
                            text = ForwardingChannels.displayName(record.channel)
                            textSize = 15f
                            maxLines = 2
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(ContextCompat.getColor(context, R.color.miui_primary_text))
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        })
                        addView(TextView(context).apply {
                            text = statusLabel(record.status)
                            textSize = 14f
                            setTypeface(typeface, Typeface.BOLD)
                            setTextColor(ContextCompat.getColor(context, statusColor(record.status)))
                            gravity = Gravity.END
                            setPadding(dp(12), 0, 0, 0)
                        })
                    })
                    if (!record.isTest && record.status in setOf(ForwardingHistoryStore.STATUS_QUEUED,
                            ForwardingHistoryStore.STATUS_WAITING_NETWORK, ForwardingHistoryStore.STATUS_RETRY,
                            ForwardingHistoryStore.STATUS_RUNNING, ForwardingHistoryStore.STATUS_UNKNOWN,
                            ForwardingHistoryStore.STATUS_FAILED)) {
                        addView(TextView(context).apply {
                            text = "查看排队原因"
                            setPadding(0, dp(10), 0, dp(10))
                            setTextColor(ContextCompat.getColor(context, R.color.miui_action_blue))
                            setOnClickListener { showQueueDiagnostics(record) }
                        })
                    }
                    if (record.status in setOf(ForwardingHistoryStore.STATUS_FAILED, ForwardingHistoryStore.STATUS_UNKNOWN)) {
                        addView(TextView(context).apply {
                            val unavailable = org.fossify.messages.forwarding.ForwardingRetry.unavailableReason(context, record)
                            text = unavailable ?: "检查并重试此通道"
                            isEnabled = unavailable == null
                            alpha = if (isEnabled) 1f else 0.6f
                            setPadding(0, dp(10), 0, dp(10))
                            setTextColor(ContextCompat.getColor(context, R.color.miui_action_blue))
                            setOnClickListener { confirmRetry(record) }
                        })
                    }
                    addView(TextView(context).apply {
                        text = buildString {
                            append(if (record.attempts == 0) "未尝试" else "尝试 ${record.attempts} 次")
                            if (record.detail.isNotBlank()) append(" · ${record.detail}")
                        }
                        textSize = 13f
                        setLineSpacing(0f, 1.1f)
                        setTextColor(ContextCompat.getColor(context, R.color.miui_secondary_text))
                        setPadding(0, dp(4), 0, 0)
                    })
                })
            }
        }
    }

    private fun showQueueDiagnostics(record: ForwardingHistoryRecord) {
        if (diagnosticsJob?.isActive == true) return
        val dialog = AlertDialog.Builder(this)
            .setTitle("排队原因")
            .setMessage("正在读取系统任务状态…")
            .setPositiveButton("关闭", null).create()
        dialog.showSmsStyled()
        diagnosticsJob = lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                try {
                    org.fossify.messages.forwarding.ForwardingQueueDiagnostics.forRecord(applicationContext, record)
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { "读取任务状态失败或超时，请稍后重试；此检查没有重新发送短信。" }
            }
            if (dialog.isShowing) dialog.setMessage(message)
        }
        dialog.setOnDismissListener { diagnosticsJob?.cancel() }
    }

    private fun confirmRetry(record: ForwardingHistoryRecord) {
        AlertDialog.Builder(this)
            .setTitle("仅重试此通道")
            .setMessage("先核对原任务已失败或取消，再仅重试此通道一次，不重新匹配规则或执行整个通道组。已尝试发送的收费短信禁止从日志重试。失败记录也可能已经送达，请先核对接收端。")
            .setPositiveButton("确认重试") { _, _ ->
                lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { org.fossify.messages.forwarding.ForwardingRetry.enqueue(applicationContext, record.recordId) }
                    }
                    android.widget.Toast.makeText(this@ForwardingHistoryActivity,
                        result.exceptionOrNull()?.message ?: "已检查重试请求，请查看对应日志状态", android.widget.Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("取消", null).create().showSmsStyled()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.forwarding_history_clear)
            .setMessage(R.string.forwarding_history_clear_confirm)
            .setPositiveButton(R.string.forwarding_history_clear) { _, _ ->
                refreshJob?.cancel()
                refreshJob = lifecycleScope.launch {
                    withContext(Dispatchers.IO) { history.clear() }
                    visibleLimit = 50
                    lastRenderedSignature = null
                    renderRecords()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }

    private fun statusLabel(status: String) = when (status) {
        ForwardingHistoryStore.STATUS_QUEUED -> "已入队"
        ForwardingHistoryStore.STATUS_WAITING_NETWORK -> "等待网络"
        ForwardingHistoryStore.STATUS_RUNNING -> "发送中"
        ForwardingHistoryStore.STATUS_SUCCESS -> "成功"
        ForwardingHistoryStore.STATUS_RETRY -> "等待重试"
        ForwardingHistoryStore.STATUS_UNKNOWN -> "结果未知"
        ForwardingHistoryStore.STATUS_SKIPPED -> "已跳过"
        else -> "失败"
    }

    private fun statusColor(status: String) = when (status) {
        ForwardingHistoryStore.STATUS_SUCCESS -> R.color.miui_action_blue
        ForwardingHistoryStore.STATUS_FAILED -> R.color.miui_unread_red
        ForwardingHistoryStore.STATUS_WAITING_NETWORK,
        ForwardingHistoryStore.STATUS_RETRY,
        ForwardingHistoryStore.STATUS_UNKNOWN -> R.color.miui_warning_text
        ForwardingHistoryStore.STATUS_SKIPPED -> R.color.miui_secondary_text
        else -> R.color.miui_primary_text
    }

}
