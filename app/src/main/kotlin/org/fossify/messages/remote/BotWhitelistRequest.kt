package org.fossify.messages.remote

/** Informational request, deliberately independent of authorization and SMS execution. */
data class BotWhitelistRequest(
    val messageId: String,
    val kind: DingTalkWhitelistRequest.Kind,
    val platform: String,
    val userId: String,
    val userField: String,
    val groupId: String,
    val groupField: String,
    val isGroup: Boolean,
    val isMentioned: Boolean,
    val replyTarget: String,
) {
    fun replyText(): String = buildString {
        append("【白名单 ID 申请 · ").append(platform).append("】\n")
        if (userId.isBlank()) append("当前事件未提供用户 ID；无法使用昵称或手机号代替。请检查机器人发布状态。\n")
        else append("用户白名单 ID（").append(userField).append("）：\n").append(userId).append('\n')
        if (isGroup) {
            if (groupId.isBlank()) append("当前事件未提供群会话 ID。\n")
            else append("群组白名单 ID（").append(groupField).append("）：\n").append(groupId).append('\n')
        } else if (kind == DingTalkWhitelistRequest.Kind.GROUP) {
            append("当前是私聊，没有群组 ID。请在目标群 @机器人 发送“申请群白名单”。\n")
        }
        append("仅展示本次消息的 ID，尚未加入白名单，也未发送短信。\n")
        append("请由设备管理员在 APK「远程发送 → 编辑此来源」中填写对应白名单，每行一个完整 ID。\n")
        append("群组白名单授权的是会话，不代表确认申请人是群主。")
    }
}

class BotWhitelistReplyLimiter {
    private val messages = LinkedHashMap<Pair<String, String>, Long>()
    private val senders = LinkedHashMap<Triple<String, String, String>, Long>()

    @Synchronized
    fun claim(sourceId: String, request: BotWhitelistRequest, now: Long = System.currentTimeMillis()): Boolean {
        if (sourceId.isBlank() || request.messageId.isBlank() || request.replyTarget.isBlank() ||
            (request.isGroup && !request.isMentioned)) return false
        messages.entries.removeAll { now - it.value !in 0L..300_000L }
        senders.entries.removeAll { now - it.value !in 0L..30_000L }
        val messageKey = sourceId to request.messageId
        if (messages.containsKey(messageKey)) return false
        val senderKey = Triple(sourceId, request.groupId, request.userId)
        if (senders.containsKey(senderKey)) return false
        messages[messageKey] = now
        senders[senderKey] = now
        while (messages.size > 200) messages.remove(messages.keys.first())
        while (senders.size > 200) senders.remove(senders.keys.first())
        return true
    }
}
