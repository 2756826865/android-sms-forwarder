package org.fossify.messages.helpers

import android.content.Context
import android.os.SystemClock
import android.provider.Telephony
import android.util.Log
import org.fossify.messages.extensions.conversationsDB
import org.fossify.messages.extensions.messagesDB
import org.fossify.messages.models.Conversation
import org.fossify.messages.models.Message
import org.fossify.messages.models.SyncMessageMetadata

/** Optional, bounded and content-free. A diagnostic error must never change sync success. */
class SmsSyncDiagnostics private constructor(val traceId: String) {
    data class Sample(val id: Long, val threadId: Long, val dateMs: Long)

    @Volatile private var samples: List<Sample> = emptyList()
    @Volatile private var businessSamples: List<Sample> = emptyList()
    private val createdAt = SystemClock.elapsedRealtime()
    private var batchNumber = 0
    private val logCount = java.util.concurrent.atomic.AtomicInteger()
    private var businessNewest: Sample? = null
    private var businessOldest: Sample? = null

    fun log(event: String, fields: String) {
        val line = logCount.incrementAndGet()
        if (line > 800) {
            if (line == 801) Log.w("SmsSyncDiag", "trace=$traceId event=diagnostic_truncated limit=800")
            return
        }
        Log.w("SmsSyncDiag", "trace=$traceId event=$event $fields")
    }

    fun optional(stage: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Exception) {
            if (error is java.util.concurrent.CancellationException) throw error
            log("diagnostic_failed", "stage=$stage kind=${error.javaClass.simpleName}")
        }
    }

    private fun targets(): List<Sample> = (samples + businessSamples).distinctBy { it.id }.take(SAMPLE_LIMIT * 2)

    fun provider(context: Context) {
        optional("provider_count") {
            val cursor = context.contentResolver.query(Telephony.Sms.CONTENT_URI, arrayOf("COUNT(*) AS total"), null, null, null)
            if (cursor == null) log("provider_count", "status=null_cursor") else cursor.use {
                if (it.moveToFirst() && !it.isNull(0)) log("provider_count", "status=ok count=${it.getLong(0)}")
                else log("provider_count", "status=empty_result")
            }
        }
        optional("provider_metadata") {
            val cursor = context.contentResolver.query(
                Telephony.Sms.CONTENT_URI, arrayOf("_id", "thread_id", "date"), null, null, "date DESC"
            )
            if (cursor == null) {
                log("provider_metadata", "status=null_cursor")
                return@optional
            }
            cursor.use { c ->
                val count = c.count
                var traversed = 0
                var newest: Sample? = null
                var oldest: Sample? = null
                val recent = ArrayList<Sample>(SAMPLE_LIMIT)
                while (c.moveToNext()) {
                    traversed++
                    val row = Sample(c.getLong(0), c.getLong(1), c.getLong(2))
                    if (newest == null || row.dateMs > newest.dateMs) newest = row
                    if (oldest == null || row.dateMs < oldest.dateMs) oldest = row
                    if (recent.size < SAMPLE_LIMIT) recent.add(row)
                }
                samples = recent.toList()
                log("provider_metadata", "status=ok count=$count traversed=$traversed newestId=${newest?.id} newestMs=${newest?.dateMs} oldestId=${oldest?.id} oldestMs=${oldest?.dateMs}")
                recent.forEach { log("target", "id=${it.id} threadId=${it.threadId} dateMs=${it.dateMs}") }
            }
        }
    }

    fun businessRow(id: Long, threadId: Long, rawDate: Long) {
        val row = Sample(id, threadId, rawDate)
        if (businessSamples.size < SAMPLE_LIMIT) businessSamples = businessSamples + row
        if (businessNewest == null || rawDate > businessNewest!!.dateMs) businessNewest = row
        if (businessOldest == null || rawDate < businessOldest!!.dateMs) businessOldest = row
        if (samples.any { it.id == id }) log("target_business", "id=$id threadId=$threadId dateMs=$rawDate valid=${id > 0 && threadId > 0}")
    }

    fun businessEnd(count: Int, traversed: Int, valid: Int, failed: Int) {
        log("provider_business", "status=ok count=$count traversed=$traversed valid=$valid invalid=$failed newestId=${businessNewest?.id} newestMs=${businessNewest?.dateMs} oldestId=${businessOldest?.id} oldestMs=${businessOldest?.dateMs}")
    }

    private fun metadata(context: Context, ids: List<Long>): List<SyncMessageMetadata> =
        ids.chunked(400).flatMap { context.messagesDB.getHistorySyncMetadata(it) }

    fun beforeBatch(context: Context, batch: List<Message>) = optional("before_batch") {
        val expected = batch.associateBy { it.id }
        val existing = metadata(context, expected.keys.toList())
        val otherType = existing.count { it.isMMS != expected.getValue(it.id).isMMS }
        log("batch_before", "nextBatch=${batchNumber + 1} size=${batch.size} existing=${existing.size} otherType=$otherType scheduled=${existing.count { it.isScheduled }} recycled=${existing.count { it.recycled }}")
    }

    fun afterBatch(context: Context, batch: List<Message>) = optional("after_batch") {
        batchNumber++
        val expected = batch.associateBy { it.id }
        val stored = metadata(context, expected.keys.toList())
        val matched = stored.count { row ->
            val message = expected.getValue(row.id)
            row.threadId == message.threadId && row.date == message.date && row.isMMS == message.isMMS && row.isScheduled == message.isScheduled
        }
        log("batch_verified", "batch=$batchNumber size=${batch.size} unique=${expected.size} found=${stored.size} matched=$matched recycled=${stored.count { it.recycled }}")
        val counts = context.messagesDB.getHistorySyncCounts()
        log("batch_local_count", "batch=$batchNumber sms=${counts.filterNot { it.isMMS }.sumOf { it.count }} mms=${counts.filter { it.isMMS }.sumOf { it.count }} scheduled=${counts.filter { it.isScheduled }.sumOf { it.count }}")
    }

    fun database(context: Context, stage: String) = optional("database_$stage") {
        val counts = context.messagesDB.getHistorySyncCounts()
        for (mms in listOf(false, true)) for (scheduled in listOf(false, true)) {
            log("local_count", "stage=$stage mms=$mms scheduled=$scheduled count=${counts.firstOrNull { it.isMMS == mms && it.isScheduled == scheduled }?.count ?: 0L}")
        }
        val stored = metadata(context, targets().map { it.id }).associateBy { it.id }
        targets().forEach { target ->
            val row = stored[target.id]
            val expectedSeconds = if (target.dateMs > 9999999999L) target.dateMs / 1000L else target.dateMs
            log("target_local", "stage=$stage id=${target.id} expectedThread=${target.threadId} expectedDate=$expectedSeconds present=${row != null} threadId=${row?.threadId} date=${row?.date} mms=${row?.isMMS} scheduled=${row?.isScheduled} recycled=${row?.recycled} matchesSms=${row != null && row.threadId == target.threadId && row.date.toLong() == expectedSeconds && !row.isMMS && !row.isScheduled}")
        }
        val conversations = context.conversationsDB.getAllRegular()
        this.conversations("local_$stage", conversations)
    }

    fun threadResolution(threadId: Long, reason: String) {
        if (targets().any { it.threadId == threadId }) log("thread_resolution", "threadId=$threadId reason=$reason")
    }

    fun threadDate(threadId: Long, providerMs: Long, localSeconds: Int?, selectedSeconds: Long) {
        if (targets().any { it.threadId == threadId }) {
            log("thread_date", "threadId=$threadId providerRaw=$providerMs localSeconds=$localSeconds selectedSeconds=$selectedSeconds stage=before_draft")
        }
    }

    fun conversations(stage: String, rows: List<Conversation>, excluded: Set<Long> = emptySet()) = optional("list_$stage") {
        log("list", "stage=$stage count=${rows.size} scope=data_source")
        targets().map { it.threadId }.distinct().forEach { id ->
            val index = rows.indexOfFirst { it.threadId == id }
            val row = rows.getOrNull(index)
            log("target_list", "stage=$stage threadId=$id present=${row != null} index=$index date=${row?.date} archived=${row?.isArchived} scheduled=${row?.isScheduled} reason=${if (id in excluded) "fully_recycled" else if (row == null) "absent_from_source" else "included"} scope=data_source")
        }
    }

    companion object {
        private const val SAMPLE_LIMIT = 8
        @Volatile private var latest: SmsSyncDiagnostics? = null

        /** Explicit manual sync only; no fixed provider IDs and no logging for routine refreshes. */
        fun start(traceId: String, source: String): SmsSyncDiagnostics? {
            if (source != "compose_resync_all" && source != "classic_resync_all") return null
            return SmsSyncDiagnostics(traceId).also { latest = it }
        }

        fun recent(traceId: String? = null): SmsSyncDiagnostics? = latest?.takeIf {
            SystemClock.elapsedRealtime() - it.createdAt < 10 * 60_000L && (traceId == null || it.traceId == traceId)
        }
    }
}
