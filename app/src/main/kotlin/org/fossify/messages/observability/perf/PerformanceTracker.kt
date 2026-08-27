package org.fossify.messages.observability.perf

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

data class PerformanceMetricSummary(
    val metricName: String,
    val count: Long,
    val avgDurationMs: Double,
    val minDurationMs: Long,
    val maxDurationMs: Long,
    val lastRecordedMs: Long
)

/**
 * 高性能系统性能与延迟指标追踪器
 */
object PerformanceTracker {

    const val METRIC_APP_STARTUP = "app_startup"
    const val METRIC_RECOVERY_SCAN = "recovery_scan"
    const val METRIC_OUTBOX_DISPATCH = "outbox_dispatch"
    const val METRIC_RULE_EVALUATION = "rule_evaluation"
    const val METRIC_CHANNEL_FORWARD = "channel_forward"

    private const val MAX_SAMPLES_PER_METRIC = 200
    private val metricStore = ConcurrentHashMap<String, ConcurrentLinkedQueue<Long>>()

    fun recordDuration(metricName: String, durationMs: Long) {
        val queue = metricStore.getOrPut(metricName) { ConcurrentLinkedQueue() }
        queue.add(durationMs)
        while (queue.size > MAX_SAMPLES_PER_METRIC) {
            queue.poll()
        }
    }

    inline fun <T> trace(metricName: String, block: () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val duration = System.currentTimeMillis() - start
            recordDuration(metricName, duration)
        }
    }

    fun getMetricSummary(metricName: String): PerformanceMetricSummary? {
        val queue = metricStore[metricName] ?: return null
        val samples = queue.toList()
        if (samples.isEmpty()) return null

        val count = samples.size.toLong()
        val sum = samples.sum()
        val min = samples.minOrNull() ?: 0L
        val max = samples.maxOrNull() ?: 0L
        val last = samples.lastOrNull() ?: 0L

        return PerformanceMetricSummary(
            metricName = metricName,
            count = count,
            avgDurationMs = sum.toDouble() / count,
            minDurationMs = min,
            maxDurationMs = max,
            lastRecordedMs = last
        )
    }

    fun getAllSummaries(): Map<String, PerformanceMetricSummary> {
        val map = mutableMapOf<String, PerformanceMetricSummary>()
        metricStore.keys().toList().forEach { metricName ->
            getMetricSummary(metricName)?.let { map[metricName] = it }
        }
        return map
    }

    fun clear() {
        metricStore.clear()
    }
}
