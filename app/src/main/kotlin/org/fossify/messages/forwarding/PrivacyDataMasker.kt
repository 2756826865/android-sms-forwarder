package org.fossify.messages.forwarding

/**
 * 纯本地隐私数据正则脱敏引擎
 * 自动对手机号、身份证号、银行卡号等敏感数据进行掩码处理
 */
object PrivacyDataMasker {

    private val PHONE_REGEX = Regex("""(?<!\d)(1[3-9]\d)(\d{4})(\d{4})(?!\d)""")
    private val ID_CARD_REGEX = Regex("""(?<!\d)([1-9]\d{5})(?:18|19|20)\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\d|3[01])(\d{3}[\dXx])(?!\d)""")
    private val BANK_CARD_REGEX = Regex("""(?<!\d)([3-6]\d{3})\d{8,11}(\d{4})(?!\d)""")

    fun mask(
        content: String,
        maskPhone: Boolean = true,
        maskIdCard: Boolean = true,
        maskBankCard: Boolean = true,
        maskVerificationCode: Boolean = false,
        verificationCode: String? = null
    ): String {
        var masked = content

        if (maskPhone) {
            masked = masked.replace(PHONE_REGEX) { match ->
                "${match.groupValues[1]}****${match.groupValues[3]}"
            }
        }

        if (maskIdCard) {
            masked = masked.replace(ID_CARD_REGEX) { match ->
                "${match.groupValues[1]}********${match.groupValues[2]}"
            }
        }

        if (maskBankCard) {
            masked = masked.replace(BANK_CARD_REGEX) { match ->
                val first = match.groupValues[1]
                val last = match.groupValues[2]
                "$first **** **** $last"
            }
        }

        if (maskVerificationCode && !verificationCode.isNullOrBlank()) {
            masked = masked.replace(verificationCode, "[******]")
        }

        return masked
    }
}
