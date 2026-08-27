package org.fossify.messages.rule.template

import org.fossify.messages.rule.model.IncomingMessageContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 动态转发模板渲染引擎
 */
object TemplateRenderer {

    private val CODE_REGEX = Regex("""(?<!\d)(\d{4,8})(?!\d)""")

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

    private fun extractVerificationCode(body: String): String {
        val match = CODE_REGEX.find(body)
        return match?.value ?: ""
    }
}
