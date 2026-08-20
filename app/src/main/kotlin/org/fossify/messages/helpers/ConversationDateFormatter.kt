package org.fossify.messages.helpers

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private fun isSameCalendarDay(epochMillis: Long): Boolean {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return now.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
}

private fun isYesterday(epochMillis: Long): Boolean {
    val yesterday = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, -1)
    }
    val target = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return yesterday.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
        yesterday.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR)
}

private fun isSameCalendarYear(epochMillis: Long): Boolean {
    val now = Calendar.getInstance()
    val target = Calendar.getInstance().apply { timeInMillis = epochMillis }
    return now.get(Calendar.YEAR) == target.get(Calendar.YEAR)
}

/**
 * Shared timestamp rules for home list and in-thread separators:
 * - today: `20:00`
 * - yesterday: `昨天 20:00`
 * - this year: `8月20日 20:00`
 * - earlier years: `2025年8月8日 20:00`
 */
private fun formatMessageTimestamp(epochSeconds: Int): String {
    val locale = Locale.CHINA
    val date = Date(epochSeconds * 1000L)
    val time = SimpleDateFormat("HH:mm", locale).format(date)
    return when {
        isSameCalendarDay(date.time) -> time
        isYesterday(date.time) -> "昨天 $time"
        isSameCalendarYear(date.time) ->
            "${SimpleDateFormat("M月d日", locale).format(date)} $time"
        else ->
            "${SimpleDateFormat("yyyy年M月d日", locale).format(date)} $time"
    }
}

/** Home conversation list timestamps (position unchanged, right side of row). */
fun formatConversationDate(epochSeconds: Int): String = formatMessageTimestamp(epochSeconds)

/** In-thread message separator timestamps. */
fun formatThreadMessageDate(epochSeconds: Int): String = formatMessageTimestamp(epochSeconds)

fun isThreadMessageDateToday(epochSeconds: Int): Boolean =
    isSameCalendarDay(epochSeconds * 1000L)
