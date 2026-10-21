package org.fossify.messages.forwarding

import android.content.Context
import android.util.Log
import androidx.work.*
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException

/** Bounded reconciliation on existing recovery/UI events. Never re-matches rules or sends directly. */
internal object ForwardingQueueReconciler {
    fun run(context: Context, allowRepair: Boolean, limit: Int = 30) {
        try { reconcile(context, allowRepair, limit) }
        catch (error: CancellationException) { throw error }
        catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        catch (error: Exception) { Log.w("ForwardingQueue", "queue reconciliation unavailable: ${error.javaClass.simpleName}") }
    }

    private fun reconcile(context: Context, allowRepair: Boolean, limit: Int) {
        if (allowRepair) ForwardingResubmission.resume(context)
        val store = ForwardingHistoryStore(context)
        val manager = WorkManager.getInstance(context)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        for (record in store.pendingQueueRecords(limit)) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            try {
                val name = uniqueName(record)
                val works = manager.getWorkInfosForUniqueWork(name).get(remaining, TimeUnit.NANOSECONDS)
                val now = System.currentTimeMillis()
                val claimed = store.queueRepairClaimed(record.recordId)
                val decision = ForwardingReconcilePolicy.decide(record.status, record.attempts,
                    now - record.updatedAt, works.map { ForwardingWorkState(it.state.name, it.runAttemptCount) }, claimed)
                when (decision) {
                    ForwardingReconcilePolicy.Decision.KEEP -> Unit
                    ForwardingReconcilePolicy.Decision.ACTIVE -> {
                        val running = works.any { it.state == WorkInfo.State.RUNNING }
                        val retrying = !running && (record.attempts > 0 || works.any { it.runAttemptCount > 0 })
                        store.observeQueue(record,
                            if (running) "running" else if (retrying) "retry" else "queued",
                            if (running) "系统任务执行中，等待通道结果"
                            else if (works.any { it.state == WorkInfo.State.ENQUEUED }) "等待系统调度（网络条件、延迟或重试）"
                            else "等待前置任务完成")
                    }
                    ForwardingReconcilePolicy.Decision.UNKNOWN -> store.observeQueue(record, "unknown",
                        if (works.isEmpty()) "未找到有效任务，无法确认是否已发送；请核对接收端后再手动重试"
                        else "系统任务已结束但投递结果不完整；为避免重复发送，不自动补发")
                    ForwardingReconcilePolicy.Decision.STOPPED_UNSTARTED -> {
                        val payload = store.retryPayload(record.recordId)
                        // Cancelled requests may represent an intentional user action. Never revive them.
                        val canRepair = ForwardingReconcilePolicy.canRepair(
                            works.map { ForwardingWorkState(it.state.name, it.runAttemptCount) }, record.attempts,
                            now - record.receivedAt, payload?.optInt("queueSnapshotVersion") ?: 0,
                            payload?.optBoolean("queueRepairAllowed") == true,
                            claimed, record.isTest,
                            works.any { it.id.toString() == payload?.optString("queueRequestId") })
                        if (canRepair && !allowRepair) {
                            store.observeQueue(record, "queued", "任务未启动即失败，等待一次安全补偿检查")
                        } else if (canRepair) {
                            val request = runCatching {
                                val data = Data.fromByteArray(Base64.getDecoder().decode(payload!!.getString("queueData")))
                                val channel = data.getString("target_channel").orEmpty()
                                check(data.getString("history_record_id") == record.recordId &&
                                    payload.getString("queueWorkName") == name &&
                                    data.getString("group_execution_plan").isNullOrBlank() &&
                                    !data.getBoolean("is_test", true) && channel in ForwardingChannels.networkChannels)
                                val instanceId = data.getString("target_instance_id").orEmpty()
                                if (instanceId.isNotBlank()) {
                                    val target = MultiForwardConfig(context).getChannelInstanceById(instanceId)
                                    check(target != null && target.enabled && target.channelType == channel && target.hasDispatchConfiguration())
                                } else check(MultiForwardConfig(context).isChannelEnabled(channel))
                                val delay = (payload.getLong("queueNotBefore") - now).coerceAtLeast(0)
                                val builder = OneTimeWorkRequestBuilder<MultiChannelForwardWorker>()
                                    .setInputData(Data.Builder().putAll(data).putBoolean("queue_repair", true).build())
                                    .addTag("forwarding-history:${record.recordId}")
                                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
                                if (delay > 0) builder.setInitialDelay(delay, TimeUnit.MILLISECONDS)
                                else builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                                builder.build()
                            }.getOrNull()
                            if (request == null) {
                                store.observeQueue(record, "unknown", "原目标配置已改变或恢复数据不完整，未自动补发；请核对后手动处理")
                                continue
                            }
                            // Persist the reservation before submitting. A crash cannot cause repeated repairs.
                            ForwardingWorkScheduler.enqueue(context, name, request, record.recordId,
                                    input = Data.Builder().putAll(Data.fromByteArray(Base64.getDecoder().decode(payload!!.getString("queueData"))))
                                        .putBoolean("queue_repair", true).build(),
                                    retryPayload = payload, notBefore = payload.getLong("queueNotBefore"),
                                    replacesRequestId = payload.getString("queueRequestId"), repairOf = record)
                        } else store.observeQueue(record, "failed",
                            "任务未启动即失败或取消；未自动补发，可确认后仅重试此通道")
                    }
                }
            } catch (error: CancellationException) { throw error }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); return }
            catch (error: Exception) {
                // A failed query is not evidence of a missing task. Preserve its state.
                Log.w("ForwardingQueue", "queue check unavailable: ${error.javaClass.simpleName}")
            }
        }
    }

    fun uniqueName(record: ForwardingHistoryRecord): String =
        ForwardingQueuePolicy.uniqueName(record.workId, record.channel)
}
