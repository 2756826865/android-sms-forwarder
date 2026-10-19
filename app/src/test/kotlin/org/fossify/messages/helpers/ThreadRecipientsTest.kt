package org.fossify.messages.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadRecipientsTest {
    @Test
    fun reportedLargeIdReachesProviderUnchanged() {
        val id = "1069456800000002924"
        val result = resolveThreadRecipients(parseThreadRecipientIds(id), lookup = {
            assertEquals(id, it)
            "106900001234"
        }, fallback = { error("A resolved ID must not use the fallback") })
        assertEquals(listOf(ThreadRecipient(id, "106900001234")), result)
        assertNull(result.single().contactId)
    }

    @Test
    fun providerKeysLargerThanLongAreNotParsedOrTruncated() {
        val id = "9223372036854775808123456"
        val result = resolveThreadRecipients(parseThreadRecipientIds(id), { "number-for-$it" }, { error("fallback") })
        assertEquals(id, result.single().addressId)
        assertEquals("number-for-$id", result.single().number)
        assertNull(result.single().contactId)
    }

    @Test
    fun ordinaryAndLargeIdsKeepTheirOwnNumbersInAGroup() {
        val id = "1069456800000002924"
        val numbers = mapOf("7" to "+8613800000001", id to "+8613800000002")
        val result = resolveThreadRecipients(parseThreadRecipientIds("7 $id"), numbers::get, { error("fallback") })
        assertEquals(listOf(ThreadRecipient("7", numbers.getValue("7")), ThreadRecipient(id, numbers.getValue(id))), result)
        assertEquals(7, result.first().contactId)
        assertNull(result.last().contactId)
    }

    @Test
    fun missingSingleIdUsesActualSmsAddressRatherThanIdAsNumber() {
        val id = "1069456800000002924"
        val result = resolveThreadRecipients(parseThreadRecipientIds(id), { null }, { listOf("+8613800000001") })
        assertEquals(listOf(ThreadRecipient(null, "+8613800000001")), result)
    }

    @Test
    fun blankProviderAddressAlsoUsesFallback() {
        val result = resolveThreadRecipients(listOf("7"), { "  " }, { listOf("001234") })
        assertEquals(listOf(ThreadRecipient(null, "001234")), result)
    }

    @Test
    fun unknownGroupMemberCannotShiftIdsOntoAnotherNumber() {
        var fallbackCalled = false
        val result = resolveThreadRecipients(listOf("7", "8", "9"), {
            if (it == "8") null else "number-for-$it"
        }, { fallbackCalled = true; listOf("unsafe-single-sender") })
        assertTrue(result.isEmpty())
        assertEquals(false, fallbackCalled)
    }

    @Test
    fun entirelyUnresolvedGroupCannotBecomeASingleSmsRecipient() {
        val result = resolveThreadRecipients(listOf("7", "8"), { null }, { error("unsafe group fallback") })
        assertTrue(result.isEmpty())
    }

    @Test
    fun malformedSingleKeyNeverReachesProviderAndCanFallback() {
        listOf("not-an-id", "-1", "+7", "12x").forEach { id ->
            val result = resolveThreadRecipients(parseThreadRecipientIds(id), { error("invalid lookup") }, { listOf("ServiceSMS") })
            assertEquals(listOf(ThreadRecipient(null, "ServiceSMS")), result)
        }
    }

    @Test
    fun malformedGroupKeyDoesNotSilentlyRemoveARecipient() {
        val result = resolveThreadRecipients(listOf("7", "bad-key"), { "number-for-$it" }, { error("unsafe fallback") })
        assertTrue(result.isEmpty())
    }

    @Test
    fun whitespaceAndDuplicateKeysPreserveFirstOccurrenceOrder() {
        assertEquals(listOf("7", "8", "0009"), parseThreadRecipientIds(" \t7  8\n7 \r\n0009 "))
    }

    @Test
    fun absentKeysPreserveExactSmsAddressesAndRemoveEmptyDuplicates() {
        val addresses = listOf("", "  ", "+8613800000001", "001234", "ServiceSMS", "+8613800000001")
        listOf(null, "", " \t\n").forEach { raw ->
            val result = resolveThreadRecipients(parseThreadRecipientIds(raw), { error("no lookup") }, { addresses })
            assertEquals(listOf("+8613800000001", "001234", "ServiceSMS"), result.map { it.number })
            assertTrue(result.all { it.addressId == null && it.contactId == null })
        }
    }

    @Test
    fun intBoundaryHasNoWrappingIntoAnotherContact() {
        assertEquals(Int.MAX_VALUE, ThreadRecipient(Int.MAX_VALUE.toString(), "number").contactId)
        assertNull(ThreadRecipient("2147483648", "number").contactId)
        assertNull(ThreadRecipient("9223372036854775808", "number").contactId)
    }
}
