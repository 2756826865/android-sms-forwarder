package org.fossify.messages.autofill

/**
 * 验证码智能提取引擎
 * 支持各类银行、短信验证码、动态码等中英文短信特征识别
 */
object VerificationCodeExtractor {

    private val KEYWORD_PATTERNS = listOf(
        "验证码", "验证密码", "动态码", "校验码", "确认码", "授权码", "随机码",
        "安全码", "口令", "code", "verification", "otp", "passcode"
    )

    private val CODE_REGEXES = listOf(
        // 1. 明确跟随关键词的数字/字母: (验证密码)951332 / 验证码是: 123456 / 验证码：1234
        Regex("""(?:验证码|验证密码|动态码|校验码|确认码|授权码|安全码|code|Code|OTP|otp)[^\d]*?([0-9a-zA-Z]{4,8})\b"""),
        // 2. 括号包裹的验证码: 【123456】 / [123456]
        Regex("""[【\[(]([0-9]{4,8})[】\])]"""),
        // 3. 为/是/为: 123456
        Regex("""(?:为|是|：|:)\s*([0-9]{4,8})\b"""),
        // 4. 纯数字提取 (4-6位独立纯数字)
        Regex("""\b([0-9]{4,6})\b""")
    )

    fun isVerificationSms(body: String): Boolean {
        val lower = body.lowercase()
        return KEYWORD_PATTERNS.any { lower.contains(it) }
    }

    fun extractCode(body: String): String? {
        if (!isVerificationSms(body)) return null

        for (regex in CODE_REGEXES) {
            val match = regex.find(body)
            if (match != null) {
                val code = match.groupValues.getOrNull(1)?.trim()
                if (!code.isNullOrBlank() && code.length in 4..8) {
                    return code
                }
            }
        }
        return null
    }
}
