package org.fossify.messages.security.crypto

import java.util.concurrent.ConcurrentHashMap

/** 单条凭据的加解密失败事实。 */
data class CredentialFailure(
    val key: String,
    val reason: String,
    val timestamp: Long
)

/**
 * 凭据加解密健康状态追踪器
 *
 * 背景（P0-3）：当 AndroidKeyStore 不可用（密钥丢失、备份还原冲掉、设备策略重置、ROM 兼容问题）时，
 * 加解密会静默失败。历史上 `MultiForwardConfig.getSecret` 在解密失败时把**密文当明文返回**，
 * `saveSecret` 在加密失败时**写入空串**，两者都会造成不可逆的配置破坏。
 *
 * 本追踪器的作用是把这类失败显式化，供上层（联动通道同步、UI 提示、诊断包）查询并主动跳过写盘，
 * 而不是带着不可信的凭据继续跑完整条业务链。
 *
 * 设计约束：
 * 1. 线程安全 + 有界（参照 [org.fossify.messages.observability.perf.PerformanceTracker]），
 *    绝不因异常 key 增多而泄漏内存；
 * 2. 只记录 key 名与原因，严禁记录任何明文或密文内容；
 * 3. 不依赖 Android 框架，保证单元测试可直接使用。
 */
object CredentialHealth {

    /** 失败原因：解密失败（读路径）。 */
    const val REASON_DECRYPT = "decrypt_failed"

    /** 失败原因：加密失败（写路径）。 */
    const val REASON_ENCRYPT = "encrypt_failed"

    /** 最多跟踪的异常 key 数量，超出后丢弃最旧的记录。 */
    private const val MAX_TRACKED_KEYS = 200

    private val failures = ConcurrentHashMap<String, Failure>()

    private data class Failure(val reason: String, val timestamp: Long)

    /** 记录一次解密失败。 */
    fun markDecryptFailed(key: String) {
        mark(key, REASON_DECRYPT)
    }

    /** 记录一次加密失败。 */
    fun markEncryptFailed(key: String) {
        mark(key, REASON_ENCRYPT)
    }

    /** 该 key 已恢复正常读写，清除其失败记录。 */
    fun clear(key: String) {
        if (key.isBlank()) return
        failures.remove(key)
    }

    /** 清空全部失败记录（例如用户重新配置凭据后由设置页调用）。 */
    fun clearAll() {
        failures.clear()
    }

    /**
     * 是否存在任何凭据加解密失败。
     *
     * 为 true 时说明 Keystore 不可信，任何"基于当前凭据值的写盘"都可能把密文或空串固化到磁盘，
     * 调用方必须跳过写盘。
     */
    fun hasFailures(): Boolean = failures.isNotEmpty()

    /** 失败记录的 key 集合快照。 */
    fun failedKeys(): Set<String> = failures.keys.toSet()

    /** 按时间倒序返回失败快照，供诊断包与 UI 展示。 */
    fun snapshot(): List<CredentialFailure> = failures.entries
        .map { (key, failure) -> CredentialFailure(key, failure.reason, failure.timestamp) }
        .sortedByDescending { it.timestamp }

    private fun mark(key: String, reason: String) {
        if (key.isBlank()) return
        failures[key] = Failure(reason, System.currentTimeMillis())
        trimToLimit()
    }

    private fun trimToLimit() {
        while (failures.size > MAX_TRACKED_KEYS) {
            val oldestKey = failures.entries.minByOrNull { it.value.timestamp }?.key ?: return
            failures.remove(oldestKey)
        }
    }
}
