package org.fossify.messages.activities

import android.os.Bundle
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityCallForwardingSettingsBinding
import org.fossify.messages.extensions.applyMiuiTopAppBarChrome
import org.fossify.messages.forwarding.CallForwardConfig
import org.fossify.messages.forwarding.MultiChannelForwardWorker
import org.fossify.messages.forwarding.MultiForwardConfig

class CallForwardingSettingsActivity : SimpleActivity() {

    private val binding by viewBinding(ActivityCallForwardingSettingsBinding::inflate)
    private val callConfig by lazy { CallForwardConfig(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.callForwardToolbar.setNavigationIcon(org.fossify.commons.R.drawable.ic_arrow_left_vector)
        binding.callForwardToolbar.setNavigationOnClickListener { finish() }

        initViews()
    }

    private fun initViews() {
        binding.callForwardSwitchEnabled.isChecked = callConfig.enabled
        binding.callForwardSwitchEnabled.setOnCheckedChangeListener { _, isChecked ->
            callConfig.enabled = isChecked
        }

        binding.callForwardSwitchMissedOnly.isChecked = callConfig.missedCallOnly
        binding.callForwardSwitchMissedOnly.setOnCheckedChangeListener { _, isChecked ->
            callConfig.missedCallOnly = isChecked
        }

        binding.callForwardSwitchAnswered.isChecked = callConfig.forwardAnsweredCall
        binding.callForwardSwitchAnswered.setOnCheckedChangeListener { _, isChecked ->
            callConfig.forwardAnsweredCall = isChecked
        }

        binding.callForwardBtnTest.setOnClickListener {
            val multiConfig = MultiForwardConfig(this)
            val channels = multiConfig.enabledChannelIds()
            if (channels.isEmpty()) {
                toast("请先在「转发通道」中启用至少一个推送渠道")
                return@setOnClickListener
            }

            val now = System.currentTimeMillis()
            val testBody = "🔴 【未接来电提醒】\n📞 来电号码：10086 (中国移动客服)\n⏱️ 响铃时长：25秒\n🕒 发生时间：刚刚\n📶 接收卡槽：SIM 1 (测试模拟)"

            channels.forEach { target ->
                MultiChannelForwardWorker.enqueueSingle(
                    context = this,
                    sender = "10086",
                    body = testBody,
                    receivedAt = now,
                    subscriptionId = -1,
                    uniqueId = "test-call-$now",
                    targetChannel = target,
                    allowedChannels = setOf(target),
                    isTest = true
                )
            }
            toast("未接来电模拟测试消息已发送")
        }
    }
}
