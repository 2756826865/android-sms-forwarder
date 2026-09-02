package org.fossify.messages.remote

import android.content.Context
import android.util.Log
import org.fossify.messages.forwarding.ForwardingRuleEngine
import org.fossify.messages.forwarding.ForwardingRulesConfig
import org.fossify.messages.forwarding.MultiForwardConfig
import org.fossify.messages.helpers.RemoteCommandRepository
import org.fossify.messages.messaging.SimSendResolver
import org.fossify.messages.models.RemoteCommandContext
import org.fossify.messages.models.RemoteCommandSourceType
import org.fossify.messages.models.RemoteCommandType
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

class EmailRemoteCommandPoller(private val context: Context) {

    fun pollOnce(): Int {
        val config = MultiForwardConfig(context)
        if (!config.emailRemoteControlEnabled) return 0
        val host = config.emailRemoteHost()
        val port = config.emailRemotePort()
        val user = config.emailRemoteUser()
        val pass = config.emailRemotePassword()
        if (host.isBlank() || user.isBlank() || pass.isBlank()) {
            config.appendEmailRemoteLog("缺少邮箱主机/账号/授权码")
            return 0
        }

        var processedCount = 0
        try {
            val socket = createSocket(host, port, config.emailRemoteSecurity == MultiForwardConfig.EMAIL_SECURITY_SSL)
            socket.use { s ->
                s.soTimeout = 15_000
                val reader = BufferedReader(InputStreamReader(s.inputStream, StandardCharsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(s.outputStream, StandardCharsets.UTF_8))

                reader.readLine() // Banner

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

                val loginResp = send("LOGIN $user $pass")
                if (loginResp.none { it.contains("OK") }) {
                    config.appendEmailRemoteLog("IMAP 登录失败：${loginResp.lastOrNull()}")
                    return 0
                }

                send("SELECT INBOX")
                val searchResp = send("SEARCH UNSEEN")
                val unseenLine = searchResp.firstOrNull { it.startsWith("* SEARCH") }.orEmpty()
                val seqNumbers = unseenLine.removePrefix("* SEARCH").trim().split("\\s+".toRegex()).filter(String::isNotBlank)

                val authorizedSenders = config.emailRemoteAuthorizedSenders().split('\n', ',', ';', '，', '；')
                    .map(String::trim).filter(String::isNotBlank)

                for (seq in seqNumbers) {
                    val fetchLines = send("FETCH $seq (BODY[HEADER.FIELDS (FROM SUBJECT MESSAGE-ID)] BODY[TEXT])")
                    val headerText = fetchLines.joinToString("\n")

                    val from = extractHeader(headerText, "From")
                    val subject = extractHeader(headerText, "Subject")
                    val messageId = extractHeader(headerText, "Message-ID").ifBlank { "email-$seq-${System.currentTimeMillis()}" }

                    if (authorizedSenders.isNotEmpty() && !authorizedSenders.any { from.contains(it, ignoreCase = true) }) {
                        config.appendEmailRemoteLog("忽略未授权发件人 [$from]")
                        continue
                    }

                    val customPrefix = config.emailRemoteCustomPrefix()
                    val command = RemoteSmsCommand.parse(subject, customPrefix) ?: RemoteSmsCommand.parse(headerText, customPrefix)
                    if (command != null) {
                        handleEmailCommand(command, from, messageId)
                        send("STORE $seq +FLAGS (\\Seen)")
                        processedCount++
                    }
                }

                send("LOGOUT")
            }
        } catch (e: Throwable) {
            Log.e("EmailRemotePoller", "Poll error", e)
            config.appendEmailRemoteLog("轮询失败：${e.message ?: e.javaClass.simpleName}")
        }
        return processedCount
    }

    private fun handleEmailCommand(command: RemoteSmsCommand, sender: String, messageId: String) {
        val config = MultiForwardConfig(context)
        val sendMode = command.effectiveSendMode(config.emailRemoteSendSimMode)
        val messageKey = messageId.ifBlank { "email-$sender-${command.targetNumber}-${command.content.hashCode()}" }

        val cmdContext = RemoteCommandContext(
            sourceType = RemoteCommandSourceType.EMAIL,
            sourceMessageKey = messageKey,
            commandType = RemoteCommandType.SEND_SMS,
            rawTarget = command.targetNumber,
            rawPayload = command.content,
            requestedSimMode = sendMode,
            rawRequester = sender,
            receivedAt = System.currentTimeMillis(),
        )

        val claimResult = runBlocking {
            RemoteCommandRepository.claimOrGetDuplicate(context, cmdContext)
        }

        if (claimResult is RemoteCommandRepository.ClaimResult.Duplicate) {
            config.appendEmailRemoteLog("抑制重复指令 -> ${command.targetNumber}")
            return
        }

        val commandId = (claimResult as? RemoteCommandRepository.ClaimResult.NewCommand)?.commandId.orEmpty()
        val fingerprint = "email-${sha256(messageKey)}"
        val simSuffix = " · ${SimSendResolver.describeForLog(context, null, sendMode)}"
        config.appendEmailRemoteLog("收到指令 -> ${command.targetNumber}$simSuffix (发件人: $sender)")

        val rulesConfig = ForwardingRulesConfig(context)
        if (rulesConfig.affectsRemoteCommands() && rulesConfig.rules.any { it.enabled }) {
            val decision = ForwardingRuleEngine(rulesConfig.rules).evaluate(
                sender = SOURCE_EMAIL,
                body = "${command.targetNumber} ${command.content}",
                subscriptionId = -1,
                channelCandidates = emptySet(),
                simSlotIndex = null,
            )
            if (decision.matchedRules.isEmpty()) {
                if (commandId.isNotBlank()) {
                    RemoteCommandRepository.recordAuthorization(context, commandId, authorized = false, reason = "RULE_BLOCKED")
                }
                config.appendEmailRemoteLog("规则阻止执行 -> ${command.targetNumber}$simSuffix")
                return
            }
        }

        if (commandId.isNotBlank()) {
            RemoteCommandRepository.recordAuthorization(context, commandId, authorized = true, reason = "EMAIL_AUTHORIZED")
        }

        RemoteSmsCommandWorker.enqueue(
            context = context,
            target = command.targetNumber,
            content = command.content,
            subId = -1,
            uniqueId = fingerprint,
            sendMode = sendMode,
            requester = sender,
            source = SOURCE_EMAIL,
            commandId = commandId,
        )
    }

    private fun extractHeader(text: String, name: String): String {
        val pattern = "(?i)^$name:\\s*(.+)$".toRegex(RegexOption.MULTILINE)
        return pattern.find(text)?.groupValues?.getOrNull(1)?.trim().orEmpty()
    }

    private fun createSocket(host: String, port: Int, useSsl: Boolean): Socket {
        return if (useSsl) {
            (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(host, port).apply {
                (this as? SSLSocket)?.startHandshake()
            }
        } else {
            Socket(host, port)
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
