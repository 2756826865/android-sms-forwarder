package org.fossify.messages.rule.template

import org.fossify.messages.rule.model.IncomingMessageContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 动态转发模板渲染引擎
 */
object TemplateRenderer {

    // 优先匹配紧随关键字后的验证码
    private val KEYWORD_CODE_REGEX = Regex("""(?:验证码|动态码|校验码|code|Code|CODE|授权码|随机码|确认码|PIN)[^0-9a-zA-Z]{0,6}?([0-9]{4,8})(?!\d)""")
    private val FALLBACK_CODE_REGEX = Regex("""(?<!\d)([0-9]{4,8})(?!\d)""")

    fun render(template: String?, context: IncomingMessageContext): String {
        if (template.isNullOrBlank()) {
            return context.body
        }

        val code = extractVerificationCode(context.body)
        val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(context.timestamp))

        return template
            .replace("{{sender}}", context.sender)
            .replace("{{body}}", context.body)
            .replace("{{code}}", code)
            .replace("{{time}}", formattedTime)
            .replace("{{sim}}", if (context.subscriptionId >= 0) "SIM ${context.subscriptionId}" else "Default SIM")
    }

    fun extractVerificationCode(body: String): String {
        val keywordMatch = KEYWORD_CODE_REGEX.find(body)
        if (keywordMatch != null && keywordMatch.groupValues.size > 1) {
            return keywordMatch.groupValues[1]
        }
        val fallbackMatch = FALLBACK_CODE_REGEX.find(body)
        return fallbackMatch?.value ?: ""
    }
}
