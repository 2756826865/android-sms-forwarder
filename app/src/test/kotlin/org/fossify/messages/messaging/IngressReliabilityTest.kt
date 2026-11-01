package org.fossify.messages.messaging

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class IngressReliabilityTest {
    @Test
    fun saturatedExecutorPersistsBeforeFinishing() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, ArrayBlockingQueue(1))
        try {
            executor.execute {
                started.countDown()
                release.await()
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            executor.execute {}
            val order = mutableListOf<String>()
            DurableIngressHandoff.dispatch(
                executor,
                { fail("full queue must not admit processing") },
                { order.add("persist") },
                { order.add("finish") },
            )
            assertEquals(listOf("persist", "finish"), order)
        } finally {
            release.countDown()
            executor.shutdown()
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) executor.shutdownNow()
        }
    }

    @Test
    fun processingFailureRetainsARecoveryCopy() {
        val order = mutableListOf<String>()
        try {
            DurableIngressHandoff.dispatch(
                java.util.concurrent.Executor { it.run() },
                { error("database unavailable") },
                { order.add("persist") },
                { order.add("finish") },
            )
            fail("processing exception expected")
        } catch (_: IllegalStateException) {}
        assertEquals(listOf("persist", "finish"), order)
    }

    @Test
    fun successfulHandoffReleasesBroadcastBeforeSlowProcessing() {
        val order = mutableListOf<String>()
        DurableIngressHandoff.dispatch(
            java.util.concurrent.Executor { it.run() },
            { order.add("journal") },
            { order.add("persist") },
            { order.add("finish") },
        )
        assertEquals(listOf("persist", "finish", "journal"), order)
    }

    @Test
    fun recoveryCanFinishAfterTheBroadcastBudgetExpires() {
        assertFalse(RecoveryExecutionPolicy.mayContinue(false, 8000, 8000))
        assertTrue(RecoveryExecutionPolicy.mayContinue(false, 7999, 8000))
        assertTrue(RecoveryExecutionPolicy.mayContinue(true, 120000, 8000))
    }

    @Test
    fun serviceAndRemoteStateAreNotRoutingChanges() {
        for (key in
            listOf(
                "keep_alive_service_enabled",
                "root_enhancement_enabled",
                "last_status",
                "telegram_remote_status",
                "telegram_remote_send_sim_mode",
            )) {
            assertFalse(key, RoutingDigestPolicy.includes("multi_channel_forwarding", key))
        }
    }

    @Test
    fun realDestinationAndRenderingChangesAreCovered() {
        for (key in
            listOf(
                "channel_instances",
                "telegram_chat_id",
                "sms_direct_phone",
                "custom_template",
            )) {
            assertTrue(key, RoutingDigestPolicy.includes("multi_channel_forwarding", key))
        }
        assertTrue(RoutingDigestPolicy.includes("forwarding_rules", "rules"))
        assertTrue(RoutingDigestPolicy.includes("pushplus_forwarding", "include_sender"))
    }

    @Test
    fun cleanupProtectsOriginalTargetOwnersAndPendingRequestSnapshots() {
        assertTrue(
            IngressRetentionPolicy.referenced(listOf("sms-old"), setOf("sms-old-channel-target"))
        )
        assertFalse(IngressRetentionPolicy.referenced(listOf("sms-1"), setOf("sms-12")))
        assertFalse(IngressRetentionPolicy.canExpireSnapshot("uuid", setOf("uuid")))
        assertTrue(IngressRetentionPolicy.canExpireSnapshot("expired", setOf("uuid")))
    }

    @Test
    fun rejectedPersistenceInsideAnInlineExecutorDoesNotFinishTwice() {
        var finished = 0
        var persisted = 0
        try {
            DurableIngressHandoff.dispatch(
                java.util.concurrent.Executor { it.run() },
                { error("stage failure") },
                {
                    persisted++
                    throw java.util.concurrent.RejectedExecutionException("storage unavailable")
                },
                { finished++ },
            )
            fail("persistence failure must remain visible")
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            assertEquals(1, persisted)
            assertEquals(1, finished)
        }
    }
}
