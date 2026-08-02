package org.fossify.messages.extensions

import android.graphics.Color
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fossify.messages.R

fun AlertDialog.applySmsDialogColors(): AlertDialog {
    if (!isShowing) show()

    findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
        setTextColor(Color.rgb(17, 17, 17))
        alpha = 1f
    }

    findViewById<TextView>(android.R.id.message)?.apply {
        setTextColor(Color.rgb(35, 35, 35))
        alpha = 1f
    }

    getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
        setTextColor(context.getColor(R.color.miui_fab_green))
        alpha = 1f
    }
    getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
        setTextColor(Color.rgb(85, 85, 85))
        alpha = 1f
    }
    getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
        setTextColor(Color.rgb(85, 85, 85))
        alpha = 1f
    }
    return this
}

fun AlertDialog.showSmsStyled(): AlertDialog {
    show()
    return applySmsDialogColors()
}
