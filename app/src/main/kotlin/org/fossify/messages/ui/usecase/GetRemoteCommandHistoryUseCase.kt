package org.fossify.messages.ui.usecase

import org.fossify.messages.models.RemoteCommandExecutionEntity
import org.fossify.messages.ui.repository.DashboardDataRepository

class GetRemoteCommandHistoryUseCase(private val repository: DashboardDataRepository) {
    suspend operator fun invoke(limit: Int = 50): List<RemoteCommandExecutionEntity> {
        return repository.getRecentRemoteCommands(limit)
    }
}
