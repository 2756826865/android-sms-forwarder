package org.fossify.messages.activities

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.provider.Telephony.Sms.MESSAGE_TYPE_QUEUED
import android.provider.Telephony.Sms.STATUS_NONE
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.content.res.AppCompatResources
import com.google.gson.Gson
import com.reddit.indicatorfastscroll.FastScrollItemIndicator
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getColorStateList
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.maybeShowNumberPickerDialog
import org.fossify.commons.extensions.normalizeString
import org.fossify.commons.extensions.onTextChangeListener
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.extensions.value
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.extensions.openRequestExactAlarmSettings
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isSPlus
import org.fossify.commons.models.SimpleContact
import org.fossify.commons.models.PhoneNumber
import org.fossify.messages.BuildConfig
import org.fossify.messages.R
import org.fossify.messages.adapters.ContactsAdapter
import org.fossify.messages.databinding.ActivityNewConversationBinding
import org.fossify.messages.databinding.ItemSuggestedContactBinding
import org.fossify.messages.dialogs.SimSelectionPopup
import org.fossify.messages.dialogs.ScheduleMessageDialog
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.createTemporaryThread
import org.fossify.messages.extensions.getSuggestedContacts
import org.fossify.messages.extensions.getThreadId
import org.fossify.messages.extensions.subscriptionManagerCompat
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.helpers.SmsIntentParser
import org.fossify.messages.helpers.THREAD_ATTACHMENT_URI
import org.fossify.messages.helpers.THREAD_ATTACHMENT_URIS
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_NUMBER
import org.fossify.messages.helpers.THREAD_TEXT
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.messages.helpers.IS_SCHEDULE_MODE
import org.fossify.messages.helpers.OPEN_THREAD_ATTACHMENT_PICKER
import org.fossify.messages.helpers.generateRandomId
import org.fossify.messages.messaging.isShortCodeWithLetters
import org.fossify.messages.messaging.BulkSendWorker
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.messaging.scheduleMessage
import org.fossify.messages.models.Message
import org.fossify.messages.models.SIMCard
import java.net.URLDecoder
import java.util.Locale

class NewConversationActivity : SimpleActivity() {
    private var allContacts = ArrayList<SimpleContact>()
    private var privateContacts = ArrayList<SimpleContact>()
    private val selectedRecipients = linkedMapOf<String, String>()
    private val availableSIMCards = arrayListOf<SIMCard>()
    private var currentSIMCardIndex = 0
    private var isScheduleMode = false

    private val binding by viewBinding(ActivityNewConversationBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        isScheduleMode = intent.getBooleanExtra(IS_SCHEDULE_MODE, false)
        title = getString(R.string.new_conversation)
        setupEdgeToEdge(padBottomImeAndSystem = listOf(binding.newConversationComposer))
        setupMaterialScrollListener(
            scrollingView = binding.contactsList,
            topAppBar = binding.newConversationAppbar
        )

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
        binding.newConversationAddress.requestFocus()
        binding.newConversationMessage.onTextChangeListener { updateSendButton() }
        binding.newConversationSend.setOnClickListener { sendComposedMessage() }
        binding.newConversationSend.setOnLongClickListener {
            if (selectedRecipients.isNotEmpty() && binding.newConversationMessage.value.isNotBlank()) {
                launchScheduleDialog()
            }
            true
        }
        binding.newConversationAddAttachment.setOnClickListener {
            openAttachmentForRecipient()
        }
        updateSendButton()

        // READ_CONTACTS permission is not mandatory, but without it we won't be able to show any suggestions during typing
        handlePermission(PERMISSION_READ_CONTACTS) {
            initContacts()
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.newConversationAppbar, NavigationIcon.Arrow)
        binding.newConversationToolbar.title = ""
        binding.newConversationToolbar.setBackgroundColor(Color.WHITE)
        window.statusBarColor = Color.WHITE
        window.navigationBarColor = Color.WHITE
        binding.noContactsPlaceholder2.setTextColor(getProperPrimaryColor())
        binding.noContactsPlaceholder2.underlineText()
        binding.suggestionsLabel.setTextColor(Color.rgb(139, 148, 168))
        setupSIMSelector()
    }

    private fun initContacts() {
        if (isThirdPartyIntent()) {
            return
        }

        fetchContacts()
        binding.newConversationAddress.onTextChangeListener { searchString ->
            val filteredContacts = ArrayList<SimpleContact>()
            allContacts.forEach { contact ->
                if (contact.phoneNumbers.any { it.normalizedNumber.contains(searchString, true) } ||
                    contact.name.contains(searchString, true) ||
                    contact.name.contains(searchString.normalizeString(), true) ||
                    contact.name.normalizeString().contains(searchString, true)) {
                    filteredContacts.add(contact)
                }
            }

            filteredContacts.sortWith(compareBy { !it.name.startsWith(searchString, true) })
            setupAdapter(filteredContacts)

            binding.newConversationConfirm.beVisibleIf(searchString.length > 2)
        }

        binding.newConversationConfirm.applyColorFilter(getProperTextColor())
        binding.newConversationConfirm.setOnClickListener {
            val number = binding.newConversationAddress.value
            if (isShortCodeWithLetters(number)) {
                binding.newConversationAddress.setText("")
                toast(R.string.invalid_short_code, length = Toast.LENGTH_LONG)
                return@setOnClickListener
            }
            addRecipient(number, number)
        }

        binding.noContactsPlaceholder2.setOnClickListener {
            handlePermission(PERMISSION_READ_CONTACTS) {
                if (it) {
                    fetchContacts()
                }
            }
        }

        val properPrimaryColor = getProperPrimaryColor()
        binding.contactsLetterFastscroller.textColor = getProperTextColor().getColorStateList()
        binding.contactsLetterFastscroller.pressedTextColor = properPrimaryColor
        binding.contactsLetterFastscrollerThumb.setupWithFastScroller(binding.contactsLetterFastscroller)
        binding.contactsLetterFastscrollerThumb.textColor = properPrimaryColor.getContrastColor()
        binding.contactsLetterFastscrollerThumb.thumbColor = properPrimaryColor.getColorStateList()
    }

    private fun isThirdPartyIntent(): Boolean {
        val result = SmsIntentParser.parse(intent)

        if (result != null && (result.first.isNotEmpty() || result.second.isNotEmpty())) {
            val (body, recipients) = result
            launchThreadActivity(
                phoneNumber = URLDecoder.decode(recipients.replace("+", "%2b").trim()),
                name = "",
                body = body
            )
            finish()
            return true
        }
        return false
    }

    private fun fetchContacts() {
        fillSuggestedContacts {
            SimpleContactsHelper(this).getAvailableContacts(false) {
                allContacts = it

                if (privateContacts.isNotEmpty()) {
                    allContacts.addAll(privateContacts)
                    allContacts.sort()
                }

                runOnUiThread {
                    setupAdapter(allContacts)
                }
            }
        }
    }

    private fun setupAdapter(contacts: ArrayList<SimpleContact>) {
        val hasContacts = contacts.isNotEmpty()
        binding.contactsList.beVisibleIf(hasContacts)
        binding.noContactsPlaceholder.beVisibleIf(!hasContacts)
        binding.noContactsPlaceholder2.beVisibleIf(
            !hasContacts && !hasPermission(
                PERMISSION_READ_CONTACTS
            )
        )

        if (!hasContacts) {
            val placeholderText = if (hasPermission(PERMISSION_READ_CONTACTS)) {
                org.fossify.commons.R.string.no_contacts_found
            } else {
                org.fossify.commons.R.string.no_access_to_contacts
            }

            binding.noContactsPlaceholder.text = getString(placeholderText)
        }

        val currAdapter = binding.contactsList.adapter
        if (currAdapter == null) {
            ContactsAdapter(this, contacts, binding.contactsList) {
                hideKeyboard()
                val contact = it as SimpleContact
                maybeShowNumberPickerDialog(contact.phoneNumbers) { number ->
                    addRecipient(number.normalizedNumber, contact.name)
                }
            }.apply {
                binding.contactsList.adapter = this
            }

            if (areSystemAnimationsEnabled) {
                binding.contactsList.scheduleLayoutAnimation()
            }
        } else {
            (currAdapter as ContactsAdapter).updateContacts(contacts)
        }

        setupLetterFastscroller(contacts)
    }

    private fun fillSuggestedContacts(callback: () -> Unit) {
        val privateCursor = getMyContactsCursor(false, true)
        ensureBackgroundThread {
            privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
            val suggestions = getSuggestedContacts(privateContacts)
            runOnUiThread {
                binding.suggestionsHolder.removeAllViews()
                if (suggestions.isEmpty()) {
                    binding.suggestionsLabel.beGone()
                    binding.suggestionsScrollview.beGone()
                } else {
                    binding.suggestionsLabel.beVisible()
                    binding.suggestionsScrollview.beVisible()
                    suggestions.forEach {
                        val contact = it
                        ItemSuggestedContactBinding.inflate(layoutInflater).apply {
                            suggestedContactName.text = contact.name
                            suggestedContactName.setTextColor(getProperTextColor())

                            if (!isDestroyed) {
                                SimpleContactsHelper(this@NewConversationActivity).loadContactImage(
                                    contact.photoUri,
                                    suggestedContactImage,
                                    contact.name
                                )
                                binding.suggestionsHolder.addView(root)
                                root.setOnClickListener {
                                    addRecipient(
                                        contact.phoneNumbers.first().normalizedNumber,
                                        contact.name
                                    )
                                }
                            }
                        }
                    }
                }
                callback()
            }
        }
    }

    private fun setupLetterFastscroller(contacts: ArrayList<SimpleContact>) {
        binding.contactsLetterFastscroller.setupWithRecyclerView(binding.contactsList, { position ->
            try {
                val name = contacts[position].name
                val character = if (name.isNotEmpty()) name.substring(0, 1) else ""
                FastScrollItemIndicator.Text(
                    character.uppercase(Locale.getDefault()).normalizeString()
                )
            } catch (e: Exception) {
                FastScrollItemIndicator.Text("")
            }
        })
    }

    private fun addRecipient(number: String, name: String) {
        val normalized = number.trim().replace(Regex("[\\s()-]"), "")
        if (normalized.isBlank() || selectedRecipients.containsKey(normalized)) return
        selectedRecipients[normalized] = name.ifBlank { normalized }
        binding.newConversationAddress.setText("")
        rebuildRecipientChips()
        updateSendButton()
    }

    private fun rebuildRecipientChips() {
        binding.selectedRecipientsHolder.removeAllViews()
        val density = resources.displayMetrics.density
        selectedRecipients.forEach { (number, name) ->
            val chip = TextView(this).apply {
                text = name.ifBlank { number }
                textSize = 16f
                setTextColor(Color.rgb(17, 17, 17))
                gravity = Gravity.CENTER
                isSingleLine = true
                minHeight = (40 * density).toInt()
                background = AppCompatResources.getDrawable(
                    this@NewConversationActivity,
                    R.drawable.suggested_chip_background
                )
                setPadding((14 * density).toInt(), 0, (10 * density).toInt(), 0)
                compoundDrawablePadding = (4 * density).toInt()
                setCompoundDrawablesWithIntrinsicBounds(
                    0,
                    0,
                    org.fossify.commons.R.drawable.ic_cross_vector,
                    0
                )
                setOnClickListener {
                    selectedRecipients.remove(number)
                    rebuildRecipientChips()
                    updateSendButton()
                }
            }
            val margin = (6 * resources.displayMetrics.density).toInt()
            chip.layoutParams = ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = margin }
            binding.selectedRecipientsHolder.addView(chip)
        }
        binding.selectedRecipientsScroll.beVisibleIf(selectedRecipients.isNotEmpty())
        binding.newConversationAddress.hint = getString(
            if (selectedRecipients.isEmpty()) R.string.add_contact_or_number else R.string.continue_add_recipient
        )
        binding.newConversationAddress.requestFocus()
    }

    private fun updateSendButton() {
        binding.newConversationSend.isEnabled =
            selectedRecipients.isNotEmpty() && binding.newConversationMessage.value.isNotBlank()
        binding.newConversationSend.alpha = 1f
        binding.newConversationSend.setColorFilter(
            if (binding.newConversationSend.isEnabled) Color.WHITE else Color.rgb(160, 160, 160)
        )
        updateAttachmentButton()
    }

    private fun updateAttachmentButton() {
        val hasSingleRecipient = selectedRecipients.size == 1
        binding.newConversationAddAttachment.alpha = if (hasSingleRecipient) 1f else 0.65f
        binding.newConversationAddAttachment.applyColorFilter(
            getColor(if (hasSingleRecipient) R.color.miui_action_blue else R.color.miui_secondary_text)
        )
    }

    private fun openAttachmentForRecipient() {
        when (selectedRecipients.size) {
            0 -> toast(R.string.attachment_select_one_recipient)
            1 -> {
                val (number, name) = selectedRecipients.entries.first()
                launchThreadActivity(
                    phoneNumber = number,
                    name = name,
                    body = binding.newConversationMessage.value,
                    openAttachmentPicker = true,
                )
                finish()
            }
            else -> toast(R.string.attachments_in_thread)
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupSIMSelector() {
        val active = runCatching { subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty() }
            .getOrDefault(emptyList())
        val simDisplayConfig = MultiForwardConfig(applicationContext)
        availableSIMCards.clear()
        active.forEachIndexed { index, info ->
            val slotIndex = info.simSlotIndex.takeIf { it >= 0 } ?: index
            val systemLabel = info.carrierName?.toString()?.takeIf(String::isNotBlank)
                ?: info.displayName?.toString().orEmpty()
            availableSIMCards += SIMCard(
                id = slotIndex + 1,
                subscriptionId = info.subscriptionId,
                label = simDisplayConfig.customSimLabel(slotIndex).ifBlank { systemLabel },
                phoneNumber = simDisplayConfig.customSimNumber(slotIndex).ifBlank { info.number.orEmpty() },
            )
        }
        if (availableSIMCards.isEmpty()) {
            binding.newConversationSimHolder.beGone()
            return
        }

        val defaultSubId = SmsManager.getDefaultSmsSubscriptionId()
        currentSIMCardIndex = availableSIMCards.indexOfFirst { it.subscriptionId == defaultSubId }
            .takeIf { it >= 0 }
            ?: 0
        binding.newConversationSimIcon.applyColorFilter(Color.rgb(29, 206, 56))
        binding.newConversationSimNumber.text = availableSIMCards[currentSIMCardIndex].id.toString()
        binding.newConversationSimHolder.beVisible()
        binding.newConversationSimHolder.setOnClickListener {
            SimSelectionPopup(
                context = this,
                cards = availableSIMCards,
                selectedIndex = currentSIMCardIndex,
            ) { selectedIndex ->
                currentSIMCardIndex = selectedIndex
                val card = availableSIMCards[selectedIndex]
                binding.newConversationSimNumber.text = card.id.toString()
                selectedRecipients.keys.forEach { config.saveUseSIMIdAtNumber(it, card.subscriptionId) }
            }.show(binding.newConversationInputHolder)
        }
    }

    private fun sendComposedMessage() {
        val body = binding.newConversationMessage.value.trim()
        when {
            selectedRecipients.isEmpty() -> toast(R.string.new_message_no_recipient)
            body.isBlank() -> toast(R.string.new_message_no_content)
            isScheduleMode -> launchScheduleDialog()
            else -> {
                BulkSendWorker.enqueue(
                    applicationContext,
                    body,
                    availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId
                        ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID,
                    selectedRecipients.keys.toList()
                )
                toast(R.string.new_message_queued)
                finish()
            }
        }
    }

    private fun launchScheduleDialog() {
        askForExactAlarmPermissionIfNeeded {
            ScheduleMessageDialog(this) { dateTime ->
                if (dateTime != null) scheduleComposedMessage(dateTime.millis)
            }
        }
    }

    private fun askForExactAlarmPermissionIfNeeded(callback: () -> Unit) {
        if (!isSPlus()) {
            callback()
            return
        }
        val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
        if (alarmManager.canScheduleExactAlarms()) {
            callback()
        } else {
            PermissionRequiredDialog(
                activity = this,
                textId = org.fossify.commons.R.string.allow_alarm_scheduled_messages,
                positiveActionCallback = { openRequestExactAlarmSettings(BuildConfig.APPLICATION_ID) },
            )
        }
    }

    private fun scheduleComposedMessage(sendAt: Long) {
        val body = binding.newConversationMessage.value.trim()
        val participants = ArrayList(selectedRecipients.map { (number, name) ->
            SimpleContact(
                rawId = 0,
                contactId = 0,
                name = name,
                photoUri = "",
                phoneNumbers = arrayListOf(
                    PhoneNumber(value = number, type = 0, label = "", normalizedNumber = number)
                ),
                birthdays = arrayListOf(),
                anniversaries = arrayListOf(),
            )
        })
        val messageId = generateRandomId()
        val message = Message(
            id = messageId,
            body = body,
            type = MESSAGE_TYPE_QUEUED,
            status = STATUS_NONE,
            participants = participants,
            date = (sendAt / 1000).toInt(),
            read = true,
            threadId = messageId,
            isMMS = false,
            attachment = null,
            senderPhoneNumber = "",
            senderName = "",
            senderPhotoUri = "",
            subscriptionId = availableSIMCards.getOrNull(currentSIMCardIndex)?.subscriptionId
                ?: SubscriptionManager.INVALID_SUBSCRIPTION_ID,
            isScheduled = true,
        )
        ensureBackgroundThread {
            createTemporaryThread(message, message.threadId, null)
            messagesDB.insertOrUpdate(message)
            scheduleMessage(message)
            runOnUiThread {
                toast(R.string.scheduled_created)
                finish()
            }
        }
    }

    private fun launchThreadActivity(
        phoneNumber: String,
        name: String,
        body: String = "",
        openAttachmentPicker: Boolean = false,
    ) {
        hideKeyboard()
        val numbers = phoneNumber.split(";").toSet()
        val number = if (numbers.size == 1) phoneNumber else Gson().toJson(numbers)
        Intent(this, ThreadActivity::class.java).apply {
            putExtra(THREAD_ID, getThreadId(numbers))
            putExtra(THREAD_TITLE, name)
            putExtra(THREAD_TEXT, body.ifEmpty { intent.getStringExtra(Intent.EXTRA_TEXT) })
            putExtra(THREAD_NUMBER, number)
            putExtra(OPEN_THREAD_ATTACHMENT_PICKER, openAttachmentPicker)

            if (intent.action == Intent.ACTION_SEND && intent.extras?.containsKey(Intent.EXTRA_STREAM) == true) {
                val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                putExtra(THREAD_ATTACHMENT_URI, uri?.toString())
            } else if (intent.action == Intent.ACTION_SEND_MULTIPLE && intent.extras?.containsKey(
                    Intent.EXTRA_STREAM
                ) == true
            ) {
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
                putExtra(THREAD_ATTACHMENT_URIS, uris)
            }

            startActivity(this)
        }
    }
}
