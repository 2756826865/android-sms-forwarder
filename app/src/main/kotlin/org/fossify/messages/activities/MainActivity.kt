package org.fossify.messages.activities

import android.annotation.SuppressLint
import android.Manifest
import android.app.AppOpsManager
import android.app.NotificationManager
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.provider.Telephony
import android.text.TextUtils
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.fossify.commons.dialogs.PermissionRequiredDialog
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.appLockManager
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.areSystemAnimationsEnabled
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beGoneIf
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.convertToBitmap
import org.fossify.commons.extensions.fadeIn
import org.fossify.commons.extensions.getMyContactsCursor
import org.fossify.commons.extensions.getInternalStoragePath
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.hideKeyboard
import org.fossify.commons.extensions.openNotificationSettings
import org.fossify.commons.extensions.onTextChangeListener
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.underlineText
import org.fossify.commons.extensions.updateSDCardPath
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.LOWER_ALPHA
import org.fossify.commons.helpers.MyContactsContentProvider
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.PERMISSION_READ_SMS
import org.fossify.commons.helpers.PERMISSION_SEND_SMS
import org.fossify.commons.helpers.SHORT_ANIMATION_DURATION
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.helpers.isQPlus
import org.fossify.messages.BuildConfig
import org.fossify.messages.R
import org.fossify.messages.adapters.ConversationsAdapter
import org.fossify.messages.adapters.SearchResultsAdapter
import org.fossify.messages.databinding.ActivityMainBinding
import org.fossify.messages.dialogs.ConversationActionsPopup
import org.fossify.messages.extensions.applySystemBarColors
import org.fossify.messages.extensions.checkAndDeleteOldRecycleBinMessages
import org.fossify.messages.extensions.clearAllMessagesIfNeeded
import org.fossify.messages.extensions.clearExpiredScheduledMessages
import org.fossify.messages.extensions.createConversationFromMessage
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getMessages
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.extensions.markThreadMessagesRead
import org.fossify.messages.extensions.showSmsStyled
import org.fossify.messages.extensions.syncThreadToLocal
import org.fossify.messages.helpers.SEARCHED_MESSAGE_ID
import org.fossify.messages.helpers.THREAD_ID
import org.fossify.messages.helpers.THREAD_TITLE
import org.fossify.messages.helpers.formatConversationDate
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Events
import org.fossify.messages.models.Message
import org.fossify.messages.models.SearchResult
import org.fossify.messages.messaging.SmsRecoveryWorker
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class MainActivity : SimpleActivity() {
    override var isSearchBarEnabled = false
    
    private val SMS_DEFAULT_APPLICATION_KEY = "sms_default_application"
    private val WRITE_SMS_APP_OP = "android:write_sms"
    private val ROLE_STATE_SETTLE_DELAY_MS = 500L
    private val DEFAULT_SMS_LOST_NOTIFICATION_ID = 19082

    private var storedTextColor = 0
    private var storedFontSize = 0
    private var lastSearchedText = ""
    private var bus: EventBus? = null
    private var isConversationSelectionMode = false

    private val binding by viewBinding(ActivityMainBinding::inflate)
    private val receiveSmsPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) toast(R.string.receive_sms_permission_required)
    }
    private val makeDefaultSmsAppLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        handleDefaultSmsRoleResult(result.resultCode)
    }
    private val legacyDefaultSmsAppLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        askPermissions()
    }

    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        initializeAppSession()
        config.showListAvatars = true
        config.showLetterAvatars = false
        config.useRecycleBin = true
        setupHomeSearch()
        setupSelectionActions()
        setupBottomNavigation()
        applyHomeBottomNavigationPreference()

        setupEdgeToEdge(
            padTopSystem = listOf(binding.homeHeader),
            padBottomSystem = listOf(binding.homeBottomNavigation, binding.selectionBottomBar),
            padBottomImeAndSystem = listOf(binding.homeSearch),
        )

        binding.mainCoordinator.post { applyHomeBottomNavigationPreference() }
        showFirstUseNoticeIfNeeded()

        checkAndDeleteOldRecycleBinMessages()
        clearAllMessagesIfNeeded {
            loadMessages()
        }

    }

    private fun initializeAppSession() {
        config.internalStoragePath = getInternalStoragePath()
        updateSDCardPath()
        config.appId = BuildConfig.APPLICATION_ID
        config.appRunCount++
    }

    private fun showFirstUseNoticeIfNeeded() {
        if (config.firstUseNoticeAccepted) {
            return
        }

        binding.root.post {
            if (isFinishing || isDestroyed || config.firstUseNoticeAccepted) {
                return@post
            }

            AlertDialog.Builder(this)
                .setTitle(R.string.first_use_notice_title)
                .setMessage(R.string.first_use_notice_message)
                .setPositiveButton(R.string.first_use_notice_accept) { _, _ ->
                    config.firstUseNoticeAccepted = true
                }
                .setCancelable(false)
                .create()
                .showSmsStyled()
        }
    }

    override fun onResume() {
        super.onResume()
        if (Telephony.Sms.getDefaultSmsPackage(this) == packageName) {
            getSystemService(NotificationManager::class.java)
                ?.cancel(DEFAULT_SMS_LOST_NOTIFICATION_ID)
        }
        SmsRecoveryWorker.enqueueNow(this)
        updateMenuColors()
        selectPrimaryNavigation()
        applyHomeBottomNavigationPreference()

        getOrCreateConversationsAdapter().apply {
            if (storedTextColor != getProperTextColor()) {
                updateTextColor(getProperTextColor())
            }

            if (storedFontSize != config.fontSize) {
                updateFontSize()
            }

            updateDrafts()
        }

        val pageColor = ContextCompat.getColor(this, R.color.miui_card_background)
        val properPrimaryColor = ContextCompat.getColor(this, R.color.miui_action_blue)
        binding.homeHeader.setBackgroundColor(pageColor)
        binding.homeTitle.setTextColor(ContextCompat.getColor(this, R.color.miui_primary_text))
        binding.homeSearch.setTextColor(ContextCompat.getColor(this, R.color.miui_primary_text))
        binding.homeSearch.setHintTextColor(ContextCompat.getColor(this, R.color.miui_hint_text))
        binding.conversationsFab.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.home_fab_light_green)
        )
        binding.conversationsFab.imageTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, android.R.color.white)
        )
        applySystemBarColors(pageColor)
        binding.searchHolder.setBackgroundColor(pageColor)
        binding.noConversationsPlaceholder2.setTextColor(properPrimaryColor)
        binding.noConversationsPlaceholder2.underlineText()
        binding.conversationsFastscroller.updateColors(properPrimaryColor)
        binding.conversationsProgressBar.setIndicatorColor(properPrimaryColor)
        binding.conversationsProgressBar.trackColor = properPrimaryColor.adjustAlpha(LOWER_ALPHA)
        checkShortcut()
    }

    override fun onPause() {
        super.onPause()
        storeStateVariables()
    }

    override fun onDestroy() {
        super.onDestroy()
        bus?.unregister(this)
    }

    override fun onBackPressedCompat(): Boolean {
        return if (isConversationSelectionMode) {
            getOrCreateConversationsAdapter().finishActMode()
            true
        } else if (binding.homeSearch.text?.isNotEmpty() == true) {
            binding.homeSearch.setText("")
            true
        } else {
            appLockManager.lock()
            false
        }
    }

    private fun setupHomeSearch() {
        binding.homeSearch.onTextChangeListener { text ->
            if (text.isNotBlank()) {
                binding.searchHolder.beVisible()
                binding.searchHolder.alpha = 1f
                searchTextChanged(text, true)
            } else {
                binding.searchHolder.beGone()
                searchTextChanged("", true)
            }
        }
    }

    private fun setupSelectionActions() = binding.apply {
        selectionClose.setOnClickListener { getOrCreateConversationsAdapter().finishActMode() }
        selectionSelectAll.setOnClickListener {
            getOrCreateConversationsAdapter().selectAllConversations()
        }
        selectionMarkRead.setOnClickListener {
            getOrCreateConversationsAdapter().toggleSelectedReadState()
        }
        selectionDelete.setOnClickListener {
            getOrCreateConversationsAdapter().deleteSelected()
        }
    }

    private fun setupBottomNavigation() {
        binding.homeNavPrimary.contentDescription = getString(R.string.bottom_nav_primary_description)
        binding.homeNavForward.contentDescription = getString(R.string.bottom_nav_forward_description)
        binding.homeNavSettings.contentDescription = getString(R.string.bottom_nav_settings_description)
        binding.homeNavPrimary.setOnClickListener {
            binding.homeSearch.setText("")
            binding.conversationsList.stopScroll()
            binding.conversationsList.scrollToPosition(0)
            selectPrimaryNavigation()
        }
        binding.homeNavForward.setOnClickListener {
            binding.homeNavPrimary.isSelected = false
            binding.homeNavForward.isSelected = true
            binding.homeNavSettings.isSelected = false
            hideKeyboard()
            startActivity(Intent(this, ForwardingChannelsActivity::class.java))
        }
        binding.homeNavSettings.setOnClickListener {
            binding.homeNavPrimary.isSelected = false
            binding.homeNavForward.isSelected = false
            binding.homeNavSettings.isSelected = true
            hideKeyboard()
            launchSettings()
        }
    }

    private fun selectPrimaryNavigation() {
        binding.homeNavPrimary.isSelected = true
        binding.homeNavForward.isSelected = false
        binding.homeNavSettings.isSelected = false
    }

    private fun applyHomeBottomNavigationPreference() = binding.apply {
        if (isConversationSelectionMode) {
            return@apply
        }

        val showNav = config.showHomeBottomNavigation
        homeBottomNavigation.beVisibleIf(showNav)
        if (showNav) {
            homeBottomNavigation.bringToFront()
        }
        conversationsFab.bringToFront()
        selectionBottomBar.bringToFront()

        val contentBottomPadding = resources.getDimensionPixelSize(R.dimen.home_content_bottom_padding)
        conversationsList.setPadding(
            conversationsList.paddingLeft,
            conversationsList.paddingTop,
            conversationsList.paddingRight,
            contentBottomPadding,
        )
        searchResultsList.setPadding(
            searchResultsList.paddingLeft,
            searchResultsList.paddingTop,
            searchResultsList.paddingRight,
            contentBottomPadding,
        )
    }

    private fun isSmsChainReady(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        val roleReady = getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true
        val routeReady = runCatching {
            Settings.Secure.getString(contentResolver, SMS_DEFAULT_APPLICATION_KEY) == packageName
        }.getOrDefault(false)
        val writeSmsReady = runCatching {
            val appOps = getSystemService(AppOpsManager::class.java)
            appOps?.checkOpNoThrow(WRITE_SMS_APP_OP, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)
        return roleReady && routeReady && writeSmsReady
    }

    private fun launchLegacyDefaultSmsRequest(): Boolean = runCatching {
        val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
            .putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        legacyDefaultSmsAppLauncher.launch(intent)
        true
    }.getOrDefault(false)

    private fun handleDefaultSmsRoleResult(resultCode: Int) {
        if (resultCode != RESULT_OK) {
            finish()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            binding.root.postDelayed({
                if (!isSmsChainReady() && launchLegacyDefaultSmsRequest()) {
                    return@postDelayed
                }
                askPermissions()
            }, ROLE_STATE_SETTLE_DELAY_MS)
        } else {
            askPermissions()
        }
    }

    private fun storeStateVariables() {
        storedTextColor = getProperTextColor()
        storedFontSize = config.fontSize
    }

    private fun updateMenuColors() = Unit

    private fun loadMessages() {
        if (isQPlus()) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager!!.isRoleAvailable(RoleManager.ROLE_SMS)) {
                if (roleManager.isRoleHeld(RoleManager.ROLE_SMS)) {
                    askPermissions()
                } else {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
                    makeDefaultSmsAppLauncher.launch(intent)
                }
            } else {
                toast(org.fossify.commons.R.string.unknown_error_occurred)
                finish()
            }
        } else {
            if (Telephony.Sms.getDefaultSmsPackage(this) == packageName) {
                askPermissions()
            } else {
                val intent = Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT)
                intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
                legacyDefaultSmsAppLauncher.launch(intent)
            }
        }
    }

    // while SEND_SMS and READ_SMS permissions are mandatory, READ_CONTACTS is optional.
    // If we don't have it, we just won't be able to show the contact name in some cases
    private fun askPermissions() {
        handlePermission(PERMISSION_READ_SMS) {
            if (it) {
                handlePermission(PERMISSION_SEND_SMS) {
                    if (it) {
                        ensureReceiveSmsPermission()
                        handlePermission(PERMISSION_READ_CONTACTS) {
                            handleNotificationPermission { granted ->
                                if (!granted) {
                                    PermissionRequiredDialog(
                                        activity = this,
                                        textId = org.fossify.commons.R.string.allow_notifications_incoming_messages,
                                        positiveActionCallback = { openNotificationSettings() })
                                }
                            }

                            initMessenger()
                            bus = EventBus.getDefault()
                            try {
                                bus!!.register(this)
                            } catch (_: Exception) {
                            }
                        }
                    } else {
                        finish()
                    }
                }
            } else {
                finish()
            }
        }
    }

    private fun ensureReceiveSmsPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) != PackageManager.PERMISSION_GRANTED) {
            receiveSmsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS)
        }
    }

    private fun initMessenger() {
        storeStateVariables()
        getCachedConversations()
        binding.noConversationsPlaceholder2.setOnClickListener {
            launchNewConversation()
        }

        binding.conversationsFab.setOnClickListener {
            launchNewConversation()
        }
        binding.homeSettings.setOnClickListener {
            launchSettings()
        }
        binding.homeMarkAllRead.setOnClickListener {
            markAllAsRead()
        }
    }

    private fun markAllAsRead() {
        ensureBackgroundThread {
            conversationsDB.getNonArchived()
                .filterNot { it.read }
                .forEach { markThreadMessagesRead(it.threadId) }
            runOnUiThread {
                getCachedConversations()
                toast(R.string.all_messages_marked_read)
            }
        }
    }

    private fun getCachedConversations() {
        ensureBackgroundThread {
            val conversations = try {
                conversationsDB.getNonArchived().toMutableList() as ArrayList<Conversation>
            } catch (_: Exception) {
                ArrayList()
            }

            val archived = try {
                conversationsDB.getAllArchived()
            } catch (_: Exception) {
                listOf()
            }

            runOnUiThread {
                setupConversations(conversations, cached = true)
                getNewConversations(
                    (conversations + archived).toMutableList() as ArrayList<Conversation>
                )
            }
            conversations.forEach {
                clearExpiredScheduledMessages(it.threadId)
            }
        }
    }

    private fun getNewConversations(cachedConversations: ArrayList<Conversation>) {
        val privateCursor = getMyContactsCursor(favoritesOnly = false, withPhoneNumbersOnly = true)
        ensureBackgroundThread {
            val privateContacts = MyContactsContentProvider.getSimpleContacts(this, privateCursor)
            val conversations = getConversations(privateContacts = privateContacts)

            conversations.forEach { providerConversation ->
                insertOrUpdateConversation(providerConversation)
                if (cachedConversations.none { it.threadId == providerConversation.threadId }) {
                    cachedConversations.add(providerConversation)
                }
            }

            cachedConversations.forEach { cachedConversation ->
                val threadId = cachedConversation.threadId

                val isTemporaryThread = cachedConversation.isScheduled
                val isConversationDeleted = !conversations.map { it.threadId }.contains(threadId)
                val hasLocalMessages = messagesDB.getThreadMessages(threadId).isNotEmpty()
                if (isConversationDeleted && !isTemporaryThread && !hasLocalMessages) {
                    conversationsDB.deleteThreadId(threadId)
                }

                val newConversation =
                    conversations.find { it.phoneNumber == cachedConversation.phoneNumber }
                if (isTemporaryThread && newConversation != null) {
                    // delete the original temporary thread and move any scheduled messages
                    // to the new thread
                    conversationsDB.deleteThreadId(threadId)
                    messagesDB.getScheduledThreadMessages(threadId)
                        .forEach { message ->
                            messagesDB.insertOrUpdate(
                                message.copy(threadId = newConversation.threadId)
                            )
                        }
                    insertOrUpdateConversation(newConversation, cachedConversation)
                }
            }

            cachedConversations.forEach { cachedConv ->
                val conv = conversations.find {
                    it.threadId == cachedConv.threadId && !Conversation.areContentsTheSame(
                        old = cachedConv, new = it
                    )
                }
                if (conv != null) {
                    // FIXME: Scheduled message date is being reset here. Conversations with
                    //  scheduled messages will have their original date.
                    insertOrUpdateConversation(conv)
                }
            }

            val needsFullHistorySync = !config.fullHistorySyncedV2
            conversations.forEach { conversation ->
                syncThreadToLocal(conversation.threadId, loadAll = needsFullHistorySync)
            }
            if (needsFullHistorySync) {
                config.fullHistorySyncedV2 = true
            }

            messagesDB.getAll()
                .filter { !it.isScheduled && it.threadId != 0L }
                .groupBy { it.threadId }
                .forEach { (threadId, threadMessages) ->
                    if (conversationsDB.getConversationWithThreadId(threadId) == null) {
                        threadMessages.maxByOrNull { it.date }
                            ?.let(::createConversationFromMessage)
                            ?.let(::insertOrUpdateConversation)
                    }
                }

            val allConversations = ArrayList(conversationsDB.getNonArchived())
            runOnUiThread {
                setupConversations(allConversations)
            }
        }
    }

    private fun getOrCreateConversationsAdapter(): ConversationsAdapter {
        var currAdapter = binding.conversationsList.adapter
        if (currAdapter == null) {
            hideKeyboard()
            currAdapter = ConversationsAdapter(
                activity = this,
                recyclerView = binding.conversationsList,
                onRefresh = { notifyDatasetChanged() },
                itemClick = { handleConversationClick(it) },
                itemLongClick = { anchor, conversation ->
                    showConversationActions(anchor, conversation)
                },
                selectionChanged = { active, count ->
                    updateConversationSelectionUi(active, count)
                },
            )

            binding.conversationsList.adapter = currAdapter
            if (areSystemAnimationsEnabled) {
                binding.conversationsList.scheduleLayoutAnimation()
            }
        }
        return currAdapter as ConversationsAdapter
    }

    private fun showConversationActions(anchor: android.view.View, conversation: Conversation) {
        val pinned = config.pinnedConversations.contains(conversation.threadId.toString())
        ConversationActionsPopup(this, conversation, pinned) { action ->
            val adapter = getOrCreateConversationsAdapter()
            when (action) {
                ConversationActionsPopup.ACTION_READ -> adapter.performSingleAction(
                    conversation,
                    if (conversation.read) R.id.cab_mark_as_unread else R.id.cab_mark_as_read,
                )

                ConversationActionsPopup.ACTION_PIN -> adapter.performSingleAction(
                    conversation,
                    if (pinned) R.id.cab_unpin_conversation else R.id.cab_pin_conversation,
                )

                ConversationActionsPopup.ACTION_DELETE ->
                    adapter.performSingleAction(conversation, R.id.cab_delete)

                ConversationActionsPopup.ACTION_MULTI -> adapter.startSelection(conversation)
            }
        }.show(anchor)
    }

    private fun updateConversationSelectionUi(active: Boolean, count: Int) = binding.apply {
        isConversationSelectionMode = active
        homeNormalTopRow.beGoneIf(active)
        selectionTopRow.beVisibleIf(active)
        selectionTitle.beVisibleIf(active)
        selectionBottomBar.beVisibleIf(active)
        homeBottomNavigation.beGoneIf(active)
        selectionTitle.text = getString(R.string.selected_conversations, count)
        conversationsFab.beGoneIf(active)
        if (!active) {
            conversationsFab.beVisible()
            selectPrimaryNavigation()
            applyHomeBottomNavigationPreference()
        }
        selectionMarkRead.text = getString(
            if (count > 0 && getOrCreateConversationsAdapter().selectedHasUnread()) {
                R.string.mark_as_read
            } else {
                R.string.mark_as_unread
            }
        )
        conversationsList.setPadding(
            conversationsList.paddingLeft,
            conversationsList.paddingTop,
            conversationsList.paddingRight,
            if (active) {
                resources.getDimensionPixelSize(R.dimen.selection_content_bottom_padding)
            } else {
                resources.getDimensionPixelSize(R.dimen.home_content_bottom_padding)
            },
        )
    }

    private fun setupConversations(
        conversations: ArrayList<Conversation>,
        cached: Boolean = false,
    ) {
        val sortedConversations = conversations
            .sortedWith(
                compareByDescending<Conversation> {
                    config.pinnedConversations.contains(it.threadId.toString())
                }.thenByDescending { it.date }
            ).toMutableList() as ArrayList<Conversation>

        if (cached && config.appRunCount == 1) {
            // there are no cached conversations on the first run so we show the
            // loading placeholder and progress until we are done loading from telephony
            showOrHideProgress(conversations.isEmpty())
        } else {
            showOrHideProgress(false)
            showOrHidePlaceholder(conversations.isEmpty())
        }

        try {
            getOrCreateConversationsAdapter().apply {
                updateConversations(sortedConversations) {
                    if (!cached) {
                        showOrHidePlaceholder(currentList.isEmpty())
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun showOrHideProgress(show: Boolean) {
        if (show) {
            binding.conversationsProgressBar.show()
            binding.conversationsFastscroller.beGone()
            binding.noConversationsPlaceholder2.beGone()
            binding.noConversationsPlaceholder.beVisible()
            binding.noConversationsPlaceholder.text = getString(R.string.loading_messages)
        } else {
            binding.conversationsProgressBar.hide()
            binding.conversationsFastscroller.beVisible()
            binding.noConversationsPlaceholder.beGone()
        }
    }

    private fun showOrHidePlaceholder(show: Boolean) {
        binding.conversationsFastscroller.beGoneIf(show)
        binding.noConversationsPlaceholder.beVisibleIf(show)
        binding.noConversationsPlaceholder.text = getString(R.string.no_conversations_found)
        binding.noConversationsPlaceholder2.beVisibleIf(show)
    }

    private fun fadeOutSearch() {
        binding.searchHolder.animate()
            .alpha(0f)
            .setDuration(SHORT_ANIMATION_DURATION)
            .withEndAction {
                binding.searchHolder.beGone()
                searchTextChanged("", true)
            }.start()
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun notifyDatasetChanged() {
        getOrCreateConversationsAdapter().notifyDataSetChanged()
    }

    private fun handleConversationClick(any: Any) {
        Intent(this, ThreadActivity::class.java).apply {
            val conversation = any as Conversation
            putExtra(THREAD_ID, conversation.threadId)
            putExtra(THREAD_TITLE, conversation.title)
            startActivity(this)
        }
    }

    private fun launchNewConversation() {
        hideKeyboard()
        Intent(this, NewConversationActivity::class.java).apply {
            startActivity(this)
        }
    }

    @SuppressLint("NewApi")
    private fun checkShortcut() {
        val appIconColor = config.appIconColor
        if (config.lastHandledShortcutColor != appIconColor) {
            val newConversation = getCreateNewContactShortcut(appIconColor)

            val manager = getSystemService(ShortcutManager::class.java)
            try {
                manager.dynamicShortcuts = listOf(newConversation)
                config.lastHandledShortcutColor = appIconColor
            } catch (_: Exception) {
            }
        }
    }

    @SuppressLint("NewApi")
    private fun getCreateNewContactShortcut(appIconColor: Int): ShortcutInfo {
        val newEvent = getString(R.string.new_conversation)
        val drawable =
            AppCompatResources.getDrawable(this, org.fossify.commons.R.drawable.shortcut_plus)

        (drawable as LayerDrawable).findDrawableByLayerId(
            org.fossify.commons.R.id.shortcut_plus_background
        ).applyColorFilter(appIconColor)

        val bmp = drawable.convertToBitmap()

        val intent = Intent(this, NewConversationActivity::class.java)
        intent.action = Intent.ACTION_VIEW
        return ShortcutInfo.Builder(this, "new_conversation")
            .setShortLabel(newEvent)
            .setLongLabel(newEvent)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .setRank(0)
            .build()
    }

    private fun searchTextChanged(text: String, forceUpdate: Boolean = false) {
        if (binding.homeSearch.text.isNullOrBlank() && !forceUpdate) {
            return
        }

        lastSearchedText = text
        binding.searchPlaceholder2.beGoneIf(text.length >= 2)
        if (text.length >= 2) {
            ensureBackgroundThread {
                val searchQuery = "%$text%"
                val messages = messagesDB.getMessagesWithText(searchQuery)
                val conversations = conversationsDB.getConversationsWithText(searchQuery)
                if (text == lastSearchedText) {
                    showSearchResults(messages, conversations, text)
                }
            }
        } else {
            binding.searchPlaceholder.beVisible()
            binding.searchResultsList.beGone()
        }
    }

    private fun showSearchResults(
        messages: List<Message>,
        conversations: List<Conversation>,
        searchedText: String,
    ) {
        val searchResults = ArrayList<SearchResult>()
        conversations.forEach { conversation ->
            val date = formatConversationDate(conversation.date)

            val searchResult = SearchResult(
                messageId = -1,
                title = conversation.title,
                snippet = conversation.phoneNumber,
                date = date,
                threadId = conversation.threadId,
                photoUri = conversation.photoUri
            )
            searchResults.add(searchResult)
        }

        messages.sortedByDescending { it.id }.forEach { message ->
            var recipient = message.senderName
            if (recipient.isEmpty() && message.participants.isNotEmpty()) {
                val participantNames = message.participants.map { it.name }
                recipient = TextUtils.join(", ", participantNames)
            }

            val date = formatConversationDate(message.date)

            val searchResult = SearchResult(
                messageId = message.id,
                title = recipient,
                snippet = message.body,
                date = date,
                threadId = message.threadId,
                photoUri = message.senderPhotoUri
            )
            searchResults.add(searchResult)
        }

        runOnUiThread {
            binding.searchResultsList.beVisibleIf(searchResults.isNotEmpty())
            binding.searchPlaceholder.beVisibleIf(searchResults.isEmpty())

            val currAdapter = binding.searchResultsList.adapter
            if (currAdapter == null) {
                SearchResultsAdapter(this, searchResults, binding.searchResultsList, searchedText) {
                    hideKeyboard()
                    Intent(this, ThreadActivity::class.java).apply {
                        putExtra(THREAD_ID, (it as SearchResult).threadId)
                        putExtra(THREAD_TITLE, it.title)
                        putExtra(SEARCHED_MESSAGE_ID, it.messageId)
                        startActivity(this)
                    }
                }.apply {
                    binding.searchResultsList.adapter = this
                }
            } else {
                (currAdapter as SearchResultsAdapter).updateItems(searchResults, searchedText)
            }
        }
    }

    private fun launchRecycleBin() {
        hideKeyboard()
        startActivity(Intent(applicationContext, RecycleBinConversationsActivity::class.java))
    }

    private fun launchArchivedConversations() {
        hideKeyboard()
        startActivity(Intent(applicationContext, ArchivedConversationsActivity::class.java))
    }

    private fun launchSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, SettingsActivity::class.java))
    }

    private fun launchPushPlusSettings() {
        hideKeyboard()
        startActivity(Intent(applicationContext, PushPlusSettingsActivity::class.java))
    }

    private fun launchBulkSend() {
        hideKeyboard()
        startActivity(Intent(applicationContext, BulkSendActivity::class.java))
    }

    private fun resyncAllMessages() {
        config.fullHistorySyncedV2 = false
        toast(R.string.resync_started)
        getCachedConversations()
    }

    private fun launchAbout() {
        startActivity(Intent(applicationContext, AboutActivity::class.java))
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun refreshConversations(@Suppress("unused") event: Events.RefreshConversations) {
        initMessenger()
    }

}
