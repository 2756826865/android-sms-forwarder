package org.fossify.messages.activities

import android.annotation.SuppressLint
import android.os.Bundle
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.widget.ArrayAdapter
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.onTextChangeListener
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.messages.R
import org.fossify.messages.adapters.BulkRecipientsAdapter
import org.fossify.messages.databinding.ActivityBulkSendBinding
import org.fossify.messages.extensions.subscriptionManagerCompat
import org.fossify.messages.messaging.BulkSendWorker
import org.fossify.messages.models.BulkRecipient

class BulkSendActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityBulkSendBinding::inflate)
    private val allRecipients = ArrayList<BulkRecipient>()
    private val selectedNumbers = linkedSetOf<String>()
    private val adapter = BulkRecipientsAdapter(selectedNumbers, ::updateSelectedCount)
    private var simOptions = emptyList<SimOption>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.bulkSendAction))
        setupMaterialScrollListener(
            scrollingView = binding.bulkSendRecipients,
            topAppBar = binding.bulkSendAppbar
        )
        setupTopAppBar(binding.bulkSendAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.bulkSendHolder)
        binding.bulkSendRecipients.adapter = adapter

        binding.bulkSendSearch.onTextChangeListener { applyFilter(it) }
        binding.bulkSendSelectAll.setOnClickListener {
            val visible = adapter.visibleNumbers()
            if (selectedNumbers.size + visible.count { it !in selectedNumbers } > MAX_RECIPIENTS) {
                toast(getString(R.string.bulk_send_too_many, MAX_RECIPIENTS))
            } else {
                selectedNumbers.addAll(visible)
                adapter.notifyDataSetChanged()
                updateSelectedCount()
            }
        }
        binding.bulkSendClear.setOnClickListener {
            selectedNumbers.clear()
            adapter.notifyDataSetChanged()
            updateSelectedCount()
        }
        binding.bulkSendAction.setOnClickListener { confirmSend() }

        handlePermission(PERMISSION_READ_CONTACTS) { granted ->
            if (granted) loadContacts() else toast(org.fossify.commons.R.string.no_access_to_contacts)
        }
        loadSimOptions()
    }

    private fun loadContacts() {
        SimpleContactsHelper(this).getAvailableContacts(false) { contacts ->
            val recipients = contacts.flatMap { contact ->
                contact.phoneNumbers.mapNotNull { phone ->
                    phone.normalizedNumber.trim().takeIf(String::isNotBlank)?.let {
                        BulkRecipient(contact.name, it)
                    }
                }
            }.distinctBy { it.number }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

            runOnUiThread {
                allRecipients.clear()
                allRecipients.addAll(recipients)
                applyFilter(binding.bulkSendSearch.value)
            }
        }
    }

    private fun applyFilter(query: String) {
        val normalized = query.trim()
        val filtered = if (normalized.isBlank()) {
            allRecipients
        } else {
            allRecipients.filter { it.name.contains(normalized, true) || it.number.contains(normalized) }
        }
        adapter.submitList(filtered)
    }

    @SuppressLint("MissingPermission")
    private fun loadSimOptions() {
        val active = runCatching { subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty() }.getOrDefault(emptyList())
        simOptions = buildList {
            add(SimOption(getString(R.string.bulk_send_default_sim), SubscriptionManager.INVALID_SUBSCRIPTION_ID))
            active.forEachIndexed { index, info ->
                val carrier = info.carrierName?.toString().orEmpty()
                add(SimOption("SIM${index + 1}${if (carrier.isBlank()) "" else " · $carrier"}", info.subscriptionId))
            }
        }
        binding.bulkSendSim.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, simOptions)
    }

    private fun confirmSend() {
        val body = binding.bulkSendBody.value.trim()
        when {
            selectedNumbers.isEmpty() -> toast(R.string.bulk_send_no_recipient)
            body.isBlank() -> toast(R.string.bulk_send_no_content)
            selectedNumbers.size > MAX_RECIPIENTS -> toast(getString(R.string.bulk_send_too_many, MAX_RECIPIENTS))
            else -> {
                val parts = SmsMessage.calculateLength(body, false).first().coerceAtLeast(1)
                val estimatedMessages = parts * selectedNumbers.size
                ConfirmationDialog(
                    this,
                    getString(R.string.bulk_send_confirm, selectedNumbers.size, estimatedMessages)
                ) {
                    val selectedSim = simOptions.getOrNull(binding.bulkSendSim.selectedItemPosition)
                    val subId = selectedSim?.subscriptionId ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID
                    BulkSendWorker.enqueue(applicationContext, body, subId, selectedNumbers.toList())
                    toast(getString(R.string.bulk_send_started, selectedNumbers.size))
                    finish()
                }
            }
        }
    }

    private fun updateSelectedCount() {
        binding.bulkSendSelectedCount.text = getString(R.string.bulk_send_selected, selectedNumbers.size)
    }

    private data class SimOption(val label: String, val subscriptionId: Int) {
        override fun toString(): String = label
    }

    companion object {
        private const val MAX_RECIPIENTS = 30
    }
}
