package org.fossify.messages.messaging

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.helpers.Config
import org.fossify.messages.models.SmsSendContext
import org.fossify.messages.models.SmsSendTriggerType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HeadlessSendMigrationTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Config.newInstance(context).smsSendOperationShadowEnabled = true
    }

    private fun parseNumberFromUri(dataString: String?): String {
        if (dataString == null) return ""
        return Uri.decode(
            dataString
                .removePrefix("smsto:")
                .removePrefix("sms:")
                .removePrefix("mmsto:")
                .removePrefix("mms:")
                .trim()
        )
    }

    @Test
    fun testHeadless_smsScheme() {
        val number = parseNumberFromUri("sms:10086")
        assertEquals("10086", number)

        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.HEADLESS,
            address = number,
            body = "Headless sms scheme test",
            subscriptionId = 1,
            threadId = 501L,
            requireDeliveryReport = false,
            messageUri = "content://sms/9101"
        )
        val opId = SmsSendCoordinator.beginSend(context, sendContext)
        assertNotNull(opId)

        Thread.sleep(200)
        val dao = context.getMessagesDB().SmsSendDao()
        val op = runBlocking { dao.getOperationById(opId!!) }
        assertNotNull(op)
        assertEquals(SmsSendTriggerType.HEADLESS.name, op?.triggerType)
    }

    @Test
    fun testHeadless_smstoScheme() {
        val number = parseNumberFromUri("smsto:10010")
        assertEquals("10010", number)

        val sendContext = SmsSendContext(
            triggerType = SmsSendTriggerType.HEADLESS,
            address = number,
            body = "Headless smsto scheme test",
            subscriptionId = 1,
            threadId = 502L,
            requireDeliveryReport = false,
            messageUri = "content://sms/9102"
        )
        val opId = SmsSendCoordinator.beginSend(context, sendContext)
        assertNotNull(opId)

        Thread.sleep(200)
        val dao = context.getMessagesDB().SmsSendDao()
        val op = runBlocking { dao.getOperationById(opId!!) }
        assertNotNull(op)
        assertEquals(SmsSendTriggerType.HEADLESS.name, op?.triggerType)
    }

    @Test
    fun testHeadless_mmsScheme() {
        val number = parseNumberFromUri("mms:10000")
        assertEquals("10000", number)
    }

    @Test
    fun testHeadless_mmstoScheme() {
        val number = parseNumberFromUri("mmsto:10001")
        assertEquals("10001", number)
    }

    @Test
    fun testHeadless_nullDataString_safeFallback() {
        val number = parseNumberFromUri(null)
        assertEquals("", number)
    }
}
