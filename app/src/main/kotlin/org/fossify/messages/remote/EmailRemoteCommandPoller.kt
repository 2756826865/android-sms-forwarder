package org.fossify.messages.remote

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import org.fossify.messages.BuildConfig
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.remote.repository.RemoteSourceConnectionState
import org.fossify.messages.remote.repository.RemoteSourceRepository
import org.fossify.messages.remote.repository.RemoteSourceType

class EmailRemoteCommandPoller(
    private val context: Context,
    private val sourceInstanceId: String? = null,
    private val isCurrent: () -> Boolean = { true },
) {
    private val networkWakeup = ReconnectWakeup()
    private val running = AtomicBoolean(false)
    private var loopThread: Thread? = null
    private val activeSocket = java.util.concurrent.atomic.AtomicReference<Socket?>()

    fun start(intervalMs: Long = 60_000L, onStatus: (String) -> Unit = {}) {
        if (!running.compareAndSet(false, true)) return
        loopThread = Thread {
            onStatus("已启动邮箱 IMAP 轮询监听…")
            var consecutiveErrors = 0
            while (running.get() && isCurrent()) {
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
                        networkWakeup.await(sleepTime)
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

    fun onNetworkChanged() {
        if (!running.get()) return
        runCatching { activeSocket.getAndSet(null)?.close() }
        networkWakeup.signal()
    }

    fun stop() {
        running.set(false)
        networkWakeup.signal()
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
            if (isCurrent())
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
                if (!isTaggedOk(loginResp)) {
                    val err = "IMAP 登录失败：${loginResp.lastOrNull()}"
                    config.appendEmailRemoteLog(err)
                    onStatus(err)
                    if (isCurrent())
                        repo.updateConnectionState(instance.id, RemoteSourceConnectionState.ERROR, errorMessage = err)
                    return 0
                }

                // 网易 163/126/yeah 的 IMAP 服务要求认证后主动发送 RFC 2971 ID，
                // 否则后续 SELECT 可能返回 "Unsafe Login"。其他邮箱若不支持 ID，
                // 通常返回 BAD；该能力声明不应阻断标准 IMAP 流程。
                val idResp = send(
                    "ID (\"name\" \"SMS Forwarder\" \"version\" \"${BuildConfig.VERSION_NAME}\" " +
                        "\"vendor\" \"Fossify\" \"support-email\" \"noreply@localhost\")"
                )
                if (host.endsWith(".163.com", ignoreCase = true) ||
                    host.endsWith(".126.com", ignoreCase = true) ||
                    host.endsWith(".yeah.net", ignoreCase = true)
                ) {
                    val idAccepted = isTaggedOk(idResp)
                    if (!idAccepted) {
                        val err = "网易 IMAP ID 身份声明失败：${idResp.lastOrNull()}"
                        config.appendEmailRemoteLog(err)
                        onStatus(err)
                    }
                }

                val selectResp = send("SELECT INBOX")
                check(isTaggedOk(selectResp)) { "IMAP SELECT INBOX 失败：${selectResp.lastOrNull()}" }
                if (isCurrent())
                    repo.updateConnectionState(instance.id, RemoteSourceConnectionState.READY)
                onStatus("IMAP 连接就绪，正在检查收件箱…")
                val uidValidity = selectResp.asSequence()
                    .mapNotNull { UID_VALIDITY_REGEX.find(it)?.groupValues?.getOrNull(1) }
                    .firstOrNull()
                    .orEmpty()
                val searchResp = send("UID SEARCH UNSEEN")
                check(isTaggedOk(searchResp)) { "IMAP UID SEARCH 失败：${searchResp.lastOrNull()}" }
                val unseenLine = searchResp.firstOrNull { it.startsWith("* SEARCH") }.orEmpty()
                val messageUids = unseenLine.removePrefix("* SEARCH").trim().split("\\s+".toRegex()).filter(String::isNotBlank)

                val authorizedSenders = instance.authorizedUsers

                for (uid in messageUids) {
                    val checkedKey = "$host|$user|$uidValidity|$uid"
                    if (wasNonCommandChecked(checkedKey)) continue

                    val fetchLines = send(
                        "UID FETCH $uid (INTERNALDATE BODY.PEEK[HEADER.FIELDS " +
                            "(FROM SUBJECT MESSAGE-ID DATE CONTENT-TYPE CONTENT-TRANSFER-ENCODING)] BODY.PEEK[TEXT])"
                    )
                    check(isTaggedOk(fetchLines)) { "IMAP UID FETCH 失败：${fetchLines.lastOrNull()}" }
                    val textMarkerIdx = fetchLines.indexOfFirst { it.contains("BODY[TEXT]", ignoreCase = true) }
                    val headerLines = if (textMarkerIdx >= 0) fetchLines.subList(0, textMarkerIdx + 1) else fetchLines
                    val unfoldedHeaders = headerLines.joinToString("\n").replace(Regex("\\r?\\n[ \\t]+"), " ")

                    val rawFrom = extractHeader(unfoldedHeaders, "From")
                    val decodedFrom = decodeMimeHeader(rawFrom)
                    val senderEmail = extractEmailAddress(decodedFrom)

                    val rawSubject = extractHeader(unfoldedHeaders, "Subject")
                    val decodedSubject = decodeMimeHeader(rawSubject)
                    val messageId = extractHeader(unfoldedHeaders, "Message-ID").ifBlank { "email-$uidValidity-$uid" }
                    val contentType = extractHeader(unfoldedHeaders, "Content-Type")
                    val transferEncoding = extractHeader(unfoldedHeaders, "Content-Transfer-Encoding").lowercase()
                    val emailReceivedAt = parseEmailReceivedAt(headerLines.joinToString("\n"), extractHeader(unfoldedHeaders, "Date"))

                    if (instance.whitelistEnabled) {
                        val isAuthorized = authorizedSenders.any { auth ->
                            val cleanAuth = auth.trim().lowercase()
                            senderEmail.isNotBlank() && senderEmail == cleanAuth
                        }
                        if (!isAuthorized) {
                            config.appendEmailRemoteLog("忽略未授权发件人 [$senderEmail]")
                            rememberNonCommandChecked(checkedKey)
                            continue
                        }
                    }

                    val customPrefix = instance.customCommandPrefix
                    val subjectCmd = RemoteSmsCommand.parse(decodedSubject, customPrefix)
                    val bodyCandidate = if (subjectCmd == null) {
                        extractBodyCommandLine(fetchLines, contentType, transferEncoding, customPrefix)
                    } else {
                        null
                    }
                    val matchedRawContent = when {
                        subjectCmd != null -> decodedSubject
                        bodyCandidate != null -> bodyCandidate
                        else -> null
                    }
                    if (matchedRawContent != null) {
                        if (handleEmailCommand(matchedRawContent, senderEmail, messageId, instance.id, emailReceivedAt)) {
                            send("UID STORE $uid +FLAGS (\\Seen)")
                            processedCount++
                        }
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
            if (isCurrent())
                repo.updateConnectionState(instance.id, RemoteSourceConnectionState.ERROR, errorMessage = err)
            throw e
        } finally {
            if (socket != null) {
                activeSocket.compareAndSet(socket, null)
            }
        }
        return processedCount
    }

    private fun handleEmailCommand(
        rawContent: String,
        sender: String,
        messageId: String,
        activeInstanceId: String,
        emailReceivedAt: Long,
    ): Boolean {
        if (!isCurrent()) return false
        val config = MultiForwardConfig(context)
        val envelope = RemoteCommandEnvelope(
            sourceType = RemoteSourceType.EMAIL,
            sourceInstanceId = activeInstanceId,
            sourceMessageKey = messageId.ifBlank { "email-$sender-${System.currentTimeMillis()}" },
            senderId = sender,
            rawContent = rawContent,
            receivedAt = emailReceivedAt
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
        return true
    }

    private fun extractHeader(text: String, name: String): String {
        val pattern = "(?i)^$name:\\s*(.+)$".toRegex(RegexOption.MULTILINE)
        return pattern.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun isTaggedOk(lines: List<String>): Boolean =
        lines.lastOrNull()?.matches(Regex("A\\d{4} OK(?:\\s|$).*", RegexOption.IGNORE_CASE)) == true

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

    private fun extractBodyCommandLine(
        fetchLines: List<String>,
        contentType: String,
        transferEncoding: String,
        customPrefix: String,
    ): String? {
        val textStartIdx = fetchLines.indexOfFirst { it.contains("BODY[TEXT]", ignoreCase = true) }
        val rawBodyLines = if (textStartIdx >= 0 && textStartIdx + 1 < fetchLines.size) {
            val slice = fetchLines.subList(textStartIdx + 1, fetchLines.size - 1).toMutableList()
            // 仅剥离 IMAP FETCH 响应末尾单独成行或包裹在最后一行的右括号，不误删短信正文自身的括号
            while (slice.isNotEmpty() && slice.last().trim() == ")") {
                slice.removeAt(slice.lastIndex)
            }
            slice
        } else {
            emptyList()
        }
        if (rawBodyLines.isEmpty()) return null
        val joinedRaw = rawBodyLines.joinToString("\n")
        val boundary = Regex("""boundary\s*=\s*"?([^";\s]+)"?""", RegexOption.IGNORE_CASE)
            .find(contentType)
            ?.groupValues
            ?.getOrNull(1)
        val candidateTexts = if (!boundary.isNullOrBlank()) {
            extractMultipartPlainBodies(joinedRaw, boundary)
        } else {
            listOf(decodeMimeBody(rawBodyLines, joinedRaw, transferEncoding))
        }
        for (decodedBody in candidateTexts) {
            val matchedLine = decodedBody.lineSequence()
                .map { it.trim() }
                .firstOrNull { line -> line.isNotBlank() && RemoteSmsCommand.parse(line, customPrefix) != null }
            if (matchedLine != null) return matchedLine
        }
        return null
    }

    private fun extractMultipartPlainBodies(rawMultipart: String, boundary: String): List<String> {
        val delimiter = "--$boundary"
        return rawMultipart.split(delimiter)
            .drop(1)
            .filterNot { it.trimStart().startsWith("--") }
            .mapNotNull { part ->
                val splitIdx = part.indexOf("\n\n")
                if (splitIdx < 0) return@mapNotNull null
                val partHeaders = part.substring(0, splitIdx).replace(Regex("\\r?\\n[ \\t]+"), " ")
                val partType = extractHeader(partHeaders, "Content-Type").lowercase()
                if (partType.isNotBlank() && !partType.contains("text/plain")) return@mapNotNull null
                val partEncoding = extractHeader(partHeaders, "Content-Transfer-Encoding").lowercase()
                val partBody = part.substring(splitIdx + 2).trim()
                decodeMimeBody(partBody.lines(), partBody, partEncoding)
            }
    }

    private fun decodeMimeBody(lines: List<String>, joinedRaw: String, transferEncoding: String): String {
        return when {
            transferEncoding.contains("base64") -> runCatching {
                val compact = lines.joinToString("") { it.trim() }
                String(android.util.Base64.decode(compact, android.util.Base64.DEFAULT), Charsets.UTF_8)
            }.getOrDefault(joinedRaw)
            transferEncoding.contains("quoted-printable") -> runCatching {
                val softJoined = joinedRaw.replace("=\r\n", "").replace("=\n", "")
                val bytes = softJoined.replace("=([0-9A-Fa-f]{2})".toRegex()) { m ->
                    m.groupValues[1].toInt(16).toChar().toString()
                }.toByteArray(Charsets.ISO_8859_1)
                String(bytes, Charsets.UTF_8)
            }.getOrDefault(joinedRaw)
            else -> joinedRaw
        }
    }

    private fun parseEmailReceivedAt(rawFetchText: String, dateHeader: String): Long {
        val internalMatch = INTERNAL_DATE_REGEX.find(rawFetchText)?.groupValues?.getOrNull(1)
        if (!internalMatch.isNullOrBlank()) {
            val parsed = runCatching {
                java.text.SimpleDateFormat("dd-MMM-yyyy HH:mm:ss Z", java.util.Locale.US)
                    .parse(internalMatch)?.time
            }.getOrNull()
            if (parsed != null && parsed > 0L) return parsed
        }
        if (dateHeader.isNotBlank()) {
            val patterns = listOf(
                "EEE, d MMM yyyy HH:mm:ss Z",
                "d MMM yyyy HH:mm:ss Z"
            )
            for (pat in patterns) {
                val parsed = runCatching {
                    java.text.SimpleDateFormat(pat, java.util.Locale.US).parse(dateHeader.trim())?.time
                }.getOrNull()
                if (parsed != null && parsed > 0L) return parsed
            }
        }
        return System.currentTimeMillis()
    }

    private fun rememberNonCommandChecked(key: String) {
        val prefs = context.getSharedPreferences(PREFS_CHECKED_MAIL, Context.MODE_PRIVATE)
        val checked = prefs.getStringSet(KEY_CHECKED_NON_COMMANDS, emptySet())
            ?.toMutableSet()
            ?: mutableSetOf()
        checked.add(key)
        if (checked.size > MAX_CHECKED_NON_COMMANDS) {
            checked.sortedWith(
                compareBy<String> { it.substringBeforeLast('|') }
                    .thenBy { it.substringAfterLast('|').toLongOrNull() ?: 0L }
            ).take(checked.size - MAX_CHECKED_NON_COMMANDS).forEach(checked::remove)
        }
        prefs.edit().putStringSet(KEY_CHECKED_NON_COMMANDS, checked).apply()
    }

    companion object {
        private const val TAG = "EmailRemotePoller"
        private const val PREFS_CHECKED_MAIL = "email_remote_checked_mail"
        private const val KEY_CHECKED_NON_COMMANDS = "non_command_uids"
        private const val MAX_CHECKED_NON_COMMANDS = 500
        private val UID_VALIDITY_REGEX = "\\[UIDVALIDITY\\s+(\\d+)]".toRegex(RegexOption.IGNORE_CASE)
        private val INTERNAL_DATE_REGEX = "INTERNALDATE\\s+\"([^\"]+)\"".toRegex(RegexOption.IGNORE_CASE)
    }
}
