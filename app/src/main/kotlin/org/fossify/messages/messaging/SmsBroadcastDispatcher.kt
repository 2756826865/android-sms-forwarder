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
    @Suppress("TooGenericExceptionCaught")
    fun dispatch(context: Context, source: Intent, pending: BroadcastReceiver.PendingResult) {
        val app = context.applicationContext
        try {
            executor.execute {
                var fallback: Intent? = null
                try {
                    val staged = SmsIngressJournal(app).stage(source)
                    // Register recovery before requesting a service: a successful request is not a
                    // completed handoff.
                    runCatching { SmsRecoveryWorker.enqueueNow(app) }
                        .onFailure {
                            Log.w(
                                "SmsIngress",
                                "recovery registration failed: ${it.javaClass.simpleName}",
                            )
                        }
                    runCatching { SmsKeepAliveService.ensureStarted(app) }
                    if (!IncomingSmsService.enqueue(app, staged)) fallback = staged
                } catch (error: Exception) {
                    Log.e("SmsIngress", "handoff failed: ${error.javaClass.simpleName}")
                    runCatching { SmsRecoveryWorker.enqueueNow(app) }
                } finally {
                    pending.finish()
                }
                // The durable handoff is complete. Do not hold a broadcast token during database
                // retries.
                fallback?.let { intent ->
                    try {
                        fallbackExecutor.execute {
                            runCatching { IncomingSmsService.processMinimal(app, intent) }
                        }
                    } catch (_: java.util.concurrent.RejectedExecutionException) {
                        Log.w("SmsIngress", "fallback busy; durable receipt retained for recovery")
                    }
                }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            Log.e("SmsIngress", "handoff capacity exhausted")
            pending.finish()
        }
    }
}
