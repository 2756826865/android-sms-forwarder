package org.fossify.messages.extensions

import android.graphics.Color
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import org.fossify.messages.R

import androidx.core.content.ContextCompat

fun AlertDialog.applySmsDialogColors(): AlertDialog {
    if (!isShowing) show()

    val primaryText = ContextCompat.getColor(context, R.color.miui_primary_text)
    val secondaryText = ContextCompat.getColor(context, R.color.miui_secondary_text)
    val actionGreen = ContextCompat.getColor(context, R.color.miui_fab_green)

    findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.apply {
        setTextColor(primaryText)
        alpha = 1f
    }

    findViewById<TextView>(android.R.id.message)?.apply {
        setTextColor(primaryText)
        alpha = 1f
    }

    getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
        setTextColor(actionGreen)
        alpha = 1f
    }
    getButton(AlertDialog.BUTTON_NEGATIVE)?.apply {
        setTextColor(secondaryText)
        alpha = 1f
    }
    getButton(AlertDialog.BUTTON_NEUTRAL)?.apply {
        setTextColor(secondaryText)
        alpha = 1f
    }
    return this
}

fun AlertDialog.showSmsStyled(): AlertDialog {
    show()
    return applySmsDialogColors()
}
