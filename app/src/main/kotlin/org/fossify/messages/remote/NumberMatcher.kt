package org.fossify.messages.remote

/**
 * 远程指令来源的号码归一化与等价判定工具（全项目唯一实现）。
 *
 * 安全说明（改动时请务必保留）：
 * 1. 只接受 ASCII 的 '0'..'9'，不使用 [Char.isDigit]。后者会放行 Unicode 其他十进制数字
 *    （如阿拉伯-印度数字 ٠١٢٣），可被用于构造绕过白名单的号码。
 * 2. 号码中 ',' 与 ';' 及其后的内容视为分机号/附加参数，先截断再归一化。
 * 3. '86' / '0086' 前缀仅在剥离后为 11 位且以 '1' 开头（中国大陆手机号）时才剥离，
 *    否则保留原串，避免对海外号码做破坏性截断。
 * 4. 短号（≤ 6 位，如 1069、10086）只与短号比较，绝不与手机号比较。
 *    这是修复 "白名单填 1069，攻击号码 13800121069 因 endsWith 命中而通过鉴权" 的关键。
 */
object NumberMatcher {

    /** 中国大陆手机号长度。 */
    private const val CN_MOBILE_LENGTH = 11

    /** 短号的最大长度（含）。 */
    private const val SHORT_CODE_MAX_LENGTH = 6

    /**
     * 将任意号码/账号文本归一化为纯数字比较键。
     *
     * @param raw 原始文本，可包含 +、空格、-、括号、分机号等。
     * @return 归一化后的纯 ASCII 数字串；输入无有效数字时返回空串。
     */
    fun normalize(raw: String): String {
        // ',' / ';' 之后的内容视为分机号或附加参数，直接丢弃。
        val digits = raw
            .takeWhile { it != ',' && it != ';' }
            .filter { it in '0'..'9' }

        if (digits.startsWith("0086")) {
            val rest = digits.drop(4)
            if (isCnMobile(rest)) return rest
        }
        if (digits.startsWith("86") && digits.length == 13) {
            val rest = digits.drop(2)
            if (isCnMobile(rest)) return rest
        }
        return digits
    }

    /** 判定是否为中国大陆手机号（11 位且以 1 开头）。 */
    fun isCnMobile(value: String): Boolean =
        value.length == CN_MOBILE_LENGTH && value[0] == '1'

    /** 判定是否为短号/特服号（长度不超过 6 位的纯数字）。 */
    fun isShortCode(value: String): Boolean =
        value.isNotEmpty() && value.length <= SHORT_CODE_MAX_LENGTH

    /**
     * 判定两个号码是否视为同一个发件人。
     *
     * 两侧都先做 [normalize]，再要求"短号属性一致"并严格相等。
     * 任何一方归一化后为空即视为不匹配。
     */
    fun equivalent(a: String, b: String): Boolean {
        val left = normalize(a)
        val right = normalize(b)
        if (left.isEmpty() || right.isEmpty()) return false
        if (isShortCode(left) != isShortCode(right)) return false
        return left == right
    }

    /**
     * 白名单落库前的条目归一化：仅对"看起来像电话号码"的条目做 [normalize]，
     * 非电话号码条目（Telegram/飞书的用户名、邮箱等）原样保留大小写与字符，
     * 避免把纯字母账号归一化成空串而误伤非短信来源。
     */
    fun normalizeWhitelistEntry(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""
        val normalized = normalize(trimmed)
        return if (normalized.isEmpty()) trimmed else normalized
    }

    /** 白名单集合批量归一化（用于保存与导入，保证与运行时比较两侧同构）。 */
    fun normalizeWhitelist(entries: Collection<String>): Set<String> =
        entries.map(::normalizeWhitelistEntry).filterTo(LinkedHashSet()) { it.isNotBlank() }
}
