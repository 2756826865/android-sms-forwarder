package org.fossify.messages.ui.messages.usecase

import org.fossify.messages.ui.messages.model.MessageHistoryItem
import org.fossify.messages.ui.messages.repository.MessageCenterRepository

class GetMessageHistoryUseCase(private val repository: MessageCenterRepository) {
    suspend operator fun invoke(limit: Int = 50): List<MessageHistoryItem> {
        return repository.getMessageHistory(limit)
    }
}
