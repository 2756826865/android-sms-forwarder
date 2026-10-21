package org.fossify.messages.forwarding

import android.content.Context
import androidx.work.*
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Resume only a persisted explicit retry/repair intent; never re-route an old SMS. */
internal object ForwardingResubmission {
    @Synchronized fun resume(context: Context) {
        val history = ForwardingHistoryStore(context)
        val manager = WorkManager.getInstance(context.applicationContext)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        for (payload in history.pendingSubmissions()) {
            if (System.nanoTime() >= deadline) break
            try {
                val id = UUID.fromString(payload.getString("queueRequestId"))
                val name = payload.getString("queueWorkName")
                val recordId = payload.getString("queueRecordId")
                if (history.record(recordId) == null) continue // Explicitly cleared history: do not resurrect it.
                val works = manager.getWorkInfosForUniqueWork(name)
                    .get((deadline - System.nanoTime()).coerceAtLeast(1L), TimeUnit.NANOSECONDS)
                if (works.any { it.id == id }) {
                    history.bindQueueSnapshot(recordId, payload, payload.optString("queueReplacesRequestId").ifBlank { null })
                    if (history.retryPayload(recordId)?.optString("queueRequestId") == id.toString())
                        history.settleSubmission(id.toString())
                    continue
                }
                if (works.any { !it.state.isFinished }) continue
                val input = Data.fromByteArray(Base64.getDecoder().decode(payload.getString("queueData")))
                check(input.getString("history_record_id") == recordId)
                val request = OneTimeWorkRequestBuilder<MultiChannelForwardWorker>()
                    .setId(id).setInputData(input).addTag("forwarding-history:$recordId")
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                val channel = input.getString("target_channel").orEmpty()
                if (channel in ForwardingChannels.networkChannels) request.setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                val delay = (payload.getLong("queueNotBefore") - System.currentTimeMillis()).coerceAtLeast(0)
                if (delay > 0) request.setInitialDelay(delay, TimeUnit.MILLISECONDS)
                else request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                ForwardingWorkScheduler.enqueue(context, name, request.build(), recordId,
                    input = input, retryPayload = payload, notBefore = payload.getLong("queueNotBefore"),
                    repairAllowed = payload.optBoolean("queueRepairAllowed"),
                    replacesRequestId = payload.optString("queueReplacesRequestId").ifBlank { null }, reservedRequest = true)
            } catch (error: CancellationException) { throw error }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
            catch (_: Exception) { /* No evidence that submission failed. Preserve the same request for recovery. */ }
        }
    }
}
