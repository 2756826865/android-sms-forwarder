package org.fossify.messages.recovery

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.fossify.messages.models.RecoveryTriggerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryWorkerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testStartupRecoveryScanRunsCleanly() = runBlocking {
        val summary = RecoveryEngine.runRecoveryScan(context, triggerSource = RecoveryTriggerSource.STARTUP)
        assertNotNull(summary)
        assertEquals(RecoveryTriggerSource.STARTUP, summary.triggerSource)
        assertTrue(summary.scanTime > 0)
    }

    @Test
    fun testPeriodicRecoveryWorkerExecutesSuccessfully() = runBlocking {
        val worker = TestListenableWorkerBuilder<RecoveryWorker>(context).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun testMultipleConcurrentRecoveryScansDoNotConflict() = runBlocking {
        val scan1 = async { RecoveryEngine.runRecoveryScan(context, triggerSource = RecoveryTriggerSource.STARTUP) }
        val scan2 = async { RecoveryEngine.runRecoveryScan(context, triggerSource = RecoveryTriggerSource.PERIODIC_WORKER) }
        val scan3 = async { RecoveryEngine.runRecoveryScan(context, triggerSource = RecoveryTriggerSource.MANUAL) }

        val results = awaitAll(scan1, scan2, scan3)
        assertEquals(3, results.size)
        results.forEach { summary ->
            assertNotNull(summary)
            assertTrue(summary.scanTime > 0)
        }
    }
}
