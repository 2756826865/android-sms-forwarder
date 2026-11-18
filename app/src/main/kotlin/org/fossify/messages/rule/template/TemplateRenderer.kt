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
    private val PLACEHOLDER_REGEX = Regex("""\{\{([^\{\}]+)\}\}""")

    fun render(template: String?, context: IncomingMessageContext): String {
        if (template.isNullOrBlank()) {
            return context.body
        }

        val code by lazy { extractVerificationCode(context.body) }
        val formattedTime by lazy {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(context.timestamp))
        }
        val simLabel = if (context.subscriptionId in 0..1) {
            "SIM ${context.subscriptionId + 1}"
        } else if (context.subscriptionId > 1) {
            "订阅#${context.subscriptionId}"
        } else {
            "未知接收卡"
        }

        return PLACEHOLDER_REGEX.replace(template) { match ->
            when (match.value) {
                "{{sender}}", "{{SENDER}}", "{{from}}", "{{FROM}}" -> context.sender
                "{{body}}", "{{BODY}}", "{{sms}}", "{{SMS}}", "{{content}}", "{{CONTENT}}" -> context.body
                "{{code}}", "{{CODE}}" -> code
                "{{time}}", "{{TIME}}" -> formattedTime
                "{{sim}}", "{{SIM}}" -> simLabel
                else -> match.value
            }
        }
    }

    fun extractVerificationCode(body: String): String {
        return VerificationCodeExtractor.extractCode(body).orEmpty()
    }
}