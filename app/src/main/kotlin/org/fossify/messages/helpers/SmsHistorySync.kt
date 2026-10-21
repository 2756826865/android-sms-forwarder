package org.fossify.messages.helpers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.fossify.commons.models.PhoneNumber
import org.fossify.commons.models.SimpleContact
import org.fossify.messages.extensions.config
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.createConversationFromMessage
import org.fossify.messages.extensions.getConversations
import org.fossify.messages.extensions.getMMS
import org.fossify.messages.extensions.getNameAndPhotoFromPhoneNumber
import org.fossify.messages.extensions.insertOrUpdateConversation
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.models.Message
import org.fossify.messages.models.NamePhoto
import java.util.UUID

/**
 * Local history SMS synchronization only.
 * Strictly reads from system provider and persists into local Room database.
 * Never triggers forwarding, auto-replies, or remote execution.
 */
data class SmsSyncResult(
    val traceId: String,
    val success: Boolean,
    val readCount: Int,
    val parseFailedCount: Int,
    val writtenCount: Int,
    val threadsUpdatedCount: Int,
    val elapsedMs: Long,
    val outcome: String,
)

private val historySyncRunning = java.util.concurrent.atomic.AtomicBoolean(false)

fun Context.syncSmsHistory(source: String, strict: Boolean = false): SmsSyncResult {
    val traceId = UUID.randomUUID().toString().take(8)
    val startTime = System.currentTimeMillis()
    val hasReadSms = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
    val observedDefault = DeviceCompatHelper.isDefaultSmsApp(this)

    Log.i(
        "SmsSync",
        "trace=$traceId event=start source=$source observedDefault=$observedDefault readSmsGranted=$hasReadSms at=$startTime"
    )

    if (!hasReadSms) {
        val elapsed = System.currentTimeMillis() - startTime
        Log.w(
            "SmsSync",
            "trace=$traceId event=end outcome=permission_denied source=$source elapsedMs=$elapsed watermark=unchanged"
        )
        if (strict) throw SecurityException("SMS_PERMISSION_DENIED")
        return SmsSyncResult(traceId, false, 0, 0, 0, 0, elapsed, "permission_denied")
    }

    if (!historySyncRunning.compareAndSet(false, true))
        return SmsSyncResult(traceId, false, 0, 0, 0, 0, 0, "already_running")
    SmsSyncProgress.update(SmsSyncProgress.State(running = true))

    var readCount = 0
    var parseFailedCount = 0
    var writtenCount = 0
    var threadsUpdatedCount = 0

    try {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE,
            Telephony.Sms.STATUS,
            Telephony.Sms.SUBSCRIPTION_ID
        )

        val cursor = contentResolver.query(
            Telephony.Sms.CONTENT_URI,
            projection,
            null,
            null,
            "${Telephony.Sms.DATE} DESC"
        )

        if (cursor == null) {
            val elapsed = System.currentTimeMillis() - startTime
            Log.w(
                "SmsSync",
                "trace=$traceId event=end outcome=null_cursor source=$source elapsedMs=$elapsed watermark=unchanged"
            )
            if (strict) throw IllegalStateException("SMS_NULL_CURSOR")
            return SmsSyncResult(traceId, false, 0, 0, 0, 0, elapsed, "null_cursor")
        }

        val totalSms = cursor.count
        SmsSyncProgress.update(SmsSyncProgress.State(running = true, completed = 0, total = totalSms, failed = 0))

        val contactCache = HashMap<String, NamePhoto>()
        val batch = ArrayList<Message>(500)

        cursor.use { c ->
            val idCol = c.getColumnIndex(Telephony.Sms._ID)
            val threadCol = c.getColumnIndex(Telephony.Sms.THREAD_ID)
            val addrCol = c.getColumnIndex(Telephony.Sms.ADDRESS)
            val bodyCol = c.getColumnIndex(Telephony.Sms.BODY)
            val dateCol = c.getColumnIndex(Telephony.Sms.DATE)
            val readCol = c.getColumnIndex(Telephony.Sms.READ)
            val typeCol = c.getColumnIndex(Telephony.Sms.TYPE)
            val statusCol = c.getColumnIndex(Telephony.Sms.STATUS)
            val subCol = c.getColumnIndex(Telephony.Sms.SUBSCRIPTION_ID)
            val simCol = c.getColumnIndex("sim_id")

            while (c.moveToNext()) {
                val id = if (idCol >= 0) c.getLong(idCol) else 0L
                var threadId = if (threadCol >= 0) c.getLong(threadCol) else 0L
                val address = if (addrCol >= 0) c.getString(addrCol) else null

                if (id <= 0L || threadId <= 0L) {
                    parseFailedCount++
                    continue
                }

                val body = if (bodyCol >= 0) c.getString(bodyCol) ?: "" else ""
                val rawDate = if (dateCol >= 0) c.getLong(dateCol) else 0L
                val date = if (rawDate > 9999999999L) (rawDate / 1000L).toInt() else rawDate.toInt()
                val read = if (readCol >= 0) c.getInt(readCol) == 1 else false
                val type = if (typeCol >= 0) c.getInt(typeCol) else Telephony.Sms.MESSAGE_TYPE_INBOX
                val status = if (statusCol >= 0) c.getInt(statusCol) else Telephony.Sms.STATUS_NONE
                var subscriptionId = if (subCol >= 0) c.getInt(subCol) else SubscriptionManager.INVALID_SUBSCRIPTION_ID
                if (subscriptionId == SubscriptionManager.INVALID_SUBSCRIPTION_ID && simCol >= 0) {
                    subscriptionId = c.getInt(simCol)
                }

                val addrKey = address ?: ""
                val namePhoto = if (addrKey.isNotEmpty()) {
                    contactCache.getOrPut(addrKey) { getNameAndPhotoFromPhoneNumber(addrKey) }
                } else {
                    NamePhoto("", null)
                }

                val participants = if (addrKey.isEmpty()) arrayListOf() else {
                    arrayListOf(
                        SimpleContact(
                            rawId = 0,
                            contactId = 0,
                            name = namePhoto.name,
                            photoUri = namePhoto.photoUri ?: "",
                            phoneNumbers = arrayListOf(PhoneNumber(addrKey, 0, "", addrKey)),
                            birthdays = ArrayList(),
                            anniversaries = ArrayList()
                        )
                    )
                }

                val message = Message(
                    id = id,
                    body = body,
                    type = type,
                    status = status,
                    participants = participants,
                    date = date,
                    read = read,
                    threadId = threadId,
                    isMMS = false,
                    attachment = null,
                    senderPhoneNumber = addrKey,
                    senderName = namePhoto.name,
                    senderPhotoUri = namePhoto.photoUri ?: "",
                    subscriptionId = subscriptionId
                )

                batch.add(message)
                readCount++

                if (batch.size >= 500) {
                    messagesDB.insertMessages(*batch.toTypedArray())
                    writtenCount += batch.size
                    batch.clear()
                    SmsSyncProgress.update(
                        SmsSyncProgress.State(
                            running = true,
                            completed = readCount,
                            total = totalSms,
                            failed = parseFailedCount
                        )
                    )
                }
            }
        }

        if (batch.isNotEmpty()) {
            messagesDB.insertMessages(*batch.toTypedArray())
            writtenCount += batch.size
            batch.clear()
        }

        // 2. Sync MMS messages if any
        val mmsMessages = getMMS(threadId = null, strict = true)
        if (mmsMessages.isNotEmpty()) {
            mmsMessages.chunked(200).forEach { mmsBatch ->
                messagesDB.insertMessages(*mmsBatch.toTypedArray())
                writtenCount += mmsBatch.size
            }
        }

        // 3. Update conversations table
        val conversations = getConversations(threadId = null, strict = true)
        conversations.chunked(200).forEach { convBatch ->
            convBatch.forEach { conv ->
                insertOrUpdateConversation(conv)
                threadsUpdatedCount++
            }
        }

        // 4. Fill any missing conversation summaries for remaining threads
        messagesDB.getLatestMessagesWithoutConversation().forEach { msg ->
            createConversationFromMessage(msg)?.let { conv ->
                insertOrUpdateConversation(conv)
                threadsUpdatedCount++
            }
        }

        check(parseFailedCount == 0) { "SMS_SYNC_PARTIAL_PARSE_FAILURE" }
        if (readCount > 0) config.fullHistorySyncedV2 = true
        val elapsed = System.currentTimeMillis() - startTime
        Log.i(
            "SmsSync",
            "trace=$traceId event=end outcome=success read=$readCount parseFailed=$parseFailedCount added=$writtenCount threadsUpdated=$threadsUpdatedCount elapsedMs=$elapsed watermark=success_only"
        )
        SmsSyncProgress.update(
            SmsSyncProgress.State(
                running = false,
                completed = readCount,
                total = totalSms,
                failed = parseFailedCount
            )
        )
        return SmsSyncResult(
            traceId = traceId,
            success = true,
            readCount = readCount,
            parseFailedCount = parseFailedCount,
            writtenCount = writtenCount,
            threadsUpdatedCount = threadsUpdatedCount,
            elapsedMs = elapsed,
            outcome = "success"
        )
    } catch (error: Exception) {
        if (error is java.util.concurrent.CancellationException) throw error
        val elapsed = System.currentTimeMillis() - startTime
        val outcome = when (error) {
            is SecurityException -> "permission_denied"
            else -> "failed"
        }
        Log.w(
            "SmsSync",
            "trace=$traceId event=end outcome=$outcome kind=${error.javaClass.simpleName} read=$readCount added=$writtenCount elapsedMs=$elapsed watermark=unchanged"
        )
        SmsSyncProgress.update(
            SmsSyncProgress.State(
                running = false,
                completed = readCount,
                total = readCount,
                failed = parseFailedCount + 1
            )
        )
        if (strict) throw error
        return SmsSyncResult(traceId, false, readCount, parseFailedCount, writtenCount, threadsUpdatedCount, elapsed, outcome)
    } finally {
        historySyncRunning.set(false)
        SmsSyncProgress.update(SmsSyncProgress.state.value.copy(running = false))
    }
}
