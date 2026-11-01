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

    // Framework/OEM boundary: retain durable state and report unclassified platform failures.
    fun dispatch(context: Context, source: Intent, pending: BroadcastReceiver.PendingResult) {
        val app = context.applicationContext
        val copy =
            Intent(source)
                .putExtra(SmsIngressSpool.EXTRA_ID, java.util.UUID.randomUUID().toString())
                .putExtra(SmsIngressSpool.EXTRA_RECEIVED_AT, System.currentTimeMillis())
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)
        fun finish() { if (finished.compareAndSet(false, true)) pending.finish() }
        var handedOff = false
        val persist = {
            runCatching {
                SmsIngressSpool(app).save(copy)
                handedOff = true
            }
                .onFailure {
                    Log.e("SmsIngress", "emergency persistence failed: ${it.javaClass.simpleName}")
                    val acceptedByService = runCatching { IncomingSmsService.enqueue(app, copy) }.getOrDefault(false)
                    handedOff = acceptedByService
                    if (!acceptedByService) {
                        app.getSharedPreferences("sms_ingress_spool_status", Context.MODE_PRIVATE).edit()
                            .putString("error", "storage_and_service_handoff_failed").apply()
                        Log.w("SmsIngress", "storage and service unavailable; attempting protected Journal admission")
                    }
                }
            Unit
        }
        val accepted =
            DurableIngressHandoff.dispatch(
                java.util.concurrent.Executor { task ->
                    if (handedOff) executor.execute(task) else emergencyExecutor.execute(task)
                },
                process = {
                    runCatching {
                        val staged = try {
                            SmsIngressJournal(app).stage(copy)
                        } finally {
                            finish()
                        }
                        SmsIngressSpool(app).remove(copy)
                        runCatching { SmsKeepAliveService.ensureStarted(app) }
                        if (!IncomingSmsService.enqueue(app, staged)) {
                            try {
                                fallbackExecutor.execute {
                                    runCatching { IncomingSmsService.processMinimal(app, staged) }
                                }
                            } catch (_: java.util.concurrent.RejectedExecutionException) {
                                Log.w("SmsIngress", "fallback busy; journal retained")
                            }
                        }
                    }
                        .onFailure {
                            Log.w(
                                "SmsIngress",
                                "staged processing deferred: ${it.javaClass.simpleName}",
                            )
                        }
                    runCatching { SmsRecoveryWorker.enqueueNow(app) }
                },
                persist = persist,
                finish = { if (handedOff) finish() },
            )
        if (!accepted) {
            finish()
            Log.w("SmsIngress", if (handedOff) "processing queue full; recovery handoff retained"
                else "emergency admission full; no durable handoff confirmed")
        }
        runCatching { SmsRecoveryWorker.enqueueNow(app) }
    }
}
