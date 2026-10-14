package org.fossify.messages.models

import androidx.room.ColumnInfo
import androidx.room.Embedded

data class ConversationWithSnippetOverride(
    @ColumnInfo(name = "new_snippet") val snippet: String?,
    @ColumnInfo(name = "new_date") val latestMessageDate: Int?,
    @Embedded val conversation: Conversation
) {
    fun toConversation() = conversation.copy(
        snippet = snippet ?: conversation.snippet,
        date = maxOf(conversation.date, latestMessageDate ?: conversation.date)
    )
}
