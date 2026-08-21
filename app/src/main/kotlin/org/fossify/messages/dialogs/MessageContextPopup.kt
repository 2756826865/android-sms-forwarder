package org.fossify.messages.dialogs

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import org.fossify.messages.R

/**
 * MIUI-style floating text menu shown near a long-pressed message bubble.
 */
class MessageContextPopup(
    private val context: Context,
    private val items: List<Item>,
    private val onItemClick: (ItemId) -> Unit,
) {
    enum class ItemId {
        COPY,
        SHARE,
        SAVE_AS,
        DELETE,
        FORWARD,
        SELECT_TEXT,
        DETAILS,
        RESTORE,
        MULTI_SELECT,
    }

    data class Item(val id: ItemId, val title: String)

    private val density = context.resources.displayMetrics.density

    fun show(anchor: View) {
        if (items.isEmpty()) return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AppCompatResources.getDrawable(context, R.drawable.message_context_popup_background)
            clipToOutline = true
            setPadding(0, dp(6), 0, dp(6))
        }

        items.forEach { item ->
            container.addView(createRow(item.title) {
                onItemClick(item.id)
                popupWindow?.dismiss()
            })
        }

        val width = minOf(dp(168), context.resources.displayMetrics.widthPixels - dp(32))
        val popup = PopupWindow(
            container,
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            elevation = 10 * density
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        }
        popupWindow = popup

        val location = IntArray(2)
        anchor.getLocationOnScreen(location)
        val screenWidth = context.resources.displayMetrics.widthPixels
        val screenHeight = context.resources.displayMetrics.heightPixels
        container.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val popupHeight = container.measuredHeight
        val anchorCenterX = location[0] + anchor.width / 2
        val x = (anchorCenterX - width / 2).coerceIn(dp(12), screenWidth - width - dp(12))
        val yBelow = location[1] + anchor.height + dp(4)
        val y = if (yBelow + popupHeight < screenHeight - dp(24)) {
            yBelow
        } else {
            (location[1] - popupHeight - dp(4)).coerceAtLeast(dp(24))
        }
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    private fun createRow(title: String, onClick: () -> Unit): View {
        val text = TextView(context).apply {
            text = title
            textSize = 16f
            setTextColor(Color.rgb(51, 51, 51))
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(20), dp(14), dp(20), dp(14))
            background = RippleDrawable(
                ColorStateList.valueOf(Color.rgb(230, 230, 230)),
                ColorDrawable(Color.TRANSPARENT),
                null,
            )
            setOnClickListener { onClick() }
        }
        text.layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        return text
    }

    private fun dp(value: Int) = (value * density).toInt()

    companion object {
        private var popupWindow: PopupWindow? = null

        fun dismiss() {
            popupWindow?.dismiss()
            popupWindow = null
        }
    }
}
