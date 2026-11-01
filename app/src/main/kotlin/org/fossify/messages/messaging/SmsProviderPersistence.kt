package org.fossify.messages.messaging

import android.content.Context
import android.provider.Telephony
import android.util.Log
import org.fossify.messages.extensions.insertNewSMS

/** Reconcile uncertain Provider writes before allowing another insert. */
internal object SmsProviderPersistence {
    private const val TAG = "SmsProviderPersistence"
    private const val PROVIDER_ATTEMPTS = 3
    private const val PROVIDER_RETRY_DELAY_MS = 500L

    @Suppress(
        "LongParameterList",
        "TooGenericExceptionCaught",
    ) // Provider boundary and legacy call contract.
    fun persistWithRetry(
        context: Context,
        address: String,
        subject: String,
        body: String,
        receivedAt: Long,
        sentAt: Long,
        threadId: Long,
        subscriptionId: Int,
        eventKey: String? = null,
    ): Long {
        val journal = SmsIngressJournal(context)
        val receipt = eventKey?.let(journal::receipt)
        if (receipt != null && receipt.providerId > 0) return receipt.providerId
        if (receipt?.attempted == true) {
            val found =
                findProviderReceipt(
                    context,
                    address,
                    body,
                    dateSent = sentAt,
                    receivedAt = receivedAt,
                    sub = subscriptionId,
                )
            if (found > 0) {
                journal.providerResult(receipt.key, found)
                return found
            }
            journal.reason(receipt.key, "provider_result_uncertain")
            error("provider_result_uncertain")
        }
        var lastError: Throwable? = null
        repeat(PROVIDER_ATTEMPTS) { attempt ->
            try {
                if (!SmsRoleDiagnostics.observe(context, "provider-attempt:${attempt + 1}"))
                    return -1L
                eventKey?.let {
                    journal.providerAttempt(it)
                    journal.phase(it, "provider_started")
                }
                val inserted =
                    context.insertNewSMS(
                        address = address,
                        subject = subject,
                        body = body,
                        date = receivedAt,
                        dateSent = sentAt,
                        read = 0,
                        threadId = threadId,
                        type = Telephony.Sms.MESSAGE_TYPE_INBOX,
                        subscriptionId = subscriptionId,
                    )
                eventKey?.let {
                    journal.providerResult(it, inserted)
                    journal.phase(it, "provider_committed")
                }
                return inserted
            } catch (error: Throwable) {
                if (eventKey != null) {
                    journal.reason(eventKey, "provider_result_uncertain")
                    throw error
                }
                lastError = error
                Log.e(TAG, "SMS provider insert attempt ${attempt + 1} failed", error)
                if (attempt + 1 < PROVIDER_ATTEMPTS) Thread.sleep(PROVIDER_RETRY_DELAY_MS)
            }
        }
        lastError?.let { throw it }
        error("短信 Provider 写入失败")
    }

    fun findProviderReceipt(
        context: Context,
        address: String,
        body: String,
        dateSent: Long,
        receivedAt: Long,
        sub: Int,
    ): Long {
        return context.contentResolver
            .query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "address=? AND body=? AND date_sent=? AND date=? AND type=? AND (sub_id=? OR CAST(? AS INTEGER)<0)",
                arrayOf(
                    address,
                    body,
                    dateSent.toString(),
                    receivedAt.toString(),
                    Telephony.Sms.MESSAGE_TYPE_INBOX.toString(),
                    sub.toString(),
                    sub.toString(),
                ),
                null,
            )
            ?.use {
                if (it.count == 1 && it.moveToFirst()) it.getLong(0) else -1L
            } ?: -1L
    }
}
