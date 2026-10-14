package org.fossify.messages.helpers

import org.junit.Assert.*
import org.junit.Test

class CallAccessDiagnosticsTest {
    @Test fun missingCallPermissionDoesNotReadRecords() {
        val result = CallAccessDiagnostics.check(true, false) { error("Must not query without permission") }
        assertEquals(CallLogAccess.NOT_GRANTED, result.callLogAccess)
        assertFalse(result.canEnable)
    }
    @Test fun grantedPermissionIsInsufficientIfProviderRejectsRead() {
        val result = CallAccessDiagnostics.check(true, true) { throw SecurityException("blocked") }
        assertEquals(CallLogAccess.DENIED, result.callLogAccess)
        assertFalse(result.canEnable)
    }
    @Test fun emptyHistoryIsSuccessfulAccess() {
        val result = CallAccessDiagnostics.check(true, true) { CallLogAccess.EMPTY }
        assertTrue(result.canEnable)
        assertTrue(result.summary().contains("目前没有通话记录"))
    }
    @Test fun readableHistoryStillRequiresPhoneStatePermission() {
        assertFalse(CallAccessDiagnostics.check(false, true) { CallLogAccess.READABLE }.canEnable)
    }
    @Test fun unavailableProviderCannotEnableFeature() {
        assertFalse(CallAccessDiagnostics.check(true, true) { CallLogAccess.PROVIDER_UNAVAILABLE }.canEnable)
    }
    @Test fun hiddenNumberProducesWarningWithoutPretendingAccessFailed() {
        val result = CallAccessDiagnostics.check(true, true) { CallLogAccess.NUMBER_UNAVAILABLE }
        assertTrue(result.canEnable)
        assertTrue(result.summary().contains("号码为空或被隐藏"))
    }
    @Test fun providerFailureIsReported() {
        val result = CallAccessDiagnostics.check(true, true) { throw IllegalStateException("failed") }
        assertEquals(CallLogAccess.FAILED, result.callLogAccess)
        assertFalse(result.canEnable)
    }
    @Test fun readableHistoryPassesButDoesNotClaimEndToEndSuccess() {
        val result = CallAccessDiagnostics.check(true, true) { CallLogAccess.READABLE }
        assertTrue(result.canEnable)
        assertTrue(result.summary().contains("不证明来电广播"))
    }
}
