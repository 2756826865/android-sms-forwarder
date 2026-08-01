package org.fossify.messages.helpers

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

fun formatConversationDate(epochSeconds: Int): String {
    val locale = Locale.getDefault()
    val date = Date(epochSeconds * 1000L)
    if (locale.language != Locale.CHINESE.language) {
        return SimpleDateFormat("MMM d, yyyy", locale).format(date)
    }

    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { time = date }
    val sameDay = now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> SimpleDateFormat("HH:mm", locale).format(date)
        now.get(Calendar.YEAR) == target.get(Calendar.YEAR) ->
            SimpleDateFormat("M月d日", locale).format(date)
        else -> SimpleDateFormat("yyyy年M月d日", locale).format(date)
    }
}
