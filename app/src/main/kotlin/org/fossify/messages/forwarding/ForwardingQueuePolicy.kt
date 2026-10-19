package org.fossify.messages.forwarding

/** Read-only interpretation. Scheduling state is not proof of a provider accepting a message. */
internal object ForwardingQueuePolicy {
    fun canUpdateSubmission(status: String, attempts: Int) = status == "queued" && attempts == 0

    fun canRetryAfterLookup(works: List<ForwardingWorkState>): Boolean =
        works.isNotEmpty() && works.all { it.state in setOf("FAILED", "CANCELLED") }

    fun uniqueName(workId: String, channel: String): String =
        if (workId.startsWith("fallback-")) workId else "multi-forward-$workId-$channel"

    fun describe(state: String?, attempts: Int, nextRunAt: Long, now: Long,
                 needsNetwork: Boolean, networkReady: Boolean?): String = when (state) {
        null -> "未找到对应任务：可能尚未提交完成或记录已被系统清理；不能据此判断短信是否送达"
        "RUNNING" -> "任务正在执行；请等待发送结果"
        "BLOCKED" -> "等待前置任务完成"
        "SUCCEEDED" -> "调度任务已结束；实际发送结果请以转发记录为准"
        "FAILED" -> "调度任务已失败；请检查转发记录，不会自动重新投递"
        "CANCELLED" -> "调度任务已取消；不会自动重新投递"
        "ENQUEUED" -> buildList {
            if (nextRunAt != Long.MAX_VALUE && nextRunAt > now) add("尚未到执行时间（延迟或退避重试）")
            if (needsNetwork && networkReady == false) add("当前网络未满足系统联网条件，可能等待网络")
            if (needsNetwork && networkReady == null) add("无法读取当前网络状态")
            if (attempts > 0) add("任务曾启动，目前等待重试或重新调度")
            if (isEmpty()) add("等待系统调度；并不代表正在发送")
        }.joinToString("；")
        else -> "任务状态暂不可识别，请稍后刷新"
    }
}
