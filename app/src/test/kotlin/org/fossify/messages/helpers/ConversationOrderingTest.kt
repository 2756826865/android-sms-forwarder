package org.fossify.messages.helpers

import org.fossify.messages.models.Conversation
import org.fossify.messages.models.ConversationWithSnippetOverride
import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationOrderingTest {
    private fun conversation(id: Long, date: Int, snippet: String = "old") = Conversation(
        threadId = id, snippet = snippet, date = date, read = true, title = "Contact $id",
        photoUri = "", isGroupConversation = false, phoneNumber = "$id"
    )

    @Test fun latestStoredSmsRepairsOldConversationDate() {
        val repaired = ConversationWithSnippetOverride("new SMS", 300, conversation(1, 100)).toConversation()
        assertEquals(listOf(1L, 2L), sortConversations(listOf(conversation(2, 200), repaired), emptySet()).map { it.threadId })
        assertEquals("new SMS", repaired.snippet)
    }

    @Test fun unsortedStartupCacheUsesLatestFirst() {
        assertEquals(listOf(3L, 2L, 1L), sortConversations(listOf(conversation(1, 100), conversation(3, 300), conversation(2, 200)), emptySet()).map { it.threadId })
    }

    @Test fun newerLocalMessageSurvivesStaleProviderSnapshot() {
        val provider = conversation(1, 100).copy(read = false, title = "Provider name")
        val result = mergeConversationSources(listOf(provider), listOf(conversation(1, 300, "new SMS"))).single()
        assertEquals(300, result.date)
        assertEquals("new SMS", result.snippet)
        assertEquals("Provider name", result.title)
        assertEquals(false, result.read)
    }

    @Test fun pinnedConversationsStayFirstWithDeterministicTies() {
        assertEquals(listOf(1L, 3L, 2L), sortConversations(listOf(conversation(2, 300), conversation(1, 100), conversation(3, 300)), setOf("1")).map { it.threadId })
    }

    @Test fun futureScheduledCacheDoesNotOverrideRealProviderMessage() {
        val result = mergeConversationSources(listOf(conversation(1, 300)), listOf(conversation(1, 900).copy(isScheduled = true))).single()
        assertEquals(300, result.date)
    }

    @Test fun draftDateAndCacheOnlyConversationAreRetained() {
        assertEquals(400, ConversationWithSnippetOverride("new SMS", 300, conversation(1, 400)).toConversation().date)
        assertEquals(setOf(1L, 2L), mergeConversationSources(listOf(conversation(1, 100)), listOf(conversation(2, 200))).map { it.threadId }.toSet())
    }
}
