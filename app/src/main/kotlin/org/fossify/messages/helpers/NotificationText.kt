package org.fossify.messages.helpers

/** Zero-width and formatting control characters carry no visible content anywhere in a string. */
private val ZERO_WIDTH_CHARS_REGEX = Regex("[\\u200B-\\u200F\\u2060-\\u206F\\uFEFF]")

/**
 * Removes zero-width characters and trims edge whitespace, including NBSP,
 * full-width spaces, figure spaces, and narrow no-break spaces. Interior
 * spaces are preserved so that the forwarded SMS body is not altered.
 */
fun String.stripInvisibleAndTrim(): String =
    replace(ZERO_WIDTH_CHARS_REGEX, "")
        .trim {
            it.isWhitespace() || it == '\u00A0' || it == '\u3000' ||
                it == '\u202F' || it == '\u2007'
        }

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
