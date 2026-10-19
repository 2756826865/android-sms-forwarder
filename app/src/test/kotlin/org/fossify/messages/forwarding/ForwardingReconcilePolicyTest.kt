package org.fossify.messages.forwarding

import org.junit.Assert.*
import org.junit.Test

class ForwardingReconcilePolicyTest {
    private fun state(name: String, attempts: Int = 0) = ForwardingWorkState(name, attempts)
    private fun decision(works: List<ForwardingWorkState>, status: String = "queued", attempts: Int = 0, age: Long = 300_000) =
        ForwardingReconcilePolicy.decide(status, attempts, age, works)
    private fun eligible(works: List<ForwardingWorkState> = listOf(state("FAILED")), attempts: Int = 0,
                         age: Long = 1000, version: Int = 1, allowed: Boolean = true,
                         claimed: Boolean = false, isTest: Boolean = false, snapshotMatches: Boolean = true) =
        ForwardingReconcilePolicy.canRepair(works, attempts, age, version, allowed, claimed, isTest, snapshotMatches)

    @Test fun liveWorkAlwaysKeepsItsOriginalOwner() {
        for (name in listOf("ENQUEUED", "RUNNING", "BLOCKED")) {
            assertEquals(ForwardingReconcilePolicy.Decision.ACTIVE, decision(listOf(state("FAILED"), state(name))))
            assertFalse(eligible(listOf(state(name))))
        }
    }
    @Test fun missingTaskIsNeverAutomaticallyReplayed() {
        assertEquals(ForwardingReconcilePolicy.Decision.UNKNOWN, decision(emptyList()))
        assertFalse(eligible(emptyList()))
    }
    @Test fun gracePeriodProtectsSubmissionInFlight() {
        assertEquals(ForwardingReconcilePolicy.Decision.KEEP, decision(emptyList(), age = 1000))
        assertEquals(ForwardingReconcilePolicy.Decision.KEEP, decision(emptyList(), age = -1000))
    }
    @Test fun endedWorkerDoesNotProveSuccessfulDelivery() {
        assertEquals(ForwardingReconcilePolicy.Decision.UNKNOWN, decision(listOf(state("SUCCEEDED"))))
        assertFalse(eligible(listOf(state("SUCCEEDED"))))
    }
    @Test fun onlyNeverStartedFailedWorkCanBeRepaired() {
        assertEquals(ForwardingReconcilePolicy.Decision.STOPPED_UNSTARTED, decision(listOf(state("FAILED"))))
        assertTrue(eligible())
        assertFalse(eligible(listOf(state("FAILED", 1))))
        assertFalse(eligible(attempts = 1))
    }
    @Test fun cancellationIsNotAnInvitationToRestart() {
        assertFalse(eligible(listOf(state("CANCELLED"))))
        assertFalse(eligible(listOf(state("FAILED"), state("CANCELLED"))))
    }
    @Test fun noRepairOfLegacyIncompleteOrGroupSnapshots() {
        assertFalse(eligible(version = 0))
        assertFalse(eligible(version = 2))
        assertFalse(eligible(allowed = false))
        assertFalse(eligible(isTest = true))
    }
    @Test fun repairHasAnAgeLimitAndPersistentOneShotGuard() {
        assertFalse(eligible(age = 86_400_001))
        assertFalse(eligible(age = -1))
        assertFalse(eligible(claimed = true))
    }
    @Test fun keepSuppressedDuplicateCannotSupplyADifferentRepairPayload() {
        assertFalse(eligible(snapshotMatches = false))
    }
    @Test fun knownDeliveryResultsCannotBeReclassifiedByAnOldSnapshot() {
        for (status in listOf("success", "failed", "unknown", "skipped"))
            assertEquals(ForwardingReconcilePolicy.Decision.KEEP, decision(emptyList(), status = status))
    }
    @Test fun eitherSystemOrLocalEvidenceOfStartupBlocksReplay() {
        assertEquals(ForwardingReconcilePolicy.Decision.UNKNOWN, decision(listOf(state("FAILED", 1))))
        assertEquals(ForwardingReconcilePolicy.Decision.UNKNOWN, decision(listOf(state("FAILED")), attempts = 1))
    }
    @Test fun repairReservationProtectsTheGapBeforeAsyncSubmissionCompletes() {
        val failedOriginal = listOf(state("FAILED"))
        assertEquals(ForwardingReconcilePolicy.Decision.KEEP,
            ForwardingReconcilePolicy.decide("retry", 0, 1000, failedOriginal, repairClaimed = true))
        assertEquals(ForwardingReconcilePolicy.Decision.UNKNOWN,
            ForwardingReconcilePolicy.decide("retry", 0, 300_000, failedOriginal, repairClaimed = true))
        assertEquals(ForwardingReconcilePolicy.Decision.ACTIVE,
            ForwardingReconcilePolicy.decide("retry", 0, 300_000,
                failedOriginal + state("ENQUEUED"), repairClaimed = true))
    }

    @Test fun rejectedSubmissionStillObservesACommittedActiveTask() {
        assertEquals(ForwardingReconcilePolicy.Decision.ACTIVE,
            decision(listOf(state("ENQUEUED")), status = "unknown"))
    }

    @Test fun unknownSubmissionIsObservedWithoutBlindAutomaticReplay() {
        assertEquals(ForwardingReconcilePolicy.Decision.KEEP, decision(emptyList(), status = "unknown"))
        assertEquals(ForwardingReconcilePolicy.Decision.KEEP, decision(listOf(state("FAILED")), status = "unknown"))
    }
}
