package org.fossify.messages.autofill

/**
 * 验证码智能提取引擎
 * 支持各类银行、电商、政务、社交等中英文短信特征识别，具备防误判与签名剔除能力。
 */
object VerificationCodeExtractor {

    private val KEYWORD_PATTERNS = listOf(
        "验证码", "动态密码", "验证密码", "动态码", "校验码", "确认码", "授权码", "随机码",
        "安全码", "动态口令", "口令", "密码", "激活码", "提取码", "凭证码",
        "code", "verification", "otp", "passcode", "pin"
    )

    // 常见客服电话与年份过滤集合，防止误提取
    private val EXCLUDE_NUMBERS = setOf(
        "10086", "10010", "10000", "10099", "12306", "12345", "95588", "95533",
        "95566", "95599", "95555", "95528", "95511", "95188", "95017",
        "2023", "2024", "2025", "2026", "2027", "2028", "2029", "2030"
    )

    // 首尾签名正则（中括号、全角括号、英文括号）
    private val SIGNATURE_START_REGEX = Regex("""^(?:\[|【|〔|\()(.+?)(?:\]|】|〕|\))""")
    private val SIGNATURE_END_REGEX = Regex("""(?:\[|【|〔|\()(.+?)(?:\]|】|〕|\))$""")

    private val CODE_REGEXES = listOf(
        // 1. 紧随关键词后的验证码（支持冒号、空格、等号、“为”、“是”）：如 验证码: 123456 / 动态口令为8888 / 动态密码是[998877]
        Regex("""(?:验证码|动态密码|验证密码|动态码|校验码|确认码|授权码|安全码|动态口令|口令|密码|激活码|提取码|code|Code|OTP|otp|passcode|PIN)[^\d]*?([0-9a-zA-Z]{4,8})\b"""),

        // 2. 关键词在后，验证码在前的格式：如 123456(动态验证码) / 8888为您的确认码
        Regex("""\b([0-9a-zA-Z]{4,8})\s*(?:为您的|是您的|即为|为本次|作为|为登录)?\s*(?:验证码|动态密码|验证密码|动态码|校验码|确认码|授权码|安全码|动态口令|口令)"""),

        // 3. 正文中间括号/特殊符号专门包裹的验证码：如 您的验证码是【123456】 / [888888]
        Regex("""[【\[\(（]([0-9]{4,8})[】\]\)）]"""),

        // 4. “是/为”紧跟纯数字：如 验证码为 123456 / 是 888888
        Regex("""(?:为|是|：|:)\s*([0-9]{4,8})\b""")
    )

    fun isVerificationSms(body: String): Boolean {
        val lower = body.lowercase()
        return KEYWORD_PATTERNS.any { lower.contains(it) }
    }

    fun extractCode(body: String): String? {
        if (!isVerificationSms(body)) return null

        // 1. 预先剥离首尾发件方签名，严防将【10086】、【12306】等签名误判为验证码
        var cleanBody = body.trim()
        val startSig = SIGNATURE_START_REGEX.find(cleanBody)
        if (startSig != null) {
            cleanBody = cleanBody.substring(startSig.range.last + 1).trim()
        }
        val endSig = SIGNATURE_END_REGEX.find(cleanBody)
        if (endSig != null) {
            cleanBody = cleanBody.substring(0, endSig.range.first).trim()
        }

        // 2. 按优先级尝试正则匹配
        for (regex in CODE_REGEXES) {
            val matches = regex.findAll(cleanBody)
            for (match in matches) {
                val candidate = match.groupValues.getOrNull(1)?.trim()
                if (isValidCode(candidate)) {
                    return candidate
                }
            }
        }

        // 3. 兜底策略：如果正文包含强验证码关键词，提取正文中唯一孤立的 4~6 位纯数字（排除年份与尾号）
        val fallbackMatches = Regex("""\b([0-9]{4,6})\b""").findAll(cleanBody).map { it.groupValues[1] }.toList()
        val validFallbacks = fallbackMatches.filter { isValidCode(it) }
        if (validFallbacks.size == 1) {
            return validFallbacks.first()
        }

        return null
    }

    private fun isValidCode(code: String?): Boolean {
        if (code.isNullOrBlank()) return false
        val len = code.length
        if (len !in 4..8) return false

        // 排除年份与常见客服号码
        if (EXCLUDE_NUMBERS.contains(code)) return false

        // 纯字母如果不是 hex/特定码则不属于常见验证码
        if (code.all { it.isLetter() } && len > 5) return false

        return true
    }
}
