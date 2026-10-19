package org.fossify.messages.helpers

/** Canonical-address IDs are provider keys, not telephone numbers or contact IDs. */
internal data class ThreadRecipient(val addressId: String?, val number: String) {
    // SimpleContact uses Int IDs. Never truncate a larger provider key into one.
    val contactId: Int? get() = addressId?.toIntOrNull()
}

/** Preserve opaque decimal provider keys, including keys larger than Int or Long. */
internal fun parseThreadRecipientIds(rawIds: String?): List<String> =
    rawIds.orEmpty().trim().split(Regex("\\s+")).filter(String::isNotEmpty).distinct()

internal fun resolveThreadRecipients(
    ids: List<String>,
    lookup: (String) -> String?,
    fallback: () -> List<String>,
): List<ThreadRecipient> {
    val resolved = ids.mapNotNull { id ->
        if (id.isEmpty() || !id.all { it in '0'..'9' }) return@mapNotNull null
        val number = lookup(id)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        ThreadRecipient(id, number)
    }
    if (resolved.isNotEmpty() && resolved.size == ids.size) return resolved

    // A single SMS address cannot reconstruct an incomplete group. Let callers
    // retain existing participants instead of replacing them with a partial list.
    if (ids.size > 1) return emptyList()
    return fallback().filter(String::isNotBlank).distinct().map { ThreadRecipient(null, it) }
}
