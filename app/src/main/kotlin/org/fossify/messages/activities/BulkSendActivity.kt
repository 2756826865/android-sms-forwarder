package org.fossify.messages.activities

import android.annotation.SuppressLint
import android.os.Bundle
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.extensions.onTextChangeListener
import org.fossify.commons.extensions.toast
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
import android.net.Uri
import androidx.activity.result.contract.ActivityResultContracts

class BulkSendActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityBulkSendBinding::inflate)
    private val allRecipients = ArrayList<BulkRecipient>()
    private val selectedNumbers = linkedSetOf<String>()
    private val adapter = BulkRecipientsAdapter(selectedNumbers, ::updateSelectedCount)
    private var simOptions = emptyList<SimOption>()
    private val fileImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { importNumbersFromFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.bulkSendAction))
        setupMaterialScrollListener(
            scrollingView = binding.bulkSendRecipients,
            topAppBar = binding.bulkSendAppbar
        )
        setupTopAppBar(binding.bulkSendAppbar, NavigationIcon.Arrow)
        applyLightUiColors()
        binding.bulkSendRecipients.adapter = adapter

        binding.bulkSendSearch.onTextChangeListener {
            applyFilter(it)
            updateManualNumberAction(it)
        }
        binding.bulkSendAddNumber.setOnClickListener { addManualNumber() }
        binding.bulkSendImportFile.setOnClickListener { openFilePicker() }
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
                val manualRecipients = allRecipients.filter { it.name == getString(R.string.bulk_send_manual_number) }
                allRecipients.clear()
                allRecipients.addAll(manualRecipients)
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

    private fun updateManualNumberAction(query: String) {
        val number = normalizeManualNumber(query)
        val canAdd = number != null && allRecipients.none { it.number == number }
        binding.bulkSendAddNumber.isVisible = canAdd
        if (canAdd) {
            binding.bulkSendAddNumber.text = getString(R.string.bulk_send_add_number, number)
        }
    }

    private fun addManualNumber() {
        val number = normalizeManualNumber(binding.bulkSendSearch.value) ?: return
        if (number !in selectedNumbers && selectedNumbers.size >= MAX_RECIPIENTS) {
            toast(getString(R.string.bulk_send_too_many, MAX_RECIPIENTS))
            return
        }

        if (allRecipients.none { it.number == number }) {
            allRecipients.add(0, BulkRecipient(getString(R.string.bulk_send_manual_number), number))
        }
        selectedNumbers.add(number)
        binding.bulkSendSearch.setText("")
        applyFilter("")
        updateSelectedCount()
    }

    private fun normalizeManualNumber(input: String): String? {
        val compact = input.trim().replace(Regex("[\\s()\\-]"), "")
        if (!compact.matches(Regex("\\+?[0-9]{3,20}"))) return null
        return compact
    }

    private fun openFilePicker() {
        fileImportLauncher.launch(arrayOf("text/plain", "text/csv", "text/*"))
    }

    private fun importNumbersFromFile(uri: Uri) {
        try {
            val imported = mutableListOf<BulkRecipient>()
            var skipped = 0

            contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { lines ->
                var isFirstLine = true
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isBlank()) return@forEach

                    if (isFirstLine) {
                        isFirstLine = false
                        val lower = trimmed.lowercase()
                        if (lower.contains("name") || lower.contains("phone") ||
                            lower.contains("姓名") || lower.contains("号码") ||
                            lower.contains("number")) {
                            return@forEach
                        }
                    }

                    val (name, number) = parseImportLine(trimmed)
                    val normalized = normalizeManualNumber(number)

                    if (normalized == null || allRecipients.any { it.number == normalized }) {
                        skipped++
                        return@forEach
                    }

                    imported.add(BulkRecipient(name, normalized))
                }
            }

            if (imported.isEmpty()) {
                toast(R.string.bulk_send_import_empty)
            } else {
                val availableSlots = MAX_RECIPIENTS - selectedNumbers.size
                val toAdd = imported.take(availableSlots)
                val overflowSkipped = imported.size - toAdd.size
                val totalSkipped = skipped + overflowSkipped

                if (overflowSkipped > 0) {
                    toast(getString(R.string.bulk_send_too_many, MAX_RECIPIENTS))
                }

                toAdd.forEach { recipient ->
                    allRecipients.add(0, recipient)
                    selectedNumbers.add(recipient.number)
                }

                applyFilter(binding.bulkSendSearch.value)
                updateSelectedCount()
                toast(getString(R.string.bulk_send_import_result, toAdd.size, totalSkipped))
            }
        } catch (e: Exception) {
            toast(getString(R.string.bulk_send_import_error, e.message ?: "Unknown error"))
        }
    }

    private fun parseImportLine(line: String): Pair<String, String> {
        return if (line.contains(",")) {
            val parts = line.split(",", limit = 2)
            val name = parts[0].trim().ifBlank { getString(R.string.bulk_send_imported) }
            val number = parts[1].trim()
            Pair(name, number)
        } else {
            val number = line.trim()
            Pair(getString(R.string.bulk_send_imported), number)
        }
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
        binding.bulkSendSim.adapter = ArrayAdapter(this, R.layout.item_bulk_sim_option, simOptions).apply {
            setDropDownViewResource(R.layout.item_bulk_sim_option)
        }
    }

    private fun applyLightUiColors() {
        val pageColor = getColor(R.color.miui_page_background)
        binding.bulkSendHolder.setBackgroundColor(pageColor)
        window.statusBarColor = pageColor
        window.navigationBarColor = pageColor
        binding.bulkSendSearch.setTextColor(getColor(R.color.miui_primary_text))
        binding.bulkSendSearch.setHintTextColor(getColor(R.color.miui_hint_text))
        binding.bulkSendAddNumber.setTextColor(getColor(R.color.miui_action_blue))
        binding.bulkSendSelectAll.setTextColor(getColor(R.color.miui_primary_text))
        binding.bulkSendClear.setTextColor(getColor(R.color.miui_primary_text))
        binding.bulkSendSelectedCount.setTextColor(getColor(R.color.miui_secondary_text))
        binding.bulkSendBody.setTextColor(getColor(R.color.miui_primary_text))
        binding.bulkSendBody.setHintTextColor(getColor(R.color.miui_hint_text))
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
