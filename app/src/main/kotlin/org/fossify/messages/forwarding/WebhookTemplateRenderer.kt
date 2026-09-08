package org.fossify.messages.forwarding

/** Replaces template variables once; inserted message text is never interpreted as a template. */
internal object WebhookTemplateRenderer {
    private val placeholder = Regex("\\[(title|msg|from|time|sim)\\]")

    fun render(template: String, encodedValues: Map<String, String>): String =
        placeholder.replace(template) { match ->
            encodedValues[match.groupValues[1]] ?: match.value
        }
}
