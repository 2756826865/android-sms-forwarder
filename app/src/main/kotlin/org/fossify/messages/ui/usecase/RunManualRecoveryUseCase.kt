package org.fossify.messages.ui.usecase

import android.content.Context
import org.fossify.messages.models.RecoverySummary
import org.fossify.messages.models.RecoveryTriggerSource
import org.fossify.messages.recovery.RecoveryEngine

/**
 * 手动触发自愈诊断扫描用例
 */
class RunManualRecoveryUseCase(private val context: Context) {
    suspend operator fun invoke(): RecoverySummary {
        return RecoveryEngine.runRecoveryScan(context, RecoveryTriggerSource.MANUAL)
    }
}
