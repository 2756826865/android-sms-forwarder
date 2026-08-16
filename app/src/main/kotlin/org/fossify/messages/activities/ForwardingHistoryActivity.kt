package org.fossify.messages.activities

import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityForwardingHistoryBinding
import org.fossify.messages.extensions.applyMiuiPageChrome
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
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshRecords = object : Runnable {
        override fun run() {
            renderRecords()
            refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.forwardingHistoryScrollview))
        setupMaterialScrollListener(binding.forwardingHistoryScrollview, binding.forwardingHistoryAppbar)
        setupTopAppBar(binding.forwardingHistoryAppbar, NavigationIcon.Arrow)
        binding.forwardingHistoryToolbar.title = ""
        binding.forwardingHistoryClear.setOnClickListener { confirmClear() }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiPageChrome()
        refreshHandler.post(refreshRecords)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshRecords)
        super.onPause()
    }

    private fun renderRecords() {
        val records = history.records()
        val signature = records.joinToString("|") {
            "${it.recordId}:${it.status}:${it.attempts}:${it.detail}:${it.updatedAt}"
        }
        if (signature == lastRenderedSignature) return
        lastRenderedSignature = signature
        binding.forwardingHistoryList.removeAllViews()
        binding.forwardingHistoryEmpty.visibility = if (records.isEmpty()) View.VISIBLE else View.GONE
        binding.forwardingHistoryClear.isEnabled = records.isNotEmpty()
        records
            .groupBy { it.workId.ifBlank { it.recordId } }
            .values
            .sortedByDescending { group -> group.maxOf(ForwardingHistoryRecord::updatedAt) }
            .forEach { binding.forwardingHistoryList.addView(createMessageView(it)) }
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

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.forwarding_history_clear)
            .setMessage(R.string.forwarding_history_clear_confirm)
            .setPositiveButton(R.string.forwarding_history_clear) { _, _ ->
                history.clear()
                renderRecords()
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
        ForwardingHistoryStore.STATUS_SKIPPED -> "已跳过"
        else -> "失败"
    }

    private fun statusColor(status: String) = when (status) {
        ForwardingHistoryStore.STATUS_SUCCESS -> R.color.miui_action_blue
        ForwardingHistoryStore.STATUS_FAILED -> R.color.miui_unread_red
        ForwardingHistoryStore.STATUS_WAITING_NETWORK,
        ForwardingHistoryStore.STATUS_RETRY -> R.color.miui_warning_text
        ForwardingHistoryStore.STATUS_SKIPPED -> R.color.miui_secondary_text
        else -> R.color.miui_primary_text
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 1_500L
    }
}
