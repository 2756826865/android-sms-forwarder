package org.fossify.messages.remote

import android.content.Context
import android.util.Log
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.remote.repository.RemoteSourceConnectionState
import org.fossify.messages.remote.repository.RemoteSourceRepository
import org.fossify.messages.remote.repository.RemoteSourceType
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class EmailRemoteCommandPoller(
    private val context: Context,
    private val sourceInstanceId: String? = null
) {
    private val running = AtomicBoolean(false)
    private var loopThread: Thread? = null
    private val activeSocket = java.util.concurrent.atomic.AtomicReference<Socket?>()

    fun start(intervalMs: Long = 60_000L, onStatus: (String) -> Unit = {}) {
        if (!running.compareAndSet(false, true)) return
        loopThread = Thread {
            onStatus("已启动邮箱 IMAP 轮询监听…")
            var consecutiveErrors = 0
            while (running.get()) {
                try {
                    val processed = pollOnce(onStatus)
                    consecutiveErrors = 0
                    if (processed > 0) {
                        onStatus("已处理 $processed 条邮件指令")
                    }
                } catch (e: Throwable) {
                    consecutiveErrors++
                    val errMsg = "IMAP 轮询异常：${e.message ?: e.javaClass.simpleName}"
                    Log.e(TAG, "Email loop error", e)
                    onStatus(errMsg)
                }

                val sleepTime = if (consecutiveErrors > 0) {
                    // 指数退避：从 intervalMs 开始加倍，上限 5 分钟 (300,000ms)
                    val backoff = (intervalMs * (1L shl (consecutiveErrors.coerceAtMost(5) - 1))).coerceIn(intervalMs, 300_000L)
                    onStatus("连接异常，将在 ${backoff / 1000} 秒后重试 (第 $consecutiveErrors 次退避)")
                    backoff
                } else {
                    intervalMs
                }

                try {
                    Thread.sleep(sleepTime)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }.apply {
            name = "email-remote-poller-${sourceInstanceId ?: "def"}"
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running.set(false)
        loopThread?.interrupt()
        loopThread = null
        try {
            activeSocket.getAndSet(null)?.close()
        } catch (e: Throwable) {
            Log.w(TAG, "Error closing active socket on stop", e)
        }
    }

    fun pollOnce(onStatus: (String) -> Unit = {}): Int {
        val repo = RemoteSourceRepository.getInstance(context)
        val instance = sourceInstanceId?.let { repo.getSourceById(it) }
        if (instance == null || !instance.enabled) return 0

        val host = instance.optString("host")
        val port = instance.optInt("port", 0)
        val user = instance.optString("user")
        val pass = instance.optString("pass")
        if (host.isBlank() || user.isBlank() || pass.isBlank()) {
            repo.updateConnectionState(instance.id, RemoteSourceConnectionState.CONFIG_REQUIRED)
            return 0
        }

        var processedCount = 0
        val config = MultiForwardConfig(context)
        var socket: Socket? = null
        try {
            val security = instance.optString("security")
            val isSsl = if (security.isNotBlank()) {
                when (security.trim().lowercase()) {
                    "0", "ssl", "true" -> true
                    "1", "starttls", "false", "plain" -> false
                    else -> instance.optBoolean("ssl", true)
                }
            } else {
                instance.optBoolean("ssl", true)
            }
            val effectivePort = if (port > 0) port else if (isSsl) 993 else 143
            socket = createSocket(host, effectivePort, isSsl)
            activeSocket.set(socket)
            socket.use { s ->
                s.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(s.inputStream, StandardCharsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(s.outputStream, StandardCharsets.UTF_8))

                // STARTTLS 分支已在 createSocket 中读取过明文 banner；TLS 握手后不会再发第二次。
                if (isSsl) reader.readLine()

                var tagId = 1
                fun send(cmd: String): List<String> {
                    val tag = "A%04d".format(tagId++)
                    writer.write("$tag $cmd\r\n")
                    writer.flush()
                    val lines = mutableListOf<String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        lines.add(line)
                        if (line.startsWith("$tag OK") || line.startsWith("$tag NO") || line.startsWith("$tag BAD")) {
                            break
                        }
                    }
                    return lines
                }

                val loginResp = send("LOGIN ${quoteImap(user)} ${quoteImap(pass)}")
                if (loginResp.none { it.contains("OK") }) {
                    val err = "IMAP 登录失败：${loginResp.lastOrNull()}"
                    config.appendEmailRemoteLog(err)
                    onStatus(err)
                    repo.updateConnectionState(instance.id, RemoteSourceConnectionState.ERROR, errorMessage = err)
                    return 0
                }

                // 标记连接在线就绪
                repo.updateConnectionState(instance.id, RemoteSourceConnectionState.READY)
                onStatus("IMAP 连接就绪，正在检查收件箱…")

                val selectResp = send("SELECT INBOX")
                val uidValidity = selectResp.asSequence()
                    .mapNotNull { UID_VALIDITY_REGEX.find(it)?.groupValues?.getOrNull(1) }
                    .firstOrNull()
                    .orEmpty()
                val searchResp = send("UID SEARCH UNSEEN")
                val unseenLine = searchResp.firstOrNull { it.startsWith("* SEARCH") }.orEmpty()
                val messageUids = unseenLine.removePrefix("* SEARCH").trim().split("\\s+".toRegex()).filter(String::isNotBlank)

                val authorizedSenders = instance.authorizedUsers

                for (uid in messageUids) {
                    val checkedKey = "$host|$user|$uidValidity|$uid"
                    if (wasNonCommandChecked(checkedKey)) continue

                    val fetchLines = send("UID FETCH $uid (BODY.PEEK[HEADER.FIELDS (FROM SUBJECT MESSAGE-ID)] BODY.PEEK[TEXT])")
                    val headerText = fetchLines.joinToString("\n")

                    val rawFrom = extractHeader(headerText, "From")
                    val decodedFrom = decodeMimeHeader(rawFrom)
                    val senderEmail = extractEmailAddress(decodedFrom)

                    val rawSubject = extractHeader(headerText, "Subject")
                    val decodedSubject = decodeMimeHeader(rawSubject)
                    val messageId = extractHeader(headerText, "Message-ID").ifBlank { "email-$uidValidity-$uid" }

                    if (instance.whitelistEnabled && authorizedSenders.isNotEmpty()) {
                        val isAuthorized = authorizedSenders.any { auth ->
                            val cleanAuth = auth.trim().lowercase()
                            senderEmail.isNotBlank() && senderEmail == cleanAuth
                        }
                        if (!isAuthorized) {
                            config.appendEmailRemoteLog("忽略未授权发件人 [$senderEmail]")
                            send("UID STORE $uid +FLAGS (\\Seen)")
                            continue
                        }
                    }

                    val customPrefix = instance.customCommandPrefix
                    val command = RemoteSmsCommand.parse(decodedSubject, customPrefix)
                        ?: RemoteSmsCommand.parse(headerText, customPrefix)
                    if (command != null) {
                        val rawContent = if (RemoteSmsCommand.parse(decodedSubject, customPrefix) != null) decodedSubject else headerText
                        handleEmailCommand(rawContent, senderEmail, messageId, instance.id)
                        send("UID STORE $uid +FLAGS (\\Seen)")
                        processedCount++
                    } else {
                        // 普通未读邮件不应被本应用擅自标记为已读，同时也不应每分钟重复下载全文。
                        rememberNonCommandChecked(checkedKey)
                    }
                }

                send("LOGOUT")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Poll error", e)
            val err = "轮询失败：${e.message ?: e.javaClass.simpleName}"
            config.appendEmailRemoteLog(err)
            onStatus(err)
            repo.updateConnectionState(instance.id, RemoteSourceConnectionState.ERROR, errorMessage = err)
            throw e
        } finally {
            if (socket != null) {
                activeSocket.compareAndSet(socket, null)
            }
        }
        return processedCount
    }

    private fun handleEmailCommand(rawContent: String, sender: String, messageId: String, activeInstanceId: String) {
        val config = MultiForwardConfig(context)
        val envelope = RemoteCommandEnvelope(
            sourceType = RemoteSourceType.EMAIL,
            sourceInstanceId = activeInstanceId,
            sourceMessageKey = messageId.ifBlank { "email-$sender-${System.currentTimeMillis()}" },
            senderId = sender,
            rawContent = rawContent,
            receivedAt = System.currentTimeMillis()
        )

        when (val result = RemoteCommandProcessor.process(context, envelope)) {
            is RemoteProcessResult.Success -> {
                config.appendEmailRemoteLog("收到指令并加入队列 -> ${result.target} (发件人: $sender)")
            }
            is RemoteProcessResult.Duplicate -> {
                config.appendEmailRemoteLog("抑制重复指令 (发件人: $sender)")
            }
            is RemoteProcessResult.Rejected -> {
                config.appendEmailRemoteLog("指令被拒绝：${result.detail.ifBlank { result.reason }} (发件人: $sender)")
            }
            is RemoteProcessResult.Ignored -> {}
        }
    }

    private fun extractHeader(text: String, name: String): String {
        val pattern = "(?i)^$name:\\s*(.+)$".toRegex(RegexOption.MULTILINE)
        return pattern.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun extractEmailAddress(raw: String): String {
        val bracketMatch = "<([^>]+)>".toRegex().find(raw)
        if (bracketMatch != null) {
            return bracketMatch.groupValues[1].trim().lowercase()
        }
        val directMatch = "([a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,})".toRegex().find(raw)
        if (directMatch != null) {
            return directMatch.groupValues[1].trim().lowercase()
        }
        return raw.trim().lowercase()
    }

    private fun decodeMimeHeader(raw: String): String = runCatching {
        val pattern = """=\?([a-zA-Z0-9_-]+)\?([bBqQ])\?([^?]+)\?=""".toRegex()
        pattern.replace(raw) { match ->
            val charsetName = match.groupValues[1]
            val encoding = match.groupValues[2].uppercase()
            val encodedText = match.groupValues[3]
            val charset = runCatching { java.nio.charset.Charset.forName(charsetName) }.getOrDefault(Charsets.UTF_8)
            if (encoding == "B") {
                val bytes = android.util.Base64.decode(encodedText, android.util.Base64.DEFAULT)
                String(bytes, charset)
            } else if (encoding == "Q") {
                val bytes = encodedText.replace('_', ' ').replace("=([0-9A-Fa-f]{2})".toRegex()) { hexMatch ->
                    hexMatch.groupValues[1].toInt(16).toChar().toString()
                }.toByteArray(Charsets.ISO_8859_1)
                String(bytes, charset)
            } else {
                match.value
            }
        }
    }.getOrDefault(raw)

    private fun createSocket(host: String, port: Int, useSsl: Boolean): Socket {
        return if (useSsl) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(host, port).apply {
                (this as? SSLSocket)?.let { configureTls(it) }
            }
        } else {
            val plainSocket = Socket(host, port).apply { soTimeout = 15_000 }
            try {
                val reader = BufferedReader(InputStreamReader(plainSocket.inputStream, StandardCharsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(plainSocket.outputStream, StandardCharsets.UTF_8))
                check(reader.readLine()?.startsWith("* OK", ignoreCase = true) == true) {
                    "IMAP 服务器未返回有效欢迎消息"
                }
                writer.write("A0000 STARTTLS\r\n")
                writer.flush()
                val accepted = generateSequence { reader.readLine() }
                    .firstOrNull { it.startsWith("A0000 ", ignoreCase = true) }
                    ?.startsWith("A0000 OK", ignoreCase = true) == true
                check(accepted) { "IMAP 服务器不支持 STARTTLS，已拒绝明文登录" }

                (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(plainSocket, host, port, true)
                    .let { it as SSLSocket }
                    .also(::configureTls)
            } catch (error: Throwable) {
                runCatching { plainSocket.close() }
                throw error
            }
        }
    }

    private fun configureTls(socket: SSLSocket) {
        socket.sslParameters = socket.sslParameters.apply {
            endpointIdentificationAlgorithm = "HTTPS"
        }
        socket.startHandshake()
    }

    private fun quoteImap(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            if (char == '\\' || char == '"') append('\\')
            append(char)
        }
        append('"')
    }

    private fun wasNonCommandChecked(key: String): Boolean {
        return context.getSharedPreferences(PREFS_CHECKED_MAIL, Context.MODE_PRIVATE)
            .getStringSet(KEY_CHECKED_NON_COMMANDS, emptySet())
            ?.contains(key) == true
    }

    private fun rememberNonCommandChecked(key: String) {
        val prefs = context.getSharedPreferences(PREFS_CHECKED_MAIL, Context.MODE_PRIVATE)
        val checked = prefs.getStringSet(KEY_CHECKED_NON_COMMANDS, emptySet())
            ?.toMutableSet()
            ?: mutableSetOf()
        checked.add(key)
        if (checked.size > MAX_CHECKED_NON_COMMANDS) {
            checked.sorted().take(checked.size - MAX_CHECKED_NON_COMMANDS).forEach(checked::remove)
        }
        prefs.edit().putStringSet(KEY_CHECKED_NON_COMMANDS, checked).apply()
    }

    companion object {
        private const val TAG = "EmailRemotePoller"
        private const val PREFS_CHECKED_MAIL = "email_remote_checked_mail"
        private const val KEY_CHECKED_NON_COMMANDS = "non_command_uids"
        private const val MAX_CHECKED_NON_COMMANDS = 500
        private val UID_VALIDITY_REGEX = "\\[UIDVALIDITY\\s+(\\d+)]".toRegex(RegexOption.IGNORE_CASE)
    }
}
