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
    private val count = java.util.concurrent.atomic.AtomicInteger(0)

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
            throwableDetails = throwable?.let { "${it.javaClass.simpleName}: ${maskSensitiveContent(it.message.orEmpty())}" }
        )

        buffer.add(entry)
        while (count.incrementAndGet() > MAX_ENTRIES) {
            if (buffer.poll() != null) {
                count.decrementAndGet()
            }
            break
        }
    }

    fun getRecentLogs(limit: Int = 100): List<LogEntry> {
        return buffer.toList().takeLast(limit).reversed()
    }

    fun getLogsByLevel(minLevel: LogLevel, limit: Int = 100): List<LogEntry> {
        return buffer.filter { it.level.ordinal >= minLevel.ordinal }.takeLast(limit).reversed()
    }

    fun clear() {
        while (buffer.poll() != null) {
            count.updateAndGet { if (it > 0) it - 1 else 0 }
        }
        count.set(0)
    }

    fun size(): Int = buffer.size

    fun maskSensitiveContent(content: String): String {
        if (content.isEmpty()) return content
        // URL Token / Secret / Telegram Bot Token 脱敏
        var masked = content
            .replace(Regex("""(?i)(token|secret|password|access_token|key|sign)=([^&\s"']+)""")) {
                "${it.groupValues[1]}=[REDACTED]"
            }
            .replace(Regex("""bot\d{5,15}:[A-Za-z0-9_-]{20,}"""), "bot[REDACTED]")
        // 手机号码脱敏 (支持 +86/86 前缀，保留前3后4)
        masked = masked.replace(Regex("""(?<!\d)((?:\+?86[-\s]?)?1[3-9]\d)(\d{4})(\d{4})(?!\d)""")) {
            "${it.groupValues[1]}****${it.groupValues[3]}"
        }
        // 验证码语境下 4-8 位验证码脱敏 + 通用独立 6 位数字脱敏
        masked = masked.replace(Regex("""(?i)(验证码|校验码|动态码|code)[:：\s]*(\d{4,8})(?!\d)""")) {
            "${it.groupValues[1]}: ******"
        }
        return masked.replace(Regex("""(?<!\d|-|:)(\d{6})(?!\d|-|:)""")) { "******" }
    }
}
