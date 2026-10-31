package org.fossify.messages.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReceivingIdentityPolicyTest {
    private val active = listOf(ReceivingIdentityPolicy.ActiveSubscription(7, 0),
        ReceivingIdentityPolicy.ActiveSubscription(42, 1))
    private fun resolve(extras: Map<String, Any?>,
                        status: ReceivingIdentityPolicy.ReadStatus = ReceivingIdentityPolicy.ReadStatus.AVAILABLE,
                        cards: List<ReceivingIdentityPolicy.ActiveSubscription> = active) =
        ReceivingIdentityPolicy.resolve(extras, cards, status, 100L)

    @Test fun longSubscriptionMapsToActualSlot() {
        val result = resolve(mapOf("android.telephony.extra.SUBSCRIPTION_INDEX" to 42L))
        assertEquals(42, result.confirmedSubscriptionId)
        assertEquals(1, result.slotIndex)
    }
    @Test fun conflictingFieldsNeverConfirm() {
        val result = resolve(mapOf("android.telephony.extra.SUBSCRIPTION_INDEX" to 42, "subscription" to 7))
        assertEquals(ReceivingIdentityPolicy.Status.CONFLICT, result.status)
        assertNull(result.confirmedSubscriptionId)
    }
    @Test fun subscriptionAndSlotMustAgree() {
        assertEquals(ReceivingIdentityPolicy.Status.CONFLICT,
            resolve(mapOf("subscription" to 42, "slot" to 0)).status)
    }
    @Test fun noSingleCardGuess() {
        assertNull(resolve(emptyMap(), cards = active.take(1)).confirmedSubscriptionId)
    }
    @Test fun unavailableMappingPreservesEvidenceWithoutGuessing() {
        for (status in ReceivingIdentityPolicy.ReadStatus.values().filter { it != ReceivingIdentityPolicy.ReadStatus.AVAILABLE }) {
            val result = resolve(mapOf("subscription" to 42), status)
            assertEquals(42, result.observedSubscriptionId)
            assertNull(result.confirmedSubscriptionId)
            assertNull(result.slotIndex)
            assertEquals(ReceivingIdentityPolicy.Status.UNVERIFIED, result.status)
        }
    }
    @Test fun rejectSentinelsOverflowAndUnspecifiedStrings() {
        for (value in listOf(-1, Int.MAX_VALUE, Long.MAX_VALUE, 42L + Int.MAX_VALUE, "42")) {
            assertNull(resolve(mapOf("subscription" to value)).observedSubscriptionId)
        }
    }
    @Test fun historicalSubscriptionDoesNotBecomeCurrentSingleCard() {
        val result = resolve(mapOf("subscription" to 100), cards = active.take(1))
        assertEquals(100, result.observedSubscriptionId)
        assertNull(result.confirmedSubscriptionId)
    }
    @Test fun slotRequiresUniqueMapping() {
        assertEquals(42, resolve(mapOf("slot" to 1)).confirmedSubscriptionId)
        assertNull(resolve(mapOf("slot" to 0), cards = listOf(
            ReceivingIdentityPolicy.ActiveSubscription(7, 0), ReceivingIdentityPolicy.ActiveSubscription(8, 0)
        )).confirmedSubscriptionId)
    }
}
