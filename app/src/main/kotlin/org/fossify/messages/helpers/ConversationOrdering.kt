package org.fossify.messages.helpers

import org.fossify.messages.models.Conversation

fun sortConversations(conversations: List<Conversation>, pinned: Set<String>): List<Conversation> =
    conversations.sortedWith(
        compareByDescending<Conversation> { it.threadId.toString() in pinned }
            .thenByDescending { it.date }
            .thenByDescending { it.threadId }
    )

/** Provider metadata remains authoritative; newer stored messages repair a stale thread timestamp. */
fun mergeConversationSources(provider: List<Conversation>, cached: List<Conversation>): List<Conversation> {
    val cachedById = cached.associateBy { it.threadId }
    val merged = provider.associateBy { it.threadId }.toMutableMap()
    provider.forEach { conversation ->
        val local = cachedById[conversation.threadId]
        if (local != null && local.date > conversation.date && !local.isScheduled) {
            merged[conversation.threadId] = conversation.copy(date = local.date, snippet = local.snippet)
        }
    }
    cached.forEach { merged.putIfAbsent(it.threadId, it) }
    return merged.values.toList()
}
