package org.fossify.messages.security.audit

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

enum class SecurityAuditEventType {
    SECRET_READ,
    SECRET_UPDATED,
    SECRET_DELETED,
    /** 凭据解密失败：Keystore 不可用或密钥丢失，此时绝不把密文当明文外泄。 */
    SECRET_DECRYPT_FAILED,
    /** 凭据加密失败：此时绝不写盘，保留旧值以免凭据被静默清空。 */
    SECRET_ENCRYPT_FAILED,
    /** 因凭据不可信而跳过了一次联动通道同步（避免覆写既有配置）。 */
    CREDENTIAL_SYNC_SKIPPED,
    REMOTE_ACCESS_GRANTED,
    REMOTE_ACCESS_DENIED,
    CONFIG_EXPORT
}

data class SecurityAuditEvent(
    val eventId: String,
    val eventType: SecurityAuditEventType,
    val targetKeyHash: String,
    val timestamp: Long = System.currentTimeMillis(),
    val details: String? = null
)

/**
 * 安全审计事件管理器
 * 严禁记录明文敏感信息，仅记录操作类型与 Key 的不可逆摘要
 */
object SecurityAuditManager {

    private const val MAX_AUDIT_LOGS = 200
    private val auditLogs = ConcurrentLinkedQueue<SecurityAuditEvent>()

    fun logEvent(eventType: SecurityAuditEventType, targetKey: String, details: String? = null) {
        val keyHash = hashKey(targetKey)
        val event = SecurityAuditEvent(
            eventId = "sec-" + UUID.randomUUID().toString().take(8),
            eventType = eventType,
            targetKeyHash = keyHash,
            timestamp = System.currentTimeMillis(),
            details = details
        )

        auditLogs.add(event)
        while (auditLogs.size > MAX_AUDIT_LOGS) {
            auditLogs.poll()
        }
    }

    fun getRecentAuditEvents(limit: Int = 50): List<SecurityAuditEvent> {
        return auditLogs.toList().takeLast(limit).reversed()
    }

    fun clearAuditLogs() {
        auditLogs.clear()
    }

    private fun hashKey(key: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            md.digest(key.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            "unknown_hash"
        }
    }
}
