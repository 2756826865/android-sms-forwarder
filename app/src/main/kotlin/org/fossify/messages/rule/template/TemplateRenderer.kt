package org.fossify.messages.rule.template

import org.fossify.messages.autofill.VerificationCodeExtractor
import org.fossify.messages.rule.model.IncomingMessageContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 动态转发模板渲染引擎
 */
object TemplateRenderer {

    fun render(template: String?, context: IncomingMessageContext): String {
        if (template.isNullOrBlank()) {
            return context.body
        }

        val code = extractVerificationCode(context.body)
        val formattedTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(context.timestamp))

        return template
            .replace("{{sender}}", context.sender)
            .replace("{{SENDER}}", context.sender)
            .replace("{{body}}", context.body)
            .replace("{{BODY}}", context.body)
            .replace("{{code}}", code)
            .replace("{{CODE}}", code)
            .replace("{{time}}", formattedTime)
            .replace("{{TIME}}", formattedTime)
            .replace("{{sim}}", if (context.subscriptionId >= 0) "SIM ${context.subscriptionId}" else "Default SIM")
            .replace("{{SIM}}", if (context.subscriptionId >= 0) "SIM ${context.subscriptionId}" else "Default SIM")
    }

    fun extractVerificationCode(body: String): String {
        return VerificationCodeExtractor.extractCode(body).orEmpty()
    }
}