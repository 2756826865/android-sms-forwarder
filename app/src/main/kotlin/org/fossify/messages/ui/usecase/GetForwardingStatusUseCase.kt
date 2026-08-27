package org.fossify.messages.ui.usecase

import org.fossify.messages.models.ForwardingShadowDelivery
import org.fossify.messages.ui.repository.DashboardDataRepository

class GetForwardingStatusUseCase(private val repository: DashboardDataRepository) {
    suspend operator fun invoke(limit: Int = 50): List<ForwardingShadowDelivery> {
        return repository.getRecentDeliveries(limit)
    }
}
