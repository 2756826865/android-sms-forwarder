package org.fossify.messages.helpers

import java.util.concurrent.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RecoveryStepsTest {
    @Test fun failedRegistrationDoesNotPreventOtherSubsystems() {
        val reports = mutableListOf<Pair<String, Exception?>>()
        val steps = RecoverySteps { name, failure -> reports.add(name to failure) }
        val failure = IllegalStateException("scheduled messages unavailable")
        var recoveryRegistered = false
        steps.run("scheduled") { throw failure }
        steps.run("recovery") { recoveryRegistered = true }
        assertEquals(true, recoveryRegistered)
        assertEquals(listOf("scheduled", "recovery"), reports.map { it.first })
        assertSame(failure, reports[0].second)
        assertEquals(null, reports[1].second)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsNotReportedAsSuccessfulRecovery() {
        RecoverySteps { _, _ -> error("must propagate cancellation") }
            .run("cancelled") { throw CancellationException() }
    }

    @Test(expected = OutOfMemoryError::class)
    fun fatalErrorsAreNotSwallowed() {
        RecoverySteps { _, _ -> error("must propagate fatal failure") }
            .run("fatal") { throw OutOfMemoryError() }
    }
}
