package org.fossify.messages.forwarding

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.CancellationException

internal object SmsDirectBillingPolicy {
    fun workerSource(workId: String, receivedAt: Long, fallback: String): String {
        // A provider-write failure has no SMS row ID. Its PDU fingerprint must
        // survive duplicate broadcasts and route suffixes without using wall time.
        Regex("^sms-minimal-[a-f0-9]{64}(?=-|$)").find(workId)?.value?.let {
            return "worker:$it"
        }
        // Rule, instance and group suffixes are routes for the same received SMS.
        val original = Regex("^sms-[0-9]+(?=-|$)").find(workId)?.value
            ?: workId.ifBlank { fallback }
        require(original.isNotBlank()) { "短信直发缺少原始任务标识" }
        return "worker:$original:$receivedAt"
    }

    fun degradedWorkId(fingerprint: String): String {
        require(fingerprint.matches(Regex("[a-f0-9]{64}"))) { "降级短信缺少有效原始标识" }
        return "sms-minimal-$fingerprint"
    }

    fun isUnidentifiedLegacyDegradedSource(source: String): Boolean =
        source.startsWith("worker:sms-minimal-") &&
            !source.matches(Regex("worker:sms-minimal-[a-f0-9]{64}"))

    fun outboxSource(taskId: String, deliveryId: String): String =
        if (deliveryId.isNotBlank()) "delivery:$deliveryId" else "outbox:$taskId"

    fun canRetryManually(isSmsDirect: Boolean, attempts: Int): Boolean = !isSmsDirect || attempts == 0

    fun canResumePreparation(phase: String): Boolean = phase in setOf("preparing", "pre_submit_failed")

    fun key(source: String, destination: String): String {
        require(source.isNotBlank() && destination.isNotBlank()) { "短信直发缺少稳定任务标识或目标" }
        val bytes = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use { out ->
                listOf(source, normalizePhone(destination)).forEach {
                    val value = it.toByteArray(Charsets.UTF_8)
                    out.writeInt(value.size)
                    out.write(value)
                }
            }
        }.toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    fun isEcho(sender: String, destination: String): Boolean =
        sender.isNotBlank() && normalizePhone(sender) == normalizePhone(destination)

    private fun normalizePhone(value: String): String {
        val compact = value.trim().filterNot { it.isWhitespace() || it in "-()" }
        val local = when {
            compact.startsWith("+86") -> compact.removePrefix("+86")
            compact.startsWith("0086") -> compact.removePrefix("0086")
            compact.length == 13 && compact.startsWith("86") -> compact.removePrefix("86")
            else -> compact
        }
        return if (local.length == 11 && local.startsWith("1") && local.all { it in '0'..'9' }) local else compact
    }

    /** Claim must commit durably before any billable action, and must never be released. */
    fun sendOnce(key: String, claim: (String) -> Boolean, send: () -> Unit) {
        if (!claim(key)) throw SmsDirectOutcomeUnknown("该短信直发任务已开始过发送；为避免重复扣费，已阻止再次提交")
        try {
            send()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw SmsDirectOutcomeUnknown("短信发送未能确认，可能已提交；已停止自动重发，请先核对收件端", error)
        }
    }
}

internal class SmsDirectOutcomeUnknown(message: String, cause: Throwable? = null) : IOException(message, cause)

internal class SmsDirectPreSubmitFailure(cause: Throwable) : IllegalStateException("短信发送前准备失败，尚未提交，可检查配置后手动重试", cause)
