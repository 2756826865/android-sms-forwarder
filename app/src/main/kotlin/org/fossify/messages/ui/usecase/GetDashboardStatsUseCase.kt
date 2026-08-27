package org.fossify.messages.ui.usecase

import org.fossify.messages.ui.dashboard.model.DashboardStats
import org.fossify.messages.ui.repository.DashboardDataRepository

class GetDashboardStatsUseCase(private val repository: DashboardDataRepository) {
    suspend operator fun invoke(): DashboardStats {
        return repository.getDashboardStats()
    }
}
