package org.fossify.messages.forwarding

import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationForwardConfigTest {
    @Test
    fun systemSmsPackagesContainMajorOemMessagingApps() {
        val packages = NotificationForwardConfig.SYSTEM_SMS_PACKAGES
        assertTrue(packages.contains("com.android.mms"))
        assertTrue(packages.contains("com.vivo.message"))
        assertTrue(packages.contains("com.coloros.mcs"))
        assertTrue(packages.contains("com.oplus.mms"))
        assertTrue(packages.contains("com.coloros.mms"))
        assertTrue(packages.contains("com.oneplus.mms"))
        assertTrue(packages.contains("com.meizu.mms"))
        assertTrue(packages.contains("com.huawei.message"))
        assertTrue(packages.contains("com.hihonor.mms"))
        assertTrue(packages.contains("com.samsung.android.messaging"))
        assertTrue(packages.contains("com.google.android.apps.messaging"))
    }
}
