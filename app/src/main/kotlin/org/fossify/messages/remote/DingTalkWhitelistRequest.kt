package org.fossify.messages.remote

/** Identity discovery only: this path must never authorize a user or submit an SMS. */
data class DingTalkWhitelistRequest(
    val messageId: String,
    val kind: Kind,
    val senderStaffId: String,
    val senderId: String,
    val conversationId: String,
    val isGroup: Boolean,
    val isMentioned: Boolean,
    val sessionWebhook: String,
) {
    enum class Kind { USER, GROUP }

    val effectiveUserId: String get() = senderStaffId.ifBlank { senderId }

    fun asBotRequest() = BotWhitelistRequest(messageId, kind, "钉钉", effectiveUserId,
        if (senderStaffId.isNotBlank()) "senderStaffId" else "senderId", conversationId, "conversationId",
        isGroup, isMentioned, sessionWebhook)
    fun replyText(): String = asBotRequest().replyText()

    companion object {
        fun parseKind(content: String): Kind? {
            if (content.length > 512) return null
            // Some clients retain leading @mentions in text; never match a phrase inside ordinary prose.
            val command = content.trim().replace(Regex("^(?:@\\S+\\s+)+"), "").removePrefix("/")
            return when (command) {
                "申请用户白名单", "获取用户ID", "获取用户id", "获取白名单ID", "获取白名单id", "白名单" -> Kind.USER
                "申请群白名单", "申请群组白名单", "申请群主白名单", "获取群ID", "获取群id" -> Kind.GROUP
                else -> null
            }
        }
    }
}

/** Bounded in-memory protection for informational replies. Stable callback IDs suppress redelivery. */
class DingTalkWhitelistReplyLimiter {
    private val limiter = BotWhitelistReplyLimiter()
    fun claim(sourceId: String, request: DingTalkWhitelistRequest, now: Long = System.currentTimeMillis()) =
        limiter.claim(sourceId, request.asBotRequest(), now)
}
