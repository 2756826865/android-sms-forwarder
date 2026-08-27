package org.fossify.messages.observability

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.observability.bundle.DiagnosticBundleGenerator
import org.fossify.messages.observability.crash.CrashDiagnosticsHandler
import org.fossify.messages.observability.log.LogLevel
import org.fossify.messages.observability.log.RingBufferLogManager
import org.fossify.messages.observability.perf.PerformanceTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObservabilitySystemTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        RingBufferLogManager.clear()
        PerformanceTracker.clear()
    }

    @Test
    fun testRingBufferLogRecordingAndMasking() {
        RingBufferLogManager.i("SMS_TEST", "Received SMS from 13800138000 with code 888999")
        val logs = RingBufferLogManager.getRecentLogs(10)

        assertEquals(1, logs.size)
        val entry = logs.first()
        assertEquals(LogLevel.INFO, entry.level)
        assertEquals("SMS_TEST", entry.tag)
        // Check phone number masked
        assertTrue(entry.message.contains("138****8000"))
        // Check code masked
        assertTrue(entry.message.contains("******"))
        assertFalse(entry.message.contains("13800138000"))
        assertFalse(entry.message.contains("888999"))
    }

    @Test
    fun testRingBufferCapacityEviction() {
        for (i in 1..1050) {
            RingBufferLogManager.d("PERF_TEST", "Log message batch #$i")
        }

        assertEquals(1000, RingBufferLogManager.size())
        val recent = RingBufferLogManager.getRecentLogs(1)
        assertTrue(recent.first().message.contains("1050"))
    }

    @Test
    fun testPerformanceTrackerRecordingAndSummaries() {
        PerformanceTracker.recordDuration("test_latency", 100L)
        PerformanceTracker.recordDuration("test_latency", 200L)
        PerformanceTracker.recordDuration("test_latency", 300L)

        val summary = PerformanceTracker.getMetricSummary("test_latency")
        assertNotNull(summary)
        assertEquals(3L, summary?.count)
        assertEquals(200.0, summary?.avgDurationMs ?: 0.0, 0.01)
        assertEquals(100L, summary?.minDurationMs)
        assertEquals(300L, summary?.maxDurationMs)
    }

    @Test
    fun testPerformanceTrackerTraceBlock() {
        val result = PerformanceTracker.trace("test_block") {
            Thread.sleep(20)
            42
        }

        assertEquals(42, result)
        val summary = PerformanceTracker.getMetricSummary("test_block")
        assertNotNull(summary)
        assertTrue((summary?.minDurationMs ?: 0L) >= 15L)
    }

    @Test
    fun testDiagnosticBundleGenerationAndEncryption() = runBlocking {
        RingBufferLogManager.i("DIAG_TAG", "Pre-export log message")
        PerformanceTracker.recordDuration("sample_metric", 50L)

        val bundle = DiagnosticBundleGenerator.generateBundle(context, encryptWithKeyStore = true)

        assertNotNull(bundle)
        assertTrue(bundle.isEncrypted)
        assertTrue(bundle.bundleContent.isNotEmpty())
        assertTrue(bundle.checksumSha256.isNotEmpty())
        assertTrue(bundle.exportTimestamp > 0)
    }

    @Test
    fun testCrashSnapshotStorageAndRetrieval() {
        CrashDiagnosticsHandler.clearCrashSnapshot(context)
        val initial = CrashDiagnosticsHandler.getLastCrashSnapshot(context)
        assertEquals(null, initial)
    }
}
