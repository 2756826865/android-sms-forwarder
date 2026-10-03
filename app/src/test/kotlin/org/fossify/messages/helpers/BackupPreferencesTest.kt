package org.fossify.messages.helpers

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BackupPreferencesTest {
    @Test fun typedRoundTripPreservesIntegerLongAndStringSet() {
        val values = mapOf("int" to 4, "long" to 1791020000000L, "float" to 0.5f,
            "enabled" to true, "secret" to "quoted\nvalue", "set" to setOf("a", "b"), "empty" to emptySet<String>())
        assertEquals(values, BackupPreferences.decode(JSONObject(BackupPreferences.encode(values).toString())))
    }
    @Test fun incompatibleFlagTypeIsRejectedBeforeRestore() {
        val settings = JSONObject().put("multi_channel_forwarding", BackupPreferences.encode(mapOf("root_enhancement_enabled" to "true")))
        assertThrows(IllegalArgumentException::class.java) { BackupPreferences.parse(settings) }
    }
    @Test fun arbitraryNamespaceCannotOverwriteApplicationPreferences() {
        val settings = JSONObject().put("Prefs", BackupPreferences.encode(mapOf("unrelated" to true)))
        assertThrows(IllegalArgumentException::class.java) { BackupPreferences.parse(settings) }
    }
    @Test fun credentialKeyCannotBeSmuggledIntoPortableSettings() {
        val settings = JSONObject().put("multi_channel_forwarding", BackupPreferences.encode(mapOf("email_password" to "plaintext")))
        assertThrows(IllegalArgumentException::class.java) { BackupPreferences.parse(settings) }
    }
    @Test fun ComposerOffsetAndHeartbeatIntervalAreValidated() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupPreferences.parse(JSONObject().put("thread_layout", BackupPreferences.encode(mapOf("composer_offset_dp" to -1))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupPreferences.parse(JSONObject().put("heartbeat_config", BackupPreferences.encode(mapOf("heartbeat_interval_hours" to 0))))
        }
    }
    @Test fun fractionalIntegerCannotBeCoercedSilently() {
        val entry = JSONObject().put("type", "int").put("value", 1.5)
        assertThrows(IllegalArgumentException::class.java) { BackupPreferences.decode(JSONObject().put("number", entry)) }
    }
    @Test fun simNumbersAndFallbackRemainStrings() {
        val json = JSONObject().put("multi_channel_forwarding", BackupPreferences.encode(mapOf("sim_one_number" to "00123")))
            .put("forwarding_fallback", BackupPreferences.encode(mapOf("primary-id" to "backup-id")))
        val restored = BackupPreferences.parse(json)
        assertEquals("00123", restored.getValue("multi_channel_forwarding")["sim_one_number"])
        assertEquals("backup-id", restored.getValue("forwarding_fallback")["primary-id"])
    }
}
