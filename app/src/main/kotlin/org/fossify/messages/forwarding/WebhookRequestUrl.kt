package org.fossify.messages.forwarding

internal object WebhookRequestUrl {
    /** query is already encoded by the template renderer; do not encode it again. */
    fun appendQuery(url: String, query: String): String {
        val parameters = query.removePrefix("?").removePrefix("&")
        if (parameters.isBlank()) return url
        val fragmentIndex = url.indexOf('#')
        val base = if (fragmentIndex >= 0) url.substring(0, fragmentIndex) else url
        val fragment = if (fragmentIndex >= 0) url.substring(fragmentIndex) else ""
        val separator = when {
            !base.contains('?') -> "?"
            base.endsWith('?') || base.endsWith('&') -> ""
            else -> "&"
        }
        return "$base$separator$parameters$fragment"
    }
}
