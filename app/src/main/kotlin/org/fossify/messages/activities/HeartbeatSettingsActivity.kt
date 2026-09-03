package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityHeartbeatSettingsBinding
import org.fossify.messages.forwarding.HeartbeatConfig
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.forwarding.TemplateDataRetriever
import org.fossify.messages.helpers.HeartbeatWorker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HeartbeatSettingsActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityHeartbeatSettingsBinding::inflate)
    private val heartbeatConfig by lazy { HeartbeatConfig(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.heartbeatToolbar.setNavigationIcon(org.fossify.commons.R.drawable.ic_arrow_left_vector)
        binding.heartbeatToolbar.setNavigationOnClickListener { finish() }

        initViews()
    }

    private fun initViews() {
        binding.heartbeatSwitchEnabled.isChecked = heartbeatConfig.enabled
        binding.heartbeatSwitchEnabled.setOnCheckedChangeListener { _, isChecked ->
            heartbeatConfig.enabled = isChecked
            HeartbeatWorker.sync(applicationContext)
        }

        binding.heartbeatIntervalSlider.value = heartbeatConfig.intervalHours.toFloat().coerceIn(1f, 24f)
        updateIntervalSummary(heartbeatConfig.intervalHours)

        binding.heartbeatIntervalSlider.addOnChangeListener { _, value, _ ->
            val hours = value.toInt()
            heartbeatConfig.intervalHours = hours
            updateIntervalSummary(hours)
            if (heartbeatConfig.enabled) {
                HeartbeatWorker.sync(applicationContext)
            }
        }

        binding.heartbeatBtnTest.setOnClickListener {
            val multiConfig = MultiForwardConfig(this)
            val channels = multiConfig.enabledChannelIds()
            if (channels.isEmpty()) {
                toast("请先在「转发通道」中启用至少一个推送渠道")
                return@setOnClickListener
            }

            val now = System.currentTimeMillis()
            val timeFormatted = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(now))
            val sim1 = multiConfig.simOneLabel.ifBlank { "SIM 1" }
            val sim2 = multiConfig.simTwoLabel.ifBlank { "SIM 2" }

            val testBody = buildString {
                appendLine("🟢 【设备状态与心跳保活 · 测试】")
                appendLine("📱 设备机型：${TemplateDataRetriever.getDeviceName()}")
                appendLine("🔋 电池状态：${TemplateDataRetriever.getBatteryInfo(this@HeartbeatSettingsActivity)}")
                appendLine("💳 卡槽一：$sim1")
                appendLine("💳 卡槽二：$sim2")
                appendLine("🕒 测试时间：$timeFormatted")
            }.trim()

            channels.forEach { target ->
                MultiChannelForwardWorker.enqueueSingle(
                    context = this,
                    sender = "设备心跳",
                    body = testBody,
                    receivedAt = now,
                    subscriptionId = -1,
                    uniqueId = "test-hb-$now",
                    targetChannel = target,
                    allowedChannels = setOf(target),
                    isTest = true
                )
            }
            toast("心跳保活测试消息已发送")
        }
    }

    private fun updateIntervalSummary(hours: Int) {
        binding.heartbeatIntervalSummary.text = "当前周期：每 $hours 小时一次"
    }
}
