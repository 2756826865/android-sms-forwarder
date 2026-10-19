package org.fossify.messages.forwarding

import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class SmsDirectBillingPolicyTest {
    @Test fun sameTaskCannotSubmitTwice() {
        val claims = mutableSetOf<String>()
        var sends = 0
        val key = SmsDirectBillingPolicy.key("worker:original", "13800000001")
        SmsDirectBillingPolicy.sendOnce(key, claims::add) { sends++ }
        assertThrows(SmsDirectOutcomeUnknown::class.java) {
            SmsDirectBillingPolicy.sendOnce(key, claims::add) { sends++ }
        }
        assertEquals(1, sends)
    }

    @Test fun exceptionAfterSubmissionNeverReleasesClaim() {
        val claims = mutableSetOf<String>()
        var sends = 0
        assertThrows(SmsDirectOutcomeUnknown::class.java) {
            SmsDirectBillingPolicy.sendOnce("task", claims::add) { sends++; error("failure after submission") }
        }
        assertThrows(SmsDirectOutcomeUnknown::class.java) {
            SmsDirectBillingPolicy.sendOnce("task", claims::add) { sends++ }
        }
        assertEquals(1, sends)
    }

    @Test fun cancellationIsPropagatedButClaimRemains() {
        val claims = mutableSetOf<String>()
        assertThrows(CancellationException::class.java) {
            SmsDirectBillingPolicy.sendOnce("task", claims::add) { throw CancellationException() }
        }
        assertThrows(SmsDirectOutcomeUnknown::class.java) {
            SmsDirectBillingPolicy.sendOnce("task", claims::add) { fail("must not submit again") }
        }
    }

    @Test fun storageFailurePreventsAnySubmission() {
        assertThrows(IllegalStateException::class.java) {
            SmsDirectBillingPolicy.sendOnce("task", { error("disk unavailable") }) { fail("must not send") }
        }
    }

    @Test fun concurrentExecutionsHaveOneSubmission() {
        val claims = ConcurrentHashMap.newKeySet<String>()
        val start = CountDownLatch(1)
        val done = CountDownLatch(12)
        val sends = AtomicInteger()
        val unexpected = ConcurrentHashMap.newKeySet<Throwable>()
        repeat(12) {
            Thread {
                try {
                    start.await()
                    SmsDirectBillingPolicy.sendOnce("task", claims::add) { sends.incrementAndGet() }
                } catch (_: SmsDirectOutcomeUnknown) {
                } catch (error: Throwable) {
                    unexpected.add(error)
                } finally { done.countDown() }
            }.start()
        }
        start.countDown()
        assertTrue(done.await(3, TimeUnit.SECONDS))
        assertTrue(unexpected.isEmpty())
        assertEquals(1, sends.get())
    }

    @Test fun separateTasksAndDestinationsHaveSeparateKeys() {
        val original = SmsDirectBillingPolicy.key("worker:one", "13800000001")
        assertNotEquals(original, SmsDirectBillingPolicy.key("worker:two", "13800000001"))
        assertNotEquals(original, SmsDirectBillingPolicy.key("worker:one", "13800000002"))
    }

    @Test fun domesticMobileFormattingCannotBypassClaim() {
        val expected = SmsDirectBillingPolicy.key("task", "13800000001")
        assertEquals(expected, SmsDirectBillingPolicy.key("task", "+86 138-0000-0001"))
        assertEquals(expected, SmsDirectBillingPolicy.key("task", "008613800000001"))
        assertEquals(expected, SmsDirectBillingPolicy.key("task", "8613800000001"))
    }

    @Test fun echoToSenderIsDetectedWithoutMatchingUnrelatedNumbers() {
        assertTrue(SmsDirectBillingPolicy.isEcho("+8613800000001", "13800000001"))
        assertTrue(SmsDirectBillingPolicy.isEcho("13800000001", "008613800000001"))
        assertFalse(SmsDirectBillingPolicy.isEcho("13800000002", "13800000001"))
        assertFalse(SmsDirectBillingPolicy.isEcho("", "13800000001"))
        assertFalse(SmsDirectBillingPolicy.isEcho("+12125550100", "2125550100"))
    }

    @Test fun keyFramingCannotConfuseSourceAndDestination() {
        assertNotEquals(SmsDirectBillingPolicy.key("ab", "c"), SmsDirectBillingPolicy.key("a", "bc"))
        assertEquals(64, SmsDirectBillingPolicy.key("source", "13800000001").length)
    }

    @Test fun missingIdentityFailsClosed() {
        assertThrows(IllegalArgumentException::class.java) { SmsDirectBillingPolicy.key("", "13800000001") }
    }

    @Test fun uncertainSmsResultStopsGenericWorkerRetries() {
        assertTrue(DeliveryFailurePolicy.isUnknown(SmsDirectOutcomeUnknown("uncertain")))
    }

    @Test fun ruleInstanceAndGroupRoutesShareTheOriginalSmsSource() {
        val source = SmsDirectBillingPolicy.workerSource("sms-123-instance-a", 1000, "unused")
        listOf("sms-123-rule-action", "sms-123-legacy-sms_direct", "sms-123-step-1", "sms-123-group-b")
            .forEach { assertEquals(source, SmsDirectBillingPolicy.workerSource(it, 1000, "unused")) }
    }

    @Test fun providerIdReuseAtAnotherTimeDoesNotBlockAnotherMessage() {
        assertNotEquals(SmsDirectBillingPolicy.workerSource("sms-123-a", 1000, "unused"),
            SmsDirectBillingPolicy.workerSource("sms-123-a", 2000, "unused"))
    }

    @Test fun nonSmsJobsAndTestsKeepTheirOwnIdentity() {
        assertNotEquals(SmsDirectBillingPolicy.workerSource("test-one", 1000, "unused"),
            SmsDirectBillingPolicy.workerSource("test-two", 1000, "unused"))
        assertEquals(SmsDirectBillingPolicy.workerSource("", 1000, "old-work"),
            SmsDirectBillingPolicy.workerSource("old-work", 1000, "unused"))
    }

    @Test fun originalDeliveryIdentitySurvivesDifferentOutboxTaskIds() {
        assertEquals(SmsDirectBillingPolicy.outboxSource("task-one", "delivery"),
            SmsDirectBillingPolicy.outboxSource("task-two", "delivery"))
        assertNotEquals(SmsDirectBillingPolicy.outboxSource("task-one", ""),
            SmsDirectBillingPolicy.outboxSource("task-two", ""))
    }

    @Test fun attemptedChargedSmsCannotBeManuallyRequeued() {
        assertFalse(SmsDirectBillingPolicy.canRetryManually(true, 1))
        assertFalse(SmsDirectBillingPolicy.canRetryManually(true, 10))
        assertTrue(SmsDirectBillingPolicy.canRetryManually(true, 0))
        assertTrue(SmsDirectBillingPolicy.canRetryManually(false, 10))
    }

    @Test fun differentRoutesOfOneSmsSubmitOnlyOnceToTheSameDestination() {
        val claims = mutableSetOf<String>()
        var submissions = 0
        val first = SmsDirectBillingPolicy.workerSource("sms-123-route-one", 1000, "unused")
        val second = SmsDirectBillingPolicy.workerSource("sms-123-route-two", 1000, "unused")
        SmsDirectBillingPolicy.sendOnce(SmsDirectBillingPolicy.key(first, "13800000001"), claims::add) { submissions++ }
        assertThrows(SmsDirectOutcomeUnknown::class.java) {
            SmsDirectBillingPolicy.sendOnce(SmsDirectBillingPolicy.key(second, "+8613800000001"), claims::add) { submissions++ }
        }
        assertEquals(1, submissions)
    }

    @Test fun degradedBroadcastsAndRoutesShareIdentityDespiteDifferentReceiptTimes() {
        val workId = SmsDirectBillingPolicy.degradedWorkId("a".repeat(64))
        val first = SmsDirectBillingPolicy.workerSource("$workId-instance-one", 1000, "unused")
        val second = SmsDirectBillingPolicy.workerSource("$workId-instance-two", 2000, "unused")
        assertEquals(first, second)
        val claims = mutableSetOf<String>()
        var sends = 0
        SmsDirectBillingPolicy.sendOnce(SmsDirectBillingPolicy.key(first, "13800000001"), claims::add) { sends++ }
        assertThrows(SmsDirectOutcomeUnknown::class.java) {
            SmsDirectBillingPolicy.sendOnce(SmsDirectBillingPolicy.key(second, "+8613800000001"), claims::add) { sends++ }
        }
        assertEquals(1, sends)
    }

    @Test fun differentOriginalPdusRemainDifferentDegradedMessages() {
        val first = SmsDirectBillingPolicy.degradedWorkId("a".repeat(64))
        val second = SmsDirectBillingPolicy.degradedWorkId("b".repeat(64))
        assertNotEquals(SmsDirectBillingPolicy.workerSource(first, 1000, "unused"),
            SmsDirectBillingPolicy.workerSource(second, 1000, "unused"))
    }

    @Test fun malformedDegradedFingerprintFailsBeforeBuildingATask() {
        assertThrows(IllegalArgumentException::class.java) { SmsDirectBillingPolicy.degradedWorkId("old-hash") }
        assertThrows(IllegalArgumentException::class.java) { SmsDirectBillingPolicy.degradedWorkId("") }
    }

    @Test fun unidentifiedOldDegradedQueueCannotGenerateANewCharge() {
        assertTrue(SmsDirectBillingPolicy.isUnidentifiedLegacyDegradedSource(
            SmsDirectBillingPolicy.workerSource("sms-minimal-1000-123-route", 1000, "unused")))
        assertFalse(SmsDirectBillingPolicy.isUnidentifiedLegacyDegradedSource(
            SmsDirectBillingPolicy.workerSource(SmsDirectBillingPolicy.degradedWorkId("a".repeat(64)), 1000, "unused")))
        assertFalse(SmsDirectBillingPolicy.isUnidentifiedLegacyDegradedSource("worker:sms-123:1000"))
    }
}
