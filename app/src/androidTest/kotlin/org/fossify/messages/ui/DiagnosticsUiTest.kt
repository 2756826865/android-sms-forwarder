package org.fossify.messages.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.models.RecoveryAction
import org.fossify.messages.models.RecoveryObjectType
import org.fossify.messages.models.RecoveryRecordEntity
import org.fossify.messages.models.RecoveryTriggerSource
import org.fossify.messages.ui.common.UiState
import org.fossify.messages.ui.diagnostics.DiagnosticsViewModel
import org.fossify.messages.ui.repository.DashboardDataRepository
import org.fossify.messages.ui.usecase.GetRecoveryRecordsUseCase
import org.fossify.messages.ui.usecase.RunManualRecoveryUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DiagnosticsUiTest {

    private lateinit var context: Context
    private lateinit var repository: DashboardDataRepository
    private lateinit var getRecoveryRecordsUseCase: GetRecoveryRecordsUseCase
    private lateinit var runManualRecoveryUseCase: RunManualRecoveryUseCase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        repository = DashboardDataRepository(context)
        getRecoveryRecordsUseCase = GetRecoveryRecordsUseCase(repository)
        runManualRecoveryUseCase = RunManualRecoveryUseCase(context)
    }

    @Test
    fun testDiagnosticsLoadsRecoveryRecordsAndOemProfile() = runBlocking {
        val recId = "rec-diag-test-" + UUID.randomUUID()
        val now = System.currentTimeMillis()

        val record = RecoveryRecordEntity(
            recordId = recId,
            scanTime = now,
            triggerSource = RecoveryTriggerSource.MANUAL,
            objectType = RecoveryObjectType.SMS_OPERATION,
            objectId = "op-999",
            initialStatus = "SUBMITTED",
            recoveredStatus = "UNKNOWN_AFTER_SUBMIT",
            actionTaken = RecoveryAction.MARK_UNKNOWN,
            detailMessage = "Diag test mark unknown",
            createdAt = now
        )
        context.getMessagesDB().RecoveryRecordDao().insert(record)

        val viewModel = DiagnosticsViewModel(
            context = context,
            getRecoveryRecordsUseCase = getRecoveryRecordsUseCase,
            runManualRecoveryUseCase = runManualRecoveryUseCase
        )
        viewModel.loadDiagnostics()

        // Wait for coroutine
        Thread.sleep(150)

        val state = (viewModel.uiState.value as? UiState.Success)?.data
        assertNotNull(state)
        assertNotNull(state?.deviceProfile)
        assertTrue((state?.recoveryRecords?.size ?: 0) >= 1)
        assertTrue(state?.brandTips?.isNotEmpty() == true)
    }

    @Test
    fun testManualScanInvocationTriggersRecoveryEngineViaUseCase() = runBlocking {
        val summary = runManualRecoveryUseCase()
        assertNotNull(summary)
        assertEquals(RecoveryTriggerSource.MANUAL, summary.triggerSource)
        assertTrue(summary.scanTime > 0)
    }
}
