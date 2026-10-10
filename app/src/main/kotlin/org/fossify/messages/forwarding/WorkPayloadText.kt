package org.fossify.messages.forwarding

/** Reserve room for WorkManager metadata; do not split supplementary Unicode characters. */
internal object WorkPayloadText {
    private const val SUFFIX = "…(内容过长已截断)"

    fun fitUtf8(text: String, maxBytes: Int): String {
        require(maxBytes >= SUFFIX.toByteArray(Charsets.UTF_8).size)
        if (text.toByteArray(Charsets.UTF_8).size <= maxBytes) return text
        val budget = maxBytes - SUFFIX.toByteArray(Charsets.UTF_8).size
        var end = 0
        var used = 0
        while (end < text.length) {
            val codePoint = Character.codePointAt(text, end)
            val width = Character.charCount(codePoint)
            val bytes = text.substring(end, end + width).toByteArray(Charsets.UTF_8).size
            if (used + bytes > budget) break
            used += bytes
            end += width
        }
        return text.substring(0, end) + SUFFIX
    }
}
