package org.fossify.messages.forwarding

import androidx.work.Data
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class ForwardingQueueSnapshotTest {
    @Test fun roundTripPreservesOriginalRoutingRenderedBodyAndPrimitiveTypes() {
        val original = Data.Builder().putString("target_instance_id", "original-instance")
            .putString("body", "SIM2 · 家人号1234\n验证码：****")
            .putBoolean("body_already_rendered", true).putInt("subscription_id", 37)
            .putLong("received_at", 0L).putString("group_execution_plan", "")
            .putString("sms_direct_source", "worker:original-source")
            .putString("nullable", null).build()
        val encoded = Base64.getEncoder().encodeToString(original.toByteArray())
        val restored = Data.fromByteArray(Base64.getDecoder().decode(encoded))
        assertEquals(original, restored)
        assertEquals("original-instance", restored.getString("target_instance_id"))
        assertEquals("SIM2 · 家人号1234\n验证码：****", restored.getString("body"))
        assertEquals(37, restored.getInt("subscription_id", -1))
        assertEquals(0L, restored.getLong("received_at", -1L))
        assertTrue(restored.getBoolean("body_already_rendered", false))
        assertEquals("worker:original-source", restored.getString("sms_direct_source"))
    }
}
