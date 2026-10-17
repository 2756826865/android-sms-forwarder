package org.fossify.messages.forwarding

import org.junit.Assert.*
import org.junit.Test

class ExecutionPolicyTest {
    @Test fun readTimeoutHasUnknownOutcome() {
        assertTrue(DeliveryFailurePolicy.isUnknown(java.net.SocketTimeoutException("read timed out")))
        assertTrue(DeliveryFailurePolicy.isUnknown(IllegalStateException("wrapped", java.io.IOException("write failed"))))
    }
    @Test fun connectionFailureCanBeRetriedWithoutDuplicateSubmission() {
        assertFalse(DeliveryFailurePolicy.isUnknown(java.net.ConnectException("connection refused")))
        assertFalse(DeliveryFailurePolicy.isUnknown(java.net.UnknownHostException("unknown host")))
    }
    @Test fun explicitApiRejectionIsNotAnUnknownTransportResult() {
        assertFalse(DeliveryFailurePolicy.isUnknown(WebhookRejectedException("rejected")))
    }
    @Test fun stopAfterFirstSuccess() {
        assertTrue(GroupExecutionPolicy.shouldContinue("until_success", "failed"))
        assertTrue(GroupExecutionPolicy.shouldContinue("until_success", "skipped"))
        assertFalse(GroupExecutionPolicy.shouldContinue("until_success", "success"))
    }
    @Test fun stopAfterFirstFailure() {
        assertTrue(GroupExecutionPolicy.shouldContinue("until_fail", "success"))
        assertFalse(GroupExecutionPolicy.shouldContinue("until_fail", "failed"))
        assertFalse(GroupExecutionPolicy.shouldContinue("until_fail", "skipped"))
    }
    @Test fun unknownAndUnfinishedNeverAdvance() {
        for (mode in listOf("all", "until_success", "until_fail", "invalid")) {
            for (status in listOf("unknown", "running", "queued", "retry")) {
                assertFalse("$mode / $status", GroupExecutionPolicy.shouldContinue(mode, status))
            }
        }
    }
    @Test fun plainHttpPreservesExistingBehaviour() {
        WebhookSuccessRule().requireAccepted(204, "")
    }
    @Test fun jsonChecksBusinessAcceptance() {
        WebhookSuccessRule("json", "data.ok", "true").requireAccepted(200, """{"data":{"ok":true}}""")
        WebhookSuccessRule("json", "code", "0").requireAccepted(200, """{"code":0}""")
    }
    @Test(expected = WebhookRejectedException::class)
    fun businessFailureIsNotHttpSuccess() {
        WebhookSuccessRule("json", "code", "0").requireAccepted(200, """{"code":403}""")
    }
    @Test(expected = WebhookRejectedException::class)
    fun httpFailureCannotBeOverriddenByBody() {
        WebhookSuccessRule("contains", expected = "success").requireAccepted(500, "success")
    }
    @Test(expected = WebhookRejectedException::class)
    fun missingFieldFailsClosed() {
        WebhookSuccessRule("json", "code", "0").requireAccepted(200, "{}")
    }
    @Test(expected = WebhookRejectedException::class)
    fun malformedJsonFailsClosed() {
        WebhookSuccessRule("json", "code", "0").requireAccepted(200, "not json")
    }
    @Test fun responseContainsIsExplicit() {
        WebhookSuccessRule("contains", expected = "accepted").requireAccepted(200, "request accepted")
    }
}
