package org.fossify.messages.activities

import android.Manifest
import android.app.AlarmManager
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import org.fossify.commons.extensions.getTimeFormat
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityScheduledMessagesBinding
import org.fossify.messages.databinding.ItemScheduledMessageBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.deleteScheduledMessage
import org.fossify.messages.extensions.getAddresses
import org.fossify.messages.extensions.getThreadTitle
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.subscriptionManagerCompat
import org.fossify.messages.extensions.showSmsStyled
import org.fossify.messages.helpers.IS_SCHEDULE_MODE
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.messages.messaging.cancelScheduleSendPendingIntent
import org.fossify.messages.messaging.sendMessageCompat
import org.fossify.messages.models.Message
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScheduledMessagesActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityScheduledMessagesBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(
            padTopSystem = listOf(binding.scheduledAppbar),
            padBottomImeAndSystem = listOf(binding.scheduledScrollview),
        )
        setupMaterialScrollListener(binding.scheduledScrollview, binding.scheduledAppbar)
        setupTopAppBar(binding.scheduledAppbar, NavigationIcon.Arrow)
        binding.scheduledToolbar.title = ""
        applyMiuiTopAppBarChrome(binding.scheduledAppbar, binding.scheduledToolbar)
        binding.scheduledAdd.setOnClickListener {
            startActivity(Intent(this, NewConversationActivity::class.java).putExtra(IS_SCHEDULE_MODE, true))
        }
    }

    override fun onResume() {
        super.onResume()
        applyMiuiTopAppBarChrome(binding.scheduledAppbar, binding.scheduledToolbar)
        updateAlarmStatus()
        loadMessages()
    }

    private fun updateAlarmStatus() {
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        val allowed = android.os.Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        binding.scheduledAlarmStatus.text = getString(
            if (allowed) R.string.scheduled_alarm_ready else R.string.scheduled_alarm_permission_missing
        )
    }

    private fun loadMessages() {
        ensureBackgroundThread {
            val messages = messagesDB.getAllScheduledMessages().sortedBy { it.millis() }
            runOnUiThread { render(messages) }
        }
    }

    private fun render(messages: List<Message>) = binding.apply {
        scheduledList.removeAllViews()
        scheduledEmpty.visibility = if (messages.isEmpty()) View.VISIBLE else View.GONE
        messages.forEach { message ->
            val row = ItemScheduledMessageBinding.inflate(layoutInflater, scheduledList, false)
            row.scheduledRecipient.text = message.participants.getThreadTitle()
            row.scheduledBody.text = message.body
            val time = SimpleDateFormat("yyyy-MM-dd ${getTimeFormat()}", Locale.getDefault())
                .format(Date(message.millis()))
            row.scheduledMeta.text = getString(R.string.scheduled_meta, time, simLabel(message))
            row.root.setOnClickListener { openMessage(message) }
            row.scheduledMore.setOnClickListener { showActions(message) }
            scheduledList.addView(row.root)
        }
    }

    private fun simLabel(message: Message): String {
        if (message.subscriptionId < 0) return getString(R.string.bulk_send_default_sim)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return "SIM"
        }

        return runCatching {
            val info = subscriptionManagerCompat().getActiveSubscriptionInfo(message.subscriptionId)
            info?.simSlotIndex?.plus(1)?.let { "SIM$it" } ?: "SIM"
        }.getOrDefault("SIM")
    }

    private fun openMessage(message: Message) {
        startActivity(
            Intent(this, ThreadActivity::class.java)
                .putExtra(THREAD_ID, message.threadId)
                .putExtra(THREAD_TITLE, message.participants.getThreadTitle())
        )
    }

    private fun showActions(message: Message) {
        val actions = arrayOf(
            getString(R.string.scheduled_edit),
            getString(R.string.send_now),
            getString(R.string.scheduled_cancel),
        )
        AlertDialog.Builder(this)
            .setItems(actions) { _, which ->
                when (which) {
                    0 -> openMessage(message)
                    1 -> sendNow(message)
                    2 -> cancel(message)
                }
            }
            .create()
            .showSmsStyled()
    }

    private fun sendNow(message: Message) {
        ensureBackgroundThread {
            sendMessageCompat(
                message.body,
                message.participants.getAddresses(),
                message.subscriptionId,
                emptyList(),
            )
            removeScheduledMessage(message)
            runOnUiThread { loadMessages() }
        }
    }

    private fun cancel(message: Message) {
        AlertDialog.Builder(this)
            .setMessage(R.string.scheduled_cancel_confirmation)
            .setPositiveButton(R.string.scheduled_cancel) { _, _ ->
                ensureBackgroundThread {
                    removeScheduledMessage(message)
                    runOnUiThread { loadMessages() }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
            .showSmsStyled()
    }

    private fun removeScheduledMessage(message: Message) {
        cancelScheduleSendPendingIntent(message.id)
        deleteScheduledMessage(message.id)
        if (messagesDB.getNonRecycledThreadMessages(message.threadId).isEmpty()) {
            conversationsDB.deleteThreadId(message.threadId)
        }
    }
}
