package org.fossify.messages.dialogs

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import org.fossify.messages.R
import org.fossify.messages.models.Conversation

class ConversationActionsPopup(
    private val context: Context,
    private val conversation: Conversation,
    private val isPinned: Boolean,
    private val onAction: (Int) -> Unit,
) {
    private val density = context.resources.displayMetrics.density

    fun show(anchor: View) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(22).toFloat()
            }
        }
        addRow(content, if (conversation.read) R.string.mark_as_unread else R.string.mark_as_read, ACTION_READ)
        addRow(content, if (isPinned) R.string.unpin_conversation else R.string.pin_conversation, ACTION_PIN)
        addRow(content, org.fossify.commons.R.string.delete, ACTION_DELETE)
        content.addView(View(context).apply { setBackgroundColor(Color.rgb(232, 232, 232)) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)).apply {
                marginStart = dp(12)
                marginEnd = dp(12)
            })
        addRow(content, R.string.multi_select, ACTION_MULTI)

        val width = minOf(dp(280), context.resources.displayMetrics.widthPixels - dp(40))
        val heightEstimate = dp(244)
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        PopupWindow(content, width, ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            isOutsideTouchable = true
            elevation = dp(14).toFloat()
            setBackgroundDrawable(GradientDrawable().apply { setColor(Color.TRANSPARENT) })
            showAtLocation(
                anchor.rootView,
                Gravity.TOP or Gravity.END,
                dp(20),
                (location[1] + anchor.height / 2 - heightEstimate / 2).coerceAtLeast(dp(72)),
            )
            activePopup = this
        }
    }

    private fun addRow(parent: LinearLayout, textId: Int, action: Int) {
        parent.addView(TextView(context).apply {
            setText(textId)
            setTextColor(Color.rgb(17, 17, 17))
            textSize = 17f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
            background = context.getDrawable(org.fossify.commons.R.drawable.ripple_all_corners)
            setOnClickListener {
                activePopup?.dismiss()
                onAction(action)
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
    }

    private fun dp(value: Int) = (value * density).toInt()

    companion object {
        const val ACTION_READ = 1
        const val ACTION_PIN = 2
        const val ACTION_DELETE = 3
        const val ACTION_MULTI = 4
        private var activePopup: PopupWindow? = null
    }
}
