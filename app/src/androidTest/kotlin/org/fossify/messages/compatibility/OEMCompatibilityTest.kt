package org.fossify.messages.compatibility

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.fossify.messages.compatibility.model.DeviceBrand
import org.fossify.messages.compatibility.model.DeviceCapability
import org.fossify.messages.messaging.HonorSmsCompatibility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OEMCompatibilityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testAospDeviceProfile_DefaultCapabilities() {
        val aospProfile = CompatibilityManager.buildCustomProfile(
            brand = DeviceBrand.AOSP,
            manufacturer = "Google",
            model = "AOSP on ARM64",
            apiLevel = 30
        )

        assertNotNull(aospProfile)
        assertEquals(DeviceBrand.AOSP, aospProfile.brand)
        assertFalse(aospProfile.hasCapability(DeviceCapability.REQUIRES_AUTORUN_GUIDE))
        assertFalse(aospProfile.hasCapability(DeviceCapability.REQUIRES_HONOR_IMS_GUARD))
        assertTrue(aospProfile.hasCapability(DeviceCapability.REQUIRES_BATTERY_WHITELIST))
        assertTrue(aospProfile.hasCapability(DeviceCapability.SUPPORTS_DIRECT_SIM_ROUTING))
    }

    @Test
    fun testHonorDeviceProfile_HasHonorImsGuardAndAutorun() {
        val honorProfile = CompatibilityManager.buildCustomProfile(
            brand = DeviceBrand.HONOR,
            manufacturer = "HONOR",
            model = "MAG-AN00",
            apiLevel = 31
        )

        assertEquals(DeviceBrand.HONOR, honorProfile.brand)
        assertTrue(honorProfile.hasCapability(DeviceCapability.REQUIRES_HONOR_IMS_GUARD))
        assertTrue(honorProfile.hasCapability(DeviceCapability.REQUIRES_AUTORUN_GUIDE))
        assertTrue(honorProfile.hasCapability(DeviceCapability.REQUIRES_BATTERY_WHITELIST))
    }

    @Test
    fun testXiaomiDeviceProfile_HasAutorunAndBatteryRestrictions() {
        val xiaomiProfile = CompatibilityManager.buildCustomProfile(
            brand = DeviceBrand.XIAOMI,
            manufacturer = "Xiaomi",
            model = "2201123C",
            apiLevel = 33
        )

        assertEquals(DeviceBrand.XIAOMI, xiaomiProfile.brand)
        assertTrue(xiaomiProfile.hasCapability(DeviceCapability.REQUIRES_AUTORUN_GUIDE))
        assertTrue(xiaomiProfile.hasCapability(DeviceCapability.REQUIRES_BATTERY_WHITELIST))
        assertTrue(xiaomiProfile.hasCapability(DeviceCapability.REQUIRES_POST_NOTIFICATIONS))

        val bgCompat = BackgroundExecutionCompat(xiaomiProfile)
        val tips = bgCompat.getBrandTips()
        assertTrue(tips.any { it.contains("自启动") })
    }

    @Test
    fun testHuaweiDeviceProfile_HasAutorunCapabilities() {
        val huaweiProfile = CompatibilityManager.buildCustomProfile(
            brand = DeviceBrand.HUAWEI,
            manufacturer = "HUAWEI",
            model = "NOH-AN00",
            apiLevel = 29
        )

        assertEquals(DeviceBrand.HUAWEI, huaweiProfile.brand)
        assertTrue(huaweiProfile.hasCapability(DeviceCapability.REQUIRES_AUTORUN_GUIDE))
        assertTrue(huaweiProfile.hasCapability(DeviceCapability.REQUIRES_BATTERY_WHITELIST))

        val bgCompat = BackgroundExecutionCompat(huaweiProfile)
        val tips = bgCompat.getBrandTips()
        assertTrue(tips.any { it.contains("应用启动管理") })
    }

    @Test
    fun testUnifiedFacadeAccess_CompatibilityManager() {
        assertNotNull(CompatibilityManager.deviceProfile)
        assertNotNull(CompatibilityManager.backgroundCompat)
        assertNotNull(CompatibilityManager.smsProviderCompat)

        val profile = CompatibilityManager.deviceProfile
        assertTrue(profile.apiLevel > 0)
        assertNotNull(profile.brand)
        assertNotNull(profile.romName)
    }

    @Test
    fun testHonorSmsCompatibility_DelegatesCleanly() {
        val guardKey = HonorSmsCompatibility.claim(context, subId = 1, destination = "10086", body = "Test Body")
        assertNotNull(guardKey)
        HonorSmsCompatibility.complete(context, guardKey)
    }
}
