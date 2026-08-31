package org.fossify.messages.autoreply

data class AutoReplyRule(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val senderFilter: String = "",
    val includeKeywords: List<String> = emptyList(),
    val excludeKeywords: List<String> = emptyList(),
    val includeRegex: String = "",
    val replyContent: String = "",
    val simScope: String = SIM_SAME,
    val rateLimitMinutes: Int = 1440, // 0 = 不限制, 1440 = 24小时, 可自定义任意分钟数
    val delaySeconds: Int = 3,
    val notifyReceipt: Boolean = true,
) {
    fun formatCooldownLabel(): String = when (rateLimitMinutes) {
        0 -> "不限制 (每次均回复)"
        in 1..59 -> "${rateLimitMinutes} 分钟"
        in 60..1439 -> "${rateLimitMinutes / 60} 小时" + if (rateLimitMinutes % 60 > 0) "${rateLimitMinutes % 60}分" else ""
        else -> "${rateLimitMinutes / 1440} 天" + if ((rateLimitMinutes % 1440) / 60 > 0) "${(rateLimitMinutes % 1440) / 60}小时" else ""
    }

    companion object {
        const val SIM_SAME = "same_sim"
        const val SIM_ALL = "all"
        const val SIM_1 = "sim1"
        const val SIM_2 = "sim2"
    }
}
