package org.fossify.messages.helpers

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneNumberDisplayHelperTest {

    @Test
    fun `strips +86 prefix for 11-digit mobile numbers`() {
        assertEquals("13800138000", PhoneNumberDisplayHelper.formatDisplayNumber("+8613800138000"))
        assertEquals("13800138000", PhoneNumberDisplayHelper.formatDisplayNumber("+86 138-0013-8000"))
        assertEquals("13800138000", PhoneNumberDisplayHelper.formatDisplayNumber("+86 13800138000"))
        assertEquals("15912345678", PhoneNumberDisplayHelper.formatDisplayNumber("+8615912345678"))
        assertEquals("18688889999", PhoneNumberDisplayHelper.formatDisplayNumber("+8618688889999"))
        assertEquals("19900001111", PhoneNumberDisplayHelper.formatDisplayNumber("+8619900001111"))
    }

    @Test
    fun `strips 0086 and 86 prefix for 11-digit mobile numbers`() {
        assertEquals("13800138000", PhoneNumberDisplayHelper.formatDisplayNumber("008613800138000"))
        assertEquals("13800138000", PhoneNumberDisplayHelper.formatDisplayNumber("0086 13800138000"))
        assertEquals("13800138000", PhoneNumberDisplayHelper.formatDisplayNumber("8613800138000"))
    }

    @Test
    fun `strips +86 prefix for special service numbers`() {
        assertEquals("10086", PhoneNumberDisplayHelper.formatDisplayNumber("+8610086"))
        assertEquals("95588", PhoneNumberDisplayHelper.formatDisplayNumber("+86 95588"))
        assertEquals("12345", PhoneNumberDisplayHelper.formatDisplayNumber("+8612345"))
        assertEquals("10690000123", PhoneNumberDisplayHelper.formatDisplayNumber("+8610690000123"))
    }

    @Test
    fun `preserves normal domestic numbers and international numbers`() {
        assertEquals("13800138000", PhoneNumberDisplayHelper.formatDisplayNumber("13800138000"))
        assertEquals("+12025550123", PhoneNumberDisplayHelper.formatDisplayNumber("+12025550123"))
        assertEquals("+447911123456", PhoneNumberDisplayHelper.formatDisplayNumber("+447911123456"))
        assertEquals("张三", PhoneNumberDisplayHelper.formatDisplayNumber("张三"))
        assertEquals("", PhoneNumberDisplayHelper.formatDisplayNumber(""))
        assertEquals("", PhoneNumberDisplayHelper.formatDisplayNumber(null))
    }
}
