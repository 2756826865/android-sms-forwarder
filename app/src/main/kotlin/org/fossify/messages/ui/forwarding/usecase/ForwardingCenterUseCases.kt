package org.fossify.messages.ui.forwarding.usecase

import org.fossify.messages.ui.forwarding.model.ChannelHealth
import org.fossify.messages.ui.forwarding.model.ForwardingCenterState
import org.fossify.messages.ui.forwarding.repository.ForwardingCenterRepository

class GetForwardingCenterStateUseCase(private val repository: ForwardingCenterRepository) {
    suspend operator fun invoke(): ForwardingCenterState {
        return repository.getForwardingCenterState()
    }
}

class GetChannelHealthUseCase(private val repository: ForwardingCenterRepository) {
    suspend operator fun invoke(): List<ChannelHealth> {
        return repository.getForwardingCenterState().channelHealthList
    }
}
