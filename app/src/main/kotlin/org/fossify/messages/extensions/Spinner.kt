package org.fossify.messages.extensions

import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.annotation.ArrayRes
import org.fossify.messages.R

fun Spinner.bindMiuiOptions(@ArrayRes optionsRes: Int) {
    adapter = ArrayAdapter.createFromResource(
        context,
        optionsRes,
        R.layout.item_email_security_spinner,
    ).apply {
        setDropDownViewResource(R.layout.item_email_security_spinner_dropdown)
    }
}
