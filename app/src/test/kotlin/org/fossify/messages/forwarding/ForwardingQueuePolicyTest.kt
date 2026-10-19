package org.fossify.messages.forwarding

import org.junit.Assert.*
import org.junit.Test

class ForwardingQueuePolicyTest {
    private fun describe(state: String? = "ENQUEUED", attempts: Int = 0,
                         next: Long = 0, needsNetwork: Boolean = true, ready: Boolean? = true) =
        ForwardingQueuePolicy.describe(state, attempts, next, 1_000, needsNetwork, ready)

    @Test fun workingCellularNetworkStillCanWaitForScheduling() {
        assertEquals("等待系统调度；并不代表正在发送", describe())
    }
    @Test fun unvalidatedNetworkIsOnlyAPossibleCause() {
        assertTrue(describe(ready = false).contains("可能等待网络"))
    }
    @Test fun networkCannotMaskAnExplicitDelay() {
        val result = describe(next = 2_000, ready = false)
        assertTrue(result.contains("尚未到执行时间"))
        assertTrue(result.contains("可能等待网络"))
    }
    @Test fun retryIsNotMisreportedAsFirstSubmission() {
        assertTrue(describe(attempts = 1, next = 2_000).contains("曾启动"))
    }
    @Test fun unavailableNetworkDoesNotBlockSmsDirectDiagnosis() {
        assertFalse(describe(needsNetwork = false, ready = false).contains("等待网络"))
    }
    @Test fun unknownNetworkIsNotReportedAsOffline() {
        assertTrue(describe(ready = null).contains("无法读取"))
        assertFalse(describe(ready = null).contains("未满足"))
    }
    @Test fun noScheduleSentinelIsNotADelayUntilYear292Million() {
        assertFalse(describe(next = Long.MAX_VALUE).contains("执行时间"))
    }
    @Test fun completedWorkerDoesNotProveDelivery() {
        assertTrue(describe("SUCCEEDED").contains("转发记录为准"))
    }
    @Test fun prunedTaskDoesNotProveFailureOrDelivery() {
        assertTrue(describe(null).contains("不能据此判断"))
    }
    @Test fun runningTaskDoesNotClaimToBeWaitingForNetwork() {
        assertFalse(describe("RUNNING", ready = false).contains("等待网络"))
    }
    @Test fun submissionCallbackCannotClobberNewerDeliveryStates() {
        for (status in listOf("running", "retry", "success", "failed", "skipped", "unknown")) {
            assertFalse(status, ForwardingQueuePolicy.canUpdateSubmission(status, 0))
        }
        assertFalse(ForwardingQueuePolicy.canUpdateSubmission("queued", 1))
        assertTrue(ForwardingQueuePolicy.canUpdateSubmission("queued", 0))
    }
    @Test fun terminalAndBlockedStatesRemainDistinct() {
        assertTrue(describe("CANCELLED").contains("已取消"))
        assertTrue(describe("FAILED").contains("已失败"))
        assertTrue(describe("BLOCKED").contains("前置"))
    }

    @Test fun activeOriginalPreventsAnotherManualSend() {
        for (state in listOf("RUNNING", "ENQUEUED", "BLOCKED")) {
            assertFalse(ForwardingQueuePolicy.canRetryAfterLookup(listOf(ForwardingWorkState(state, 0))))
            assertFalse(ForwardingQueuePolicy.canRetryAfterLookup(listOf(
                ForwardingWorkState("FAILED", 0), ForwardingWorkState(state, 0))))
        }
    }

    @Test fun missingCompletedOrUnrecognisedOriginalFailsClosed() {
        assertFalse(ForwardingQueuePolicy.canRetryAfterLookup(emptyList()))
        for (state in listOf("SUCCEEDED", "FUTURE_STATE"))
            assertFalse(ForwardingQueuePolicy.canRetryAfterLookup(listOf(ForwardingWorkState(state, 0))))
    }

    @Test fun confirmedFailedOrCancelledWorkCanBeExplicitlyRetried() {
        assertTrue(ForwardingQueuePolicy.canRetryAfterLookup(listOf(ForwardingWorkState("FAILED", 1))))
        assertTrue(ForwardingQueuePolicy.canRetryAfterLookup(listOf(ForwardingWorkState("CANCELLED", 0))))
    }

    @Test fun lookupUsesTheOriginalUniqueNameForInstancesAndFallbacks() {
        assertEquals("multi-forward-sms-123-instance_abc", ForwardingQueuePolicy.uniqueName("sms-123", "instance_abc"))
        assertEquals("fallback-uuid", ForwardingQueuePolicy.uniqueName("fallback-uuid", "instance_abc"))
    }
}
