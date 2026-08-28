package org.fossify.messages.forwarding.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.fossify.messages.forwarding.plugin.impl.HttpChannelPlugin
import org.fossify.messages.forwarding.plugin.impl.SmsDirectChannelPlugin
import org.fossify.messages.forwarding.plugin.model.ChannelResult
import org.fossify.messages.forwarding.plugin.model.ForwardPayload
import org.fossify.messages.outbox.OutboxExecutionResult
import org.fossify.messages.models.OutboxTaskEntity
import org.fossify.messages.models.OutboxTaskState
import org.fossify.messages.models.OutboxTaskType
import org.fossify.messages.outbox.ForwardPluginOutboxExecutor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ForwardChannelPluginTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun testPluginRegistrationAndDiscovery() {
        val testPlugin = object : ForwardChannelPlugin {
            override val pluginId: String = "test_custom_plugin"
            override val displayName: String = "Test Custom Plugin"
            override fun validateConfig(config: Map<String, String>): Boolean = true
            override suspend fun send(context: Context, payload: ForwardPayload): ChannelResult = ChannelResult.Success
        }

        ChannelPluginManager.registerPlugin(testPlugin)
        val resolved = ChannelPluginManager.getPlugin("test_custom_plugin")

        assertNotNull(resolved)
        assertEquals("test_custom_plugin", resolved?.pluginId)
        assertEquals("Test Custom Plugin", resolved?.displayName)

        ChannelPluginManager.unregisterPlugin("test_custom_plugin")
        assertNull(ChannelPluginManager.getPlugin("test_custom_plugin"))
    }

    @Test
    fun testHttpPluginConfigValidation() {
        val plugin = HttpChannelPlugin("test_http", "Test HTTP")

        assertTrue(plugin.validateConfig(mapOf("url" to "https://api.example.com/webhook")))
        assertTrue(plugin.validateConfig(mapOf("webhook_url" to "http://192.168.1.100:8080/hook")))
        assertFalse(plugin.validateConfig(mapOf("url" to "ftp://invalid-url")))
        assertFalse(plugin.validateConfig(emptyMap()))
    }

    @Test
    fun testSmsDirectPluginConfigValidation() {
        val plugin = SmsDirectChannelPlugin()

        assertTrue(plugin.validateConfig(mapOf("target_phone" to "10086")))
        assertTrue(plugin.validateConfig(mapOf("destination" to "13800138000")))
        assertFalse(plugin.validateConfig(emptyMap()))
    }

    @Test
    fun testUnknownPluginSafetyFailInExecutor() = runBlocking {
        val executor = ForwardPluginOutboxExecutor()
        val task = OutboxTaskEntity(
            taskId = "task-unknown-" + UUID.randomUUID(),
            taskType = OutboxTaskType.FORWARD_PLUGIN,
            sourceType = "FORWARDING_RULE",
            sourceId = "rule-1",
            payloadHmac = "hmac",
            payloadPayload = """{"pluginId":"non_existent_plugin_xyz"}""",
            state = OutboxTaskState.RUNNING.name
        )

        val result = executor.execute(context, task)
        assertTrue(result is OutboxExecutionResult.FatalFailure)
        assertEquals("UnknownPlugin", (result as OutboxExecutionResult.FatalFailure).errorClass)
    }

    @Test
    fun testOutboxTaskCorrectlyMappedToPluginExecutor() = runBlocking {
        val executor = ForwardPluginOutboxExecutor()
        assertTrue(executor.canExecute(OutboxTaskType.FORWARD_PLUGIN))
        assertTrue(executor.canExecute(OutboxTaskType.FORWARD_HTTP))
        assertTrue(executor.canExecute(OutboxTaskType.FORWARD_SMS))
        assertFalse(executor.canExecute(OutboxTaskType.SEND_SMS))
    }
}
