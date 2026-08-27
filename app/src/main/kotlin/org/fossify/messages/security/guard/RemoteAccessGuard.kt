package org.fossify.messages.security.guard

import android.content.Context
import org.fossify.messages.security.audit.SecurityAuditEventType
import org.fossify.messages.security.audit.SecurityAuditManager

/**
 * 远程控制中心与敏感配置访问守卫
 */
object RemoteAccessGuard {

    private const val SESSION_VALIDITY_MS = 5 * 60 * 1000L // 5 分钟安全免密会话
    private var lastAuthTimestamp = 0L

    fun isSessionValid(): Boolean {
        val now = System.currentTimeMillis()
        return (now - lastAuthTimestamp) in 0..SESSION_VALIDITY_MS
    }

    fun markAuthSuccess(authMethod: String = "LOCAL_PASSCODE") {
        lastAuthTimestamp = System.currentTimeMillis()
        SecurityAuditManager.logEvent(
            eventType = SecurityAuditEventType.REMOTE_ACCESS_GRANTED,
            targetKey = "RemoteControlAuth",
            details = "Auth via $authMethod"
        )
    }

    fun markAuthFailed(authMethod: String = "LOCAL_PASSCODE", reason: String = "Invalid Credentials") {
        SecurityAuditManager.logEvent(
            eventType = SecurityAuditEventType.REMOTE_ACCESS_DENIED,
            targetKey = "RemoteControlAuth",
            details = "Auth via $authMethod failed: $reason"
        )
    }

    fun invalidateSession() {
        lastAuthTimestamp = 0L
    }
}
