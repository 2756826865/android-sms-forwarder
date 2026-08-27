package org.fossify.messages.ui.usecase

import org.fossify.messages.models.RecoveryRecordEntity
import org.fossify.messages.ui.repository.DashboardDataRepository

class GetRecoveryRecordsUseCase(private val repository: DashboardDataRepository) {
    suspend operator fun invoke(limit: Int = 50): List<RecoveryRecordEntity> {
        return repository.getRecentRecoveryRecords(limit)
    }
}
