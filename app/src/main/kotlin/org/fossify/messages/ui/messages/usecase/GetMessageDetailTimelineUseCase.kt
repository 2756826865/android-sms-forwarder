package org.fossify.messages.ui.messages.usecase

import org.fossify.messages.ui.messages.model.MessageDetailTimeline
import org.fossify.messages.ui.messages.repository.MessageCenterRepository

class GetMessageDetailTimelineUseCase(private val repository: MessageCenterRepository) {
    suspend operator fun invoke(operationId: String): MessageDetailTimeline? {
        return repository.getMessageDetailTimeline(operationId)
    }
}
