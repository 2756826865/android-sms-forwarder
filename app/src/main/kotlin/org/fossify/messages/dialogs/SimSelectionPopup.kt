package org.fossify.messages.dialogs

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.appcompat.content.res.AppCompatResources
import org.fossify.messages.R
import org.fossify.messages.models.SIMCard

class SimSelectionPopup(
    private val context: Context,
    private val cards: List<SIMCard>,
    private val selectedIndex: Int,
    private val onSelected: (Int) -> Unit,
) {
    private val density = context.resources.displayMetrics.density

    fun show(anchor: View) {
        if (cards.isEmpty()) return
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = AppCompatResources.getDrawable(context, R.drawable.sim_popup_background)
            clipToOutline = true
        }
        cards.forEachIndexed { index, card ->
            container.addView(createRow(card, index == selectedIndex) {
                onSelected(index)
                popupWindow?.dismiss()
            })
        }

        val width = minOf((340 * density).toInt(), context.resources.displayMetrics.widthPixels - (40 * density).toInt())
        popupWindow = PopupWindow(
            container,
            width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true,
        ).apply {
            isOutsideTouchable = true
            elevation = 14 * density
            setBackgroundDrawable(AppCompatResources.getDrawable(context, R.drawable.sim_popup_background))
            showAtLocation(anchor.rootView, Gravity.BOTTOM or Gravity.END, (20 * density).toInt(), (78 * density).toInt())
        }
    }

    private fun createRow(card: SIMCard, selected: Boolean, onClick: () -> Unit): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(if (selected) Color.rgb(235, 243, 255) else Color.WHITE)
            }
            setOnClickListener { onClick() }
        }
        row.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(82))

        val badge = TextView(context).apply {
            text = card.id.toString()
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setTextColor(if (selected) Color.WHITE else Color.BLACK)
            background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (selected) Color.rgb(52, 138, 244) else Color.rgb(235, 235, 235))
            }
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(42), dp(42)).apply { marginEnd = dp(18) })

        val labels = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        labels.addView(TextView(context).apply {
            text = card.label.ifBlank { context.getString(R.string.sim_card_number, card.id) }
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (selected) Color.rgb(52, 138, 244) else Color.rgb(17, 17, 17))
        })
        labels.addView(TextView(context).apply {
            text = card.phoneNumber.ifBlank { context.getString(R.string.sim_number_unavailable) }
            textSize = 15f
            setTextColor(if (selected) Color.rgb(52, 138, 244) else Color.rgb(100, 100, 100))
        })
        row.addView(labels, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val checkHolder = FrameLayout(context)
        if (selected) {
            checkHolder.addView(ImageView(context).apply {
                setImageResource(org.fossify.commons.R.drawable.ic_check_vector)
                setColorFilter(Color.rgb(52, 138, 244))
            }, FrameLayout.LayoutParams(dp(28), dp(28), Gravity.CENTER))
        }
        row.addView(checkHolder, LinearLayout.LayoutParams(dp(42), dp(42)))
        return row
    }

    private fun dp(value: Int) = (value * density).toInt()

    companion object {
        private var popupWindow: PopupWindow? = null
    }
}
