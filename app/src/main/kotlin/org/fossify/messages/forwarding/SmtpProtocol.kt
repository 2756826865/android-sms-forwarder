package org.fossify.messages.forwarding

import java.io.BufferedReader

internal object SmtpProtocol {
    fun requireMailbox(value: String): String = value.trim().also {
        require(it.isNotBlank() && it.none { char -> char.isWhitespace() || char in "<>\r\n" } &&
            it.count { char -> char == '@' } == 1 && !it.startsWith('@') && !it.endsWith('@')) {
            "邮箱地址格式无效"
        }
    }

    /** A continuation must finish with the same reply code, never accept a truncated reply. */
    fun readReply(reader: BufferedReader): Int {
        var code: Int? = null
        repeat(100) {
            val line = reader.readLine() ?: error("SMTP 响应中断")
            val current = line.take(3).toIntOrNull() ?: error("SMTP 响应无效")
            require(current in 100..599 && (code == null || code == current)) { "SMTP 多行响应码不一致" }
            code = current
            if (line.length == 3 || line.getOrNull(3) == ' ') return current
            require(line.getOrNull(3) == '-') { "SMTP 响应格式无效" }
        }
        error("SMTP 响应行数过多")
    }
}
