package org.fossify.messages.activities

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadInsetsTest {
    @Test
    fun `three button navigation uses navigation inset`() {
        assertEquals(
            126,
            resolveThreadBottomInset(126, 126, 0, 0, imeVisible = false),
        )
    }

    @Test
    fun `oem tappable inset is used when navigation inset is missing`() {
        assertEquals(
            120,
            resolveThreadBottomInset(0, 120, 24, 0, imeVisible = false),
        )
    }

    @Test
    fun `gesture navigation keeps gesture safe area`() {
        assertEquals(
            32,
            resolveThreadBottomInset(0, 0, 32, 0, imeVisible = false),
        )
    }

    @Test
    fun `visible ime wins over system navigation`() {
        assertEquals(
            720,
            resolveThreadBottomInset(126, 126, 24, 720, imeVisible = true),
        )
    }

    @Test
    fun `hidden ime value is ignored`() {
        assertEquals(
            126,
            resolveThreadBottomInset(126, 126, 24, 720, imeVisible = false),
        )
    }
}
