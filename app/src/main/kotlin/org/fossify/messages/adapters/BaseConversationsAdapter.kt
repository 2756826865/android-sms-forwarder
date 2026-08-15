package org.fossify.messages.adapters

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Typeface
import android.os.Parcelable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.qtalk.recyclerviewfastscroller.RecyclerViewFastScroller
import org.fossify.commons.adapters.MyRecyclerViewListAdapter
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beVisibleIf
import org.fossify.commons.extensions.getTextSize
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.setupViewBackground
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.SimpleContactsHelper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.commons.views.MyRecyclerView
import org.fossify.messages.R
import org.fossify.messages.activities.SimpleActivity
import org.fossify.messages.databinding.ItemConversationBinding
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.getAllDrafts
import org.fossify.messages.helpers.formatConversationDate
import org.fossify.messages.models.Conversation

@Suppress("LeakingThis")
abstract class BaseConversationsAdapter(
    activity: SimpleActivity,
    recyclerView: MyRecyclerView,
    onRefresh: () -> Unit,
    itemClick: (Any) -> Unit,
    private val itemLongClick: ((View, Conversation) -> Unit)? = null,
) : MyRecyclerViewListAdapter<Conversation>(
    activity = activity,
    recyclerView = recyclerView,
    diffUtil = ConversationDiffCallback(),
    itemClick = itemClick,
    onRefresh = onRefresh
),
    RecyclerViewFastScroller.OnPopupTextUpdate {
    private var fontSize = activity.getTextSize()
    private var drafts = HashMap<Long, String>()

    private var recyclerViewState: Parcelable? = null

    init {
        setupDragListener(true)
        setHasStableIds(true)
        updateDrafts()

        registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = restoreRecyclerViewState()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) =
                restoreRecyclerViewState()

            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) =
                restoreRecyclerViewState()
        })
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateFontSize() {
        fontSize = activity.getTextSize()
        notifyDataSetChanged()
    }

    fun updateConversations(
        newConversations: ArrayList<Conversation>,
        commitCallback: (() -> Unit)? = null,
    ) {
        saveRecyclerViewState()
        submitList(newConversations.toList(), commitCallback)
    }

    @SuppressLint("NotifyDataSetChanged")
    fun updateDrafts() {
        ensureBackgroundThread {
            val newDrafts = HashMap<Long, String>()
            fetchDrafts(newDrafts)
            activity.runOnUiThread {
                if (drafts.hashCode() != newDrafts.hashCode()) {
                    drafts = newDrafts
                    notifyDataSetChanged()
                }
            }
        }
    }

    override fun getSelectableItemCount() = itemCount

    protected fun getSelectedItems() = currentList.filter {
        selectedKeys.contains(it.hashCode())
    } as ArrayList<Conversation>

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = currentList.getOrNull(position)?.hashCode()

    override fun getItemKeyPosition(key: Int) = currentList.indexOfFirst { it.hashCode() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemConversationBinding.inflate(layoutInflater, parent, false)
        return createViewHolder(binding.root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val conversation = getItem(position)
        holder.bindView(
            conversation,
            allowSingleClick = true,
            allowLongClick = true
        ) { itemView, _ ->
            setupView(itemView, conversation)
        }
        if (itemLongClick != null && actMode == null) {
            holder.itemView.setOnLongClickListener {
                itemLongClick.invoke(it, conversation)
                true
            }
        }
        bindViewHolder(holder)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int, payloads: MutableList<Any>) {
        onBindViewHolder(holder, position)
    }

    override fun getItemId(position: Int) = getItem(position).threadId

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        if (!activity.isDestroyed && !activity.isFinishing) {
            val itemView = ItemConversationBinding.bind(holder.itemView)
            Glide.with(activity).clear(itemView.conversationImage)
        }
    }

    private fun fetchDrafts(drafts: HashMap<Long, String>) {
        drafts.clear()
        for ((threadId, draft) in activity.getAllDrafts()) {
            drafts[threadId] = draft
        }
    }

    private fun setupView(view: View, conversation: Conversation) {
        ItemConversationBinding.bind(view).apply {
            root.setupViewBackground(activity)
            val smsDraft = drafts[conversation.threadId]
            draftIndicator.beVisibleIf(!smsDraft.isNullOrEmpty())
            draftIndicator.setTextColor(properPrimaryColor)

            pinIndicator.beVisibleIf(
                activity.config.pinnedConversations.contains(conversation.threadId.toString())
            )
            pinIndicator.applyColorFilter(textColor)

            val isSelected = selectedKeys.contains(conversation.hashCode())
            conversationFrame.isSelected = isSelected
            conversationFrame.setBackgroundColor(
                activity.getColor(
                    if (isSelected) R.color.miui_selected_background else R.color.miui_card_background
                )
            )
            conversationSelectionIndicator.beVisibleIf(actMode != null)
            conversationSelectionIndicator.setImageResource(
                if (isSelected) org.fossify.commons.R.drawable.ic_check_vector else 0
            )
            conversationSelectionIndicator.setColorFilter(Color.WHITE)
            conversationSelectionIndicator.setBackgroundResource(
                if (isSelected) R.drawable.selection_circle_checked else R.drawable.selection_circle_unchecked
            )

            conversationAddress.apply {
                text = conversation.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            }

            conversationBodyShort.apply {
                text = smsDraft ?: conversation.snippet
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            }

            conversationDate.apply {
                text = formatConversationDate(conversation.date)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            }

            val isUnread = !conversation.read
            unreadIndicator.beVisibleIf(isUnread)
            val style = if (isUnread) {
                if (conversation.isScheduled) Typeface.BOLD_ITALIC else Typeface.BOLD
            } else {
                if (conversation.isScheduled) Typeface.ITALIC else Typeface.NORMAL
            }
            conversationAddress.setTypeface(Typeface.create("sans-serif", style))
            conversationBodyShort.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
            conversationDate.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL))
            conversationAddress.setTextColor(activity.getColor(R.color.miui_primary_text))
            conversationBodyShort.setTextColor(activity.getColor(R.color.miui_secondary_text))
            conversationDate.setTextColor(activity.getColor(R.color.miui_timestamp_text))
            conversationAddress.alpha = 1f
            conversationBodyShort.alpha = 1f
            conversationDate.alpha = 1f

            setupBadgeCount(unreadCountBadge, isUnread, conversation.unreadCount)
            conversationImage.beVisibleIf(activity.config.showListAvatars)
            // at group conversations we use an icon as the placeholder, not any letter
            val placeholder = if (conversation.isGroupConversation) {
                SimpleContactsHelper(activity).getColoredGroupIcon(conversation.title)
            } else {
                null
            }

            if (activity.config.showListAvatars) {
                if (!activity.hasPermission(PERMISSION_READ_CONTACTS) || conversation.photoUri.isBlank()) {
                    conversationImage.setImageResource(org.fossify.commons.R.drawable.ic_person_vector)
                    conversationImage.setBackgroundResource(R.drawable.miui_avatar_background)
                    conversationImage.setPadding(14, 14, 14, 14)
                } else {
                    conversationImage.background = null
                    conversationImage.setPadding(0, 0, 0, 0)
                    SimpleContactsHelper(activity).loadContactImage(
                        path = conversation.photoUri,
                        imageView = conversationImage,
                        placeholderName = conversation.title,
                        placeholderImage = placeholder
                    )
                }
            }
        }
    }

    private fun setupBadgeCount(view: TextView, isUnread: Boolean, count: Int) {
        view.apply {
            beVisibleIf(isUnread)
            if (isUnread) {
                text = when {
                    count > MAX_UNREAD_BADGE_COUNT -> "$MAX_UNREAD_BADGE_COUNT+"
                    count == 0 -> ""
                    else -> count.toString()
                }
                setTextColor(activity.getColor(android.R.color.white))
                setBackgroundResource(R.drawable.unread_badge_background)
            }
        }
    }

    override fun onChange(position: Int) = currentList.getOrNull(position)?.title ?: ""

    private fun saveRecyclerViewState() {
        recyclerViewState = recyclerView.layoutManager?.onSaveInstanceState()
    }

    private fun restoreRecyclerViewState() {
        recyclerView.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }

    private class ConversationDiffCallback : DiffUtil.ItemCallback<Conversation>() {
        override fun areItemsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
            return Conversation.areItemsTheSame(oldItem, newItem)
        }

        override fun areContentsTheSame(oldItem: Conversation, newItem: Conversation): Boolean {
            return Conversation.areContentsTheSame(oldItem, newItem)
        }
    }

    companion object {
        private const val MAX_UNREAD_BADGE_COUNT = 99
    }
}
