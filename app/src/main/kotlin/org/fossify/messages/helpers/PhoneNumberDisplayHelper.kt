package org.fossify.messages.helpers

/**
 * 电话号码友好展示助手：
 * 针对未存联系人的国内号码（特别是运营商下发时自带 +86 / 0086 前缀的短信），
 * 在界面列表、会话标题和通知展示时，自动剥离生硬的国际区号，友好展示为国内标准格式。
 */
object PhoneNumberDisplayHelper {

    /**
     * 将原始号码格式化为适合国内阅读的友好展示号码：
     * 1. 11 位中国大陆手机号（如 +8613800000000、008613800000000、8613800000000）：去除 +86/0086/86，展示为 13800000000
     * 2. 常见企业服务号/特服号（如 +8610086、+8695588、+861069xxx）：去除 +86/0086，展示为 10086、95588、1069xxx
     * 3. 国际号码（如 +12025550123）与普通联系人名字：保持原样，不作篡改
     */
    fun formatDisplayNumber(rawAddress: String?): String {
        if (rawAddress.isNullOrBlank()) return ""
        val trimmed = rawAddress.trim()

        // 去除空格和破折号提取纯净形态
        val compact = trimmed.filterNot { it.isWhitespace() || it == '-' }

        // 识别中国大陆区号前缀
        val domestic = when {
            compact.startsWith("+86") -> compact.removePrefix("+86")
            compact.startsWith("0086") -> compact.removePrefix("0086")
            compact.startsWith("86") && compact.length == 13 && compact[2] == '1' -> compact.removePrefix("86")
            else -> null
        }

        if (domestic != null) {
            // 场景 1：中国大陆 11 位普通手机号 (13x ~ 19x)
            if (domestic.length == 11 && domestic.startsWith("1") && domestic.all { it.isDigit() }) {
                return domestic
            }

            // 场景 2：特服/政企通道短号 (1069xxx, 10086, 95xxx, 96xxx, 123xx, 400xxx, 800xxx)
            if (domestic.all { it.isDigit() } && (
                domestic.startsWith("10") ||
                domestic.startsWith("95") ||
                domestic.startsWith("96") ||
                domestic.startsWith("123") ||
                domestic.startsWith("400") ||
                domestic.startsWith("800")
            )) {
                return domestic
            }
        }

        return trimmed
    }
}
