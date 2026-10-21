package org.fossify.messages.forwarding

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import androidx.work.Data
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executor
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/** One send owner: this only submits/observes WorkManager, never sends or replays a request. */
internal object ForwardingWorkScheduler {
    fun enqueue(context: Context, name: String, request: OneTimeWorkRequest, recordId: String,
                input: Data? = null, retryPayload: JSONObject? = null,
                notBefore: Long = 0L, repairAllowed: Boolean = false,
                replacesRequestId: String? = null,
                retryOf: ForwardingHistoryRecord? = null, repairOf: ForwardingHistoryRecord? = null,
                reservedRequest: Boolean = false,
                observerExecutor: Executor = Dispatchers.IO.asExecutor()) {
        val history = ForwardingHistoryStore(context.applicationContext)
        try {
            // A duplicate event cannot start a new request for an already-bound delivery.
            // An explicitly reserved repair is the sole exception.
            if (!reservedRequest && retryOf == null && repairOf == null && replacesRequestId == null && history.retryPayload(recordId) != null) return
            // Stage in memory only. KEEP can ignore this request: it must never
            // replace the payload belonging to the older request that actually runs.
            val snapshot = if (input == null || retryPayload == null) null else JSONObject(retryPayload.toString())
                .put("queueSnapshotVersion", 1).put("queueRequestId", request.id.toString())
                .put("queueRepairAllowed", repairAllowed).put("queueWorkName", name)
                .put("queueNotBefore", notBefore).put("queueRecordId", recordId)
                .put("queueReplacesRequestId", replacesRequestId.orEmpty())
                .put("queueData", Base64.getEncoder().encodeToString(input.toByteArray()))
            val parent = retryOf ?: repairOf
            if (parent != null) {
                check(snapshot != null)
                if (!history.reserveSubmission(parent, if (retryOf != null) "manual" else "repair", recordId, snapshot)) {
                    ForwardingResubmission.resume(context)
                    return
                }
            }
            val manager = WorkManager.getInstance(context.applicationContext)
            val result = manager
                .enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).result
            // The worker may already be running; guarded history updates preserve its newer state.
            ForwardingSubmissionObserver.observe(result, observerExecutor,
                onAccepted = { recordOutcome(manager, history, name, request.id.toString(), recordId, snapshot, replacesRequestId, true) },
                onRejected = { recordOutcome(manager, history, name, request.id.toString(), recordId, snapshot, replacesRequestId, false) },
                onRecordingError = { Log.w("ForwardingScheduler", "unable to persist enqueue result") })
        } catch (error: Exception) {
            recordFailure(history, recordId, error)
        }
    }

    private fun recordOutcome(manager: WorkManager, history: ForwardingHistoryStore, name: String,
                              requestId: String, recordId: String, snapshot: JSONObject?, replacesRequestId: String?, accepted: Boolean) {
        val works = try {
            manager.getWorkInfosForUniqueWork(name).get(5, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            history.markEnqueueFailed(recordId)
            return
        } catch (_: Exception) {
            history.markEnqueueFailed(recordId)
            return
        }
        if (works.any { it.id.toString() == requestId }) {
            // Even a failed Operation can have committed its WorkSpec already.
            // Bind only this verified UUID and never replace an existing snapshot.
            if (snapshot != null) {
                history.bindQueueSnapshot(recordId, snapshot, replacesRequestId)
                if (history.retryPayload(recordId)?.optString("queueRequestId") != requestId) return
            }
            history.settleSubmission(requestId)
            if (accepted) history.markEnqueued(recordId) else history.markEnqueueFailed(recordId)
        } else if (works.isEmpty()) {
            history.markEnqueueFailed(recordId)
        }
        // Otherwise KEEP retained another owner. Its history and snapshot win.
    }

    private fun recordFailure(history: ForwardingHistoryStore, recordId: String, error: Exception) {
        // Scheduler errors may contain WorkData: do not log the exception/body/credentials.
        Log.w("ForwardingScheduler", "enqueue observation failed: ${error.javaClass.simpleName}")
        runCatching { history.markEnqueueFailed(recordId) }
            .onFailure { Log.w("ForwardingScheduler", "unable to persist enqueue failure") }
    }
}
