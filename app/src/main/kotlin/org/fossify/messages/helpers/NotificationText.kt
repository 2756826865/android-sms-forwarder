package org.fossify.messages.helpers

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
).map(String::trim).firstOrNull(String::isNotBlank).orEmpty()
