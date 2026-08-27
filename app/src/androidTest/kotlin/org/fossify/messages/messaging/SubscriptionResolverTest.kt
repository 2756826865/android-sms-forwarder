package org.fossify.messages.messaging

import android.telephony.SubscriptionManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SubscriptionResolverTest {

    private val sim1Snapshot = SubscriptionSnapshot(
        subscriptionId = 1,
        simSlotIndex = 0,
        displayName = "中国移动",
        carrierName = "中国移动",
        number = "13800138000"
    )

    private val sim2Snapshot = SubscriptionSnapshot(
        subscriptionId = 2,
        simSlotIndex = 1,
        displayName = "中国联通",
        carrierName = "中国联通",
        number = "13000130000"
    )

    private val dualSimList = listOf(sim1Snapshot, sim2Snapshot)
    private val singleSimList = listOf(sim1Snapshot)

    @Test
    fun testExplicitSubId_matchesDirectly() {
        val request = SimResolutionRequest(explicitSubId = 2, allowFallback = false)
        val result = SubscriptionResolver.resolveInternal(
            activeSubscriptions = dualSimList,
            defaultSmsSubId = 1,
            getAddressPreferredSubId = { null },
            request = request,
            hasPhonePermission = true
        )

        assertTrue(result.isSuccessful)
        assertEquals(2, result.resolvedSubscriptionId)
        assertEquals(1, result.resolvedSlotIndex)
        assertEquals(SimResolutionSource.EXPLICIT_SUB_ID, result.resolutionSource)
        assertFalse(result.isFallback)
        assertNull(result.fallbackReason)
        assertNull(result.errorReason)
    }

    @Test
    fun testExplicitSubId_offline_allowFallbackTrue() {
        val request = SimResolutionRequest(explicitSubId = 99, allowFallback = true)
        val result = SubscriptionResolver.resolveInternal(
            activeSubscriptions = dualSimList,
            defaultSmsSubId = 1,
            getAddressPreferredSubId = { null },
            request = request,
            hasPhonePermission = true
        )

        assertTrue(result.isSuccessful)
        assertEquals(1, result.resolvedSubscriptionId) // Fallback to first active
        assertEquals(0, result.resolvedSlotIndex)
        assertEquals(SimResolutionSource.FALLBACK_FIRST_ACTIVE, result.resolutionSource)
        assertTrue(result.isFallback)
        assertEquals(SimFallbackReason.REQUESTED_SUB_ID_NOT_ACTIVE, result.fallbackReason)
    }

    @Test
    fun testExplicitSubId_offline_allowFallbackFalse() {
        val request = SimResolutionRequest(explicitSubId = 99, allowFallback = false)
        val result = SubscriptionResolver.resolveInternal(
            activeSubscriptions = dualSimList,
            defaultSmsSubId = 1,
            getAddressPreferredSubId = { null },
            request = request,
            hasPhonePermission = true
        )

        assertFalse(result.isSuccessful)
        assertEquals(SubscriptionManager.INVALID_SUBSCRIPTION_ID, result.resolvedSubscriptionId)
        assertEquals(SimErrorReason.SUBSCRIPTION_NOT_ACTIVE, result.errorReason)
    }

    @Test
    fun testExplicitSlot_matches() {
        val request = SimResolutionRequest(explicitSlotIndex = 1, allowFallback = false)
        val result = SubscriptionResolver.resolveInternal(
            activeSubscriptions = dualSimList,
            defaultSmsSubId = 1,
            getAddressPreferredSubId = { null },
            request = request,
            hasPhonePermission = true
        )

        assertTrue(result.isSuccessful)
        assertEquals(2, result.resolvedSubscriptionId)
        assertEquals(1, result.resolvedSlotIndex)
        assertEquals(SimResolutionSource.EXPLICIT_SLOT, result.resolutionSource)
    }

    @Test
    fun testExplicitSlot_emptySlot_fallback() {
        val request = SimResolutionRequest(explicitSlotIndex = 1, allowFallback = true)
        val result = SubscriptionResolver.resolveInternal(
            activeSubscriptions = singleSimList, // only slot 0
            defaultSmsSubId = 1,
            getAddressPreferredSubId = { null },
            request = request,
            hasPhonePermission = true
        )

        assertTrue(result.isSuccessful)
        assertEquals(1, result.resolvedSubscriptionId)
        assertEquals(0, result.resolvedSlotIndex)
        assertEquals(SimResolutionSource.FALLBACK_FIRST_ACTIVE, result.resolutionSource)
        assertTrue(result.isFallback)
        assertEquals(SimFallbackReason.REQUESTED_SLOT_EMPTY, result.fallbackReason)
    }

    @Test
    fun testConfiguredMode_FollowReceive() {
        val request = SimResolutionRequest(
            configuredMode = SubscriptionResolver.MODE_FOLLOW_RECEIVE,
            receivedSubId = 2,
            allowFallback = false
        )
        val result = SubscriptionResolver.resolveInternal(
            activeSubscriptions = dualSimList,
            defaultSmsSubId = 1,
            getAddressPreferredSubId = { null },
            request = request,
            hasPhonePermission = true
        )

        assertTrue(result.isSuccessful)
        assertEquals(2, result.resolvedSubscriptionId)
        assertEquals(1, result.resolvedSlotIndex)
        assertEquals(SimResolutionSource.CONFIGURED_MODE, result.resolutionSource)
    }

    @Test
    fun testAddressPreference_matches() {
        val targetNumber = "10086"
        val request = SimResolutionRequest(targetAddress = targetNumber, allowFallback = false)
        val result = SubscriptionResolver.resolveInternal(
            activeSubscriptions = dualSimList,
            defaultSmsSubId = 1,
            getAddressPreferredSubId = { if (it == targetNumber) 2 else null },
            request = request,
            hasPhonePermission = true
        )

        assertTrue(result.isSuccessful)
        assertEquals(2, result.resolvedSubscriptionId)
        assertEquals(1, result.resolvedSlotIndex)
        assertEquals(SimResolutionSource.ADDRESS_PREFERENCE, result.resolutionSource)
    }

    @Test
    fun testNoActiveSubscriptions_returnsFailure() {
        val request = SimResolutionRequest(allowFallback = true)
        val result = SubscriptionResolver.resolveInternal(
            activeSubscriptions = emptyList(),
            defaultSmsSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
            getAddressPreferredSubId = { null },
            request = request,
            hasPhonePermission = true
        )

        assertFalse(result.isSuccessful)
        assertEquals(SimErrorReason.NO_ACTIVE_SUBSCRIPTION, result.errorReason)
    }

    @Test
    fun testPermissionDenied_noFallback_returnsFailure() {
        val request = SimResolutionRequest(allowFallback = false)
        val result = SubscriptionResolver.resolveInternal(
            activeSubscriptions = emptyList(),
            defaultSmsSubId = SubscriptionManager.INVALID_SUBSCRIPTION_ID,
            getAddressPreferredSubId = { null },
            request = request,
            hasPhonePermission = false
        )

        assertFalse(result.isSuccessful)
        assertEquals(SimErrorReason.PERMISSION_DENIED, result.errorReason)
    }
}
