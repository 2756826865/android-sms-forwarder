package org.fossify.messages.forwarding

import java.io.BufferedReader
import java.io.BufferedWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** One SMTP implementation for channel tests and real forwarding. */
internal object SmtpSender {
    private const val SMTP_TIMEOUT_MS = 12_000
    fun send(
        host: String,
        port: Int,
        security: Int,
        user: String,
        password: String,
        recipientsText: String,
        subject: String,
        content: String,
    ) {
        require(host.isNotBlank() && user.isNotBlank() && password.isNotBlank() && recipientsText.isNotBlank()) {
            "邮箱配置不完整"
        }
        require(port in 1..65535) { "SMTP 端口须在 1–65535 之间" }
        require(security == MultiForwardConfig.EMAIL_SECURITY_SSL || security == MultiForwardConfig.EMAIL_SECURITY_STARTTLS) {
            "SMTP 安全模式无效"
        }
        require(host == host.trim() && host.none { it.isWhitespace() || it in "/\\\r\n" }) { "SMTP 服务器地址格式无效" }
        val account = SmtpProtocol.requireMailbox(user)
        val recipients = recipientsText.split(',', ';')
            .map(String::trim)
            .filter(String::isNotBlank)
            .map(SmtpProtocol::requireMailbox)
            .distinct()
        require(recipients.isNotEmpty()) { "未配置收件邮箱" }

        if (security == MultiForwardConfig.EMAIL_SECURITY_STARTTLS) {
            sendEmailStartTls(host, port, account, password, recipients, subject, content)
        } else {
            createTlsSocket(host, port).use { socket ->
                expectSmtp(socket.inputStream.bufferedReader(StandardCharsets.UTF_8), 220)
                runSmtpSession(socket, account, password, recipients, subject, content)
            }
        }
    }

    private fun sendEmailStartTls(
        host: String,
        port: Int,
        user: String,
        password: String,
        recipients: List<String>,
        subject: String,
        content: String,
    ) {
        val plainSocket = Socket()
        plainSocket.use {
            it.connect(java.net.InetSocketAddress(host, port), SMTP_TIMEOUT_MS)
            it.soTimeout = SMTP_TIMEOUT_MS
            val reader = it.inputStream.bufferedReader(StandardCharsets.UTF_8)
            val writer = it.outputStream.bufferedWriter(StandardCharsets.UTF_8)
            expectSmtp(reader, 220)
            smtpCommand(writer, reader, "EHLO android-sms-forwarder", 250)
            smtpCommand(writer, reader, "STARTTLS", 220)

            val tlsSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(it, host, port, true) as SSLSocket
            tlsSocket.use { ssl ->
                configureTls(ssl)
                runSmtpSession(ssl, user, password, recipients, subject, content)
            }
        }
    }

    private fun runSmtpSession(
        socket: Socket,
        user: String,
        password: String,
        recipients: List<String>,
        subject: String,
        content: String,
    ) {
        val reader = socket.inputStream.bufferedReader(StandardCharsets.UTF_8)
        val writer = socket.outputStream.bufferedWriter(StandardCharsets.UTF_8)
        smtpCommand(writer, reader, "EHLO android-sms-forwarder", 250)
        smtpCommand(writer, reader, "AUTH LOGIN", 334)
        smtpCommand(writer, reader, java.util.Base64.getEncoder().encodeToString(user.toByteArray(StandardCharsets.UTF_8)), 334)
        smtpCommand(writer, reader, java.util.Base64.getEncoder().encodeToString(password.toByteArray(StandardCharsets.UTF_8)), 235)
        smtpCommand(writer, reader, "MAIL FROM:<$user>", 250)
        recipients.forEach {
            smtpCommand(writer, reader, "RCPT TO:<$it>", 250, 251)
        }
        smtpCommand(writer, reader, "DATA", 354)

        val encodedSubject = java.util.Base64.getEncoder().encodeToString(subject.toByteArray(StandardCharsets.UTF_8))
        val encodedBody = java.util.Base64.getMimeEncoder(76, "\r\n".toByteArray())
            .encodeToString(content.toByteArray(StandardCharsets.UTF_8))
        writer.write("From: <$user>\r\n")
        writer.write("To: ${recipients.joinToString(", ")}\r\n")
        writer.write("Subject: =?UTF-8?B?$encodedSubject?=\r\n")
        writer.write("MIME-Version: 1.0\r\n")
        writer.write("Content-Type: text/plain; charset=UTF-8\r\n")
        writer.write("Content-Transfer-Encoding: base64\r\n\r\n")
        writer.write(encodedBody)
        writer.write("\r\n.\r\n")
        writer.flush()
        expectSmtp(reader, 250)
        // DATA has already been accepted. A lost QUIT reply must not mark the
        // delivery as failed, because the Worker could then send it again.
        runCatching { smtpCommand(writer, reader, "QUIT", 221) }
    }

    private fun createTlsSocket(host: String, port: Int): SSLSocket {
        val plainSocket = Socket()
        try {
            plainSocket.connect(java.net.InetSocketAddress(host, port), SMTP_TIMEOUT_MS)
            plainSocket.soTimeout = SMTP_TIMEOUT_MS
            val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                .createSocket(plainSocket, host, port, true) as SSLSocket
            try {
                configureTls(sslSocket)
                return sslSocket
            } catch (error: Throwable) {
                runCatching { sslSocket.close() }
                throw error
            }
        } catch (error: Throwable) {
            runCatching { plainSocket.close() }
            throw error
        }
    }

    private fun configureTls(socket: SSLSocket) {
        socket.soTimeout = SMTP_TIMEOUT_MS
        socket.enabledProtocols = socket.enabledProtocols
            .filter { it == "TLSv1.2" || it == "TLSv1.3" }
            .toTypedArray()
        socket.sslParameters = socket.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
        }
        socket.startHandshake()
    }

    private fun smtpCommand(
        writer: BufferedWriter,
        reader: BufferedReader,
        command: String,
        vararg expected: Int
    ) {
        writer.write(command)
        writer.write("\r\n")
        writer.flush()
        expectSmtp(reader, *expected)
    }

    private fun expectSmtp(reader: BufferedReader, vararg expected: Int) {
        val code = SmtpProtocol.readReply(reader)
        check(code in expected) { "SMTP 服务器拒绝当前步骤（响应码 $code）" }
    }

}
