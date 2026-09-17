package org.fossify.messages.forwarding

/** Replaces template variables once; inserted message text is never interpreted as a template. */
internal object WebhookTemplateRenderer {
    private val placeholder = Regex("(?i)(\\{\\{?|\\[\\[?)(title|msg|content|from|sender|time|sim|sim_slot|receiver)(\\}\\}?|\\]\\]?)")

    fun render(template: String, encodedValues: Map<String, String>): String =
        placeholder.replace(template) { match ->
            val key = match.groupValues[2].lowercase()
            val normalizedKey = when (key) {
                "content" -> "msg"
                "sender" -> "from"
                else -> key
            }
            encodedValues[normalizedKey] ?: match.value
        }
}
