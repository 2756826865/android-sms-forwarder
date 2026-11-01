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

    // Framework/OEM boundary: retain durable state and report unclassified platform failures.
    fun dispatch(context: Context, source: Intent, pending: BroadcastReceiver.PendingResult) {
        val app = context.applicationContext
        val copy =
            Intent(source)
                .putExtra(SmsIngressSpool.EXTRA_ID, java.util.UUID.randomUUID().toString())
                .putExtra(SmsIngressSpool.EXTRA_RECEIVED_AT, System.currentTimeMillis())
        val persist = {
            runCatching {
                SmsIngressSpool(app).save(copy)
            }
                .onFailure {
                    Log.e("SmsIngress", "emergency persistence failed: ${it.javaClass.simpleName}")
                    val acceptedByService = runCatching { IncomingSmsService.enqueue(app, copy) }.getOrDefault(false)
                    if (!acceptedByService) {
                        app.getSharedPreferences("sms_ingress_spool_status", Context.MODE_PRIVATE).edit()
                            .putString("error", "storage_and_service_handoff_failed").apply()
                        Log.e("SmsIngress", "no durable handoff confirmed; storage and foreground service unavailable")
                    }
                }
            Unit
        }
        val accepted =
            DurableIngressHandoff.dispatch(
                executor,
                process = {
                    runCatching {
                        val staged = SmsIngressJournal(app).stage(copy)
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
                finish = pending::finish,
            )
        if (!accepted) Log.w("SmsIngress", "processing queue full; encrypted receipt retained")
        runCatching { SmsRecoveryWorker.enqueueNow(app) }
    }
}
