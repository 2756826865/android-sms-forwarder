package org.fossify.messages.ui.remote.usecase

import org.fossify.messages.ui.remote.model.RemoteAuthorizationState
import org.fossify.messages.ui.remote.model.RemoteCommandItem
import org.fossify.messages.ui.remote.model.RemoteControlCenterState
import org.fossify.messages.ui.remote.repository.RemoteControlRepository

class RemoteCommandHistoryUseCase(private val repository: RemoteControlRepository) {
    suspend operator fun invoke(limit: Int = 50): List<RemoteCommandItem> {
        return repository.getRecentCommands(limit)
    }

    suspend fun getFullState(limit: Int = 50): RemoteControlCenterState {
        return repository.getRemoteControlCenterState(limit)
    }
}

class RemoteAuthorizationUseCase(private val repository: RemoteControlRepository) {
    suspend operator fun invoke(): RemoteAuthorizationState {
        return repository.getRemoteControlCenterState().authSummary
    }
}
