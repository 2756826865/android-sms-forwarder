package org.fossify.messages.helpers

/** Zero-width characters carry no visible content anywhere in a string. */
private val ZERO_WIDTH_CHARS_REGEX = Regex("[\\u200B-\\u200D\\u2060\\uFEFF]")

/**
 * Removes zero-width characters and trims edge whitespace, including NBSP and
 * full-width spaces. Interior NBSP / full-width spaces are preserved so that
 * the forwarded SMS body is not altered.
 */
fun String.stripInvisibleAndTrim(): String =
    replace(ZERO_WIDTH_CHARS_REGEX, "")
        .trim { it.isWhitespace() || it == '\u00A0' || it == '\u3000' }

/** Empty style fields must not hide the actual notification body. */
fun extractNotificationText(
    bigText: CharSequence?, text: CharSequence?, subText: CharSequence?,
    lines: List<CharSequence> = emptyList(), messages: List<CharSequence> = emptyList()
): String = sequenceOf(
    bigText?.toString().orEmpty(),
    text?.toString().orEmpty(),
    lines.joinToString("\n"),
    messages.joinToString("\n"),
    subText?.toString().orEmpty()
).map(String::stripInvisibleAndTrim).firstOrNull(String::isNotBlank).orEmpty()
