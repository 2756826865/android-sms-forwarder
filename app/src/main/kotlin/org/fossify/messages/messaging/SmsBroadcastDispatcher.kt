package org.fossify.messages.messaging

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.fossify.messages.services.IncomingSmsService
import org.fossify.messages.services.SmsKeepAliveService

/** No database or fallback processing runs on the broadcast main thread. */
internal object SmsBroadcastDispatcher {
    private const val ADMISSION_THREADS = 4
    private const val HANDOFF_CAPACITY = 64
    private const val FALLBACK_CAPACITY = 32
    private const val THREAD_KEEP_ALIVE_SECONDS = 30L
    private val executor =
        ThreadPoolExecutor(
            2,
            2,
            THREAD_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(HANDOFF_CAPACITY),
            java.util.concurrent.ThreadFactory { task -> Thread(task, "sms-ingress-handoff") },
        )
    private val fallbackExecutor =
        ThreadPoolExecutor(
            1,
            1,
            THREAD_KEEP_ALIVE_SECONDS,
            TimeUnit.SECONDS,
            ArrayBlockingQueue(FALLBACK_CAPACITY),
            java.util.concurrent.ThreadFactory { task -> Thread(task, "sms-ingress-fallback") },
        )

    // Reserve immediate admission for a failed spool/service handoff. No queued broadcast tokens.
    private val emergencyExecutor = ThreadPoolExecutor(
        2, 2, THREAD_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
        java.util.concurrent.SynchronousQueue(),
        java.util.concurrent.ThreadFactory { task -> Thread(task, "sms-ingress-emergency") },
    )

    // One emergency thread prevents ordinary broadcast-main-thread disk IO when
    // the bounded pools overflow. Never create unbounded overflow threads.
    private val overflowAdmissionBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private val admissionExecutor = ThreadPoolExecutor(
        2, ADMISSION_THREADS, THREAD_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
        ArrayBlockingQueue<Runnable>(HANDOFF_CAPACITY),
        java.util.concurrent.ThreadFactory { task -> Thread(task, "sms-ingress-admission") },
    )

    // Framework/OEM boundary: retain durable state and report unclassified platform failures.
    fun dispatch(context: Context, source: Intent, pending: BroadcastReceiver.PendingResult) {
        val app = context.applicationContext
        val copy =
            Intent(source)
                .putExtra(SmsIngressSpool.EXTRA_ID, java.util.UUID.randomUUID().toString())
                .putExtra(SmsIngressSpool.EXTRA_RECEIVED_AT, System.currentTimeMillis())
        val admission = Runnable { dispatchPersisted(app, copy, pending) }
        try {
            admissionExecutor.execute(admission)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            try { emergencyExecutor.execute(admission) }
            catch (_: java.util.concurrent.RejectedExecutionException) {
                // Keep the PendingResult alive on a reserved overflow thread.
                // If even that slot is busy, inline admission is the last remaining
                // durable alternative; report this platform saturation explicitly.
                if (overflowAdmissionBusy.compareAndSet(false, true)) {
                    try {
                        Thread({
                            try { admission.run() }
                            finally { overflowAdmissionBusy.set(false) }
                        }, "sms-ingress-overflow").start()
                    } catch (error: Exception) {
                        overflowAdmissionBusy.set(false)
                        Log.e("SmsIngress", "overflow thread unavailable; inline admission", error)
                        admission.run()
                    }
                } else {
                    Log.e("SmsIngress", "all admission threads saturated; inline durable fallback")
                    admission.run()
                }
            }
        }
    }

    private fun dispatchPersisted(app: Context, copy: Intent, pending: BroadcastReceiver.PendingResult) {
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish() { if (finished.compareAndSet(false, true)) pending.finish() }
        var spoolSaved = false
        var journalSaved = false
        try {
            val accepted = DurableIngressHandoff.dispatch(
                executor = java.util.concurrent.Executor { task ->
                    if (spoolSaved) executor.execute(task) else emergencyExecutor.execute(task)
                },
                persist = {
                    spoolSaved = DurableIngressHandoff.persistWithFallback(
                        primary = { SmsIngressSpool(app).save(copy) },
                        fallback = {
                            // Must be committed before the PendingResult is released.
                            SmsIngressJournal(app).stage(copy)
                            journalSaved = true
                        },
                    )
                },
                finish = { finish() },
                process = {
                    try {
                        val staged = SmsIngressJournal(app).stage(copy)
                        if (spoolSaved) SmsIngressSpool(app).remove(copy)
                        runCatching { SmsKeepAliveService.ensureStarted(app) }
                        if (!IncomingSmsService.enqueue(app, staged)) {
                            try {
                                fallbackExecutor.execute {
                                    runCatching { IncomingSmsService.processMinimal(app, staged) }
                                }
                            } catch (_: java.util.concurrent.RejectedExecutionException) {
                                Log.w("SmsIngress", "fallback busy; committed Journal retained")
                            }
                        }
                    } catch (error: Exception) {
                        Log.w("SmsIngress", "durable intake processing deferred", error)
                    } finally {
                        runCatching { SmsRecoveryWorker.enqueueNow(app) }
                    }
                },
            )
            if (!accepted) Log.w("SmsIngress", "processing queue saturated; durable intake retained")
        } catch (error: Throwable) {
            Log.e("SmsIngress", "durable admission failed", error)
            // A Service start request is only best effort, never a persistence ACK.
            if (!spoolSaved && !journalSaved) {
                runCatching { IncomingSmsService.enqueue(app, copy) }
                runCatching {
                    app.getSharedPreferences("sms_ingress_spool_status", Context.MODE_PRIVATE)
                        .edit().putString("error", "spool_and_journal_unavailable").apply()
                }
            }
        } finally {
            finish()
            if (spoolSaved || journalSaved) runCatching { SmsRecoveryWorker.enqueueNow(app) }
        }
    }
}
