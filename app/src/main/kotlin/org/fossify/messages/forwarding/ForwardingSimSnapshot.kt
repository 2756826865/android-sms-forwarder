package org.fossify.messages.forwarding

data class ForwardingSimSnapshot(
    val slotIndex: Int,
    val description: String,
    val receiverNumber: String,
    val customLabel: String,
    val subscriptionId: Int = -1,
    val status: String = if (slotIndex >= 0) "CONFIRMED" else "UNKNOWN",
    val source: String = "legacy",
    val observedAt: Long = 0L,
)
