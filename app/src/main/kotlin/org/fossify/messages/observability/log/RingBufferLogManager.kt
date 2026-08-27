package org.fossify.messages.observability.log

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR,
    CRITICAL
}

data class LogEntry(
    val logId: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
    val timestamp: Long = System.currentTimeMillis(),
    val threadName: String = Thread.currentThread().name,
    val throwableDetails: String? = null
)

/**
 * 内存环形日志缓冲区 (Ring Buffer)
 *
 * 核心特性：
 * 1. 固定容量 (默认 1000 条)，先进先出自动淘汰；
 * 2. 纯内存并发安全无锁队列，零磁盘 I/O 损耗与电量消耗；
 * 3. 敏感数据入库前自动脱敏。
 */
object RingBufferLogManager {

    private const val MAX_ENTRIES = 1000
    private val buffer = ConcurrentLinkedQueue<LogEntry>()

    fun d(tag: String, message: String) = log(LogLevel.DEBUG, tag, message)
    fun i(tag: String, message: String) = log(LogLevel.INFO, tag, message)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.ERROR, tag, message, throwable)
    fun critical(tag: String, message: String, throwable: Throwable? = null) = log(LogLevel.CRITICAL, tag, message, throwable)

    private fun log(level: LogLevel, tag: String, message: String, throwable: Throwable? = null) {
        val entry = LogEntry(
            logId = "log-" + UUID.randomUUID().toString().take(8),
            level = level,
            tag = tag,
            message = maskSensitiveContent(message),
            timestamp = System.currentTimeMillis(),
            threadName = Thread.currentThread().name,
            throwableDetails = throwable?.let { "${it.javaClass.simpleName}: ${it.message}" }
        )

        buffer.add(entry)
        while (buffer.size > MAX_ENTRIES) {
            buffer.poll()
        }
    }

    fun getRecentLogs(limit: Int = 100): List<LogEntry> {
        return buffer.toList().takeLast(limit).reversed()
    }

    fun getLogsByLevel(minLevel: LogLevel, limit: Int = 100): List<LogEntry> {
        return buffer.filter { it.level.ordinal >= minLevel.ordinal }.takeLast(limit).reversed()
    }

    fun clear() {
        buffer.clear()
    }

    fun size(): Int = buffer.size

    private fun maskSensitiveContent(content: String): String {
        // 手机号码脱敏 (保留前3后4)
        val phoneMasked = content.replace(Regex("""(?<!\d)(1[3-9]\d)(\d{4})(\d{4})(?!\d)""")) {
            "${it.groupValues[1]}****${it.groupValues[3]}"
        }
        // 6位验证码脱敏
        return phoneMasked.replace(Regex("""(?<!\d)(\d{6})(?!\d)""")) { "******" }
    }
}
