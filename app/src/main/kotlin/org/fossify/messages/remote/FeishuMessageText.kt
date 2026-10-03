package org.fossify.messages.remote

/** Strip only metadata-backed mentions at the start, preserving the SMS body. */
internal object FeishuMessageText {
    fun stripLeadingMentions(text: String, mentionKeys: Iterable<String>): String {
        val tokens = mentionKeys.filter(String::isNotBlank)
            .map { if (it.startsWith('@')) it else "@$it" }
            .distinct()
            .sortedByDescending(String::length)
        var remaining = text.trim()
        while (true) {
            val token = tokens.firstOrNull { key ->
                remaining.startsWith(key) && remaining.getOrNull(key.length).let { next ->
                    next == null || !(next.isLetterOrDigit() || next == '_')
                }
            } ?: return remaining
            remaining = remaining.removePrefix(token).trimStart()
        }
    }
}
