package org.fossify.messages.services

internal data class BackgroundServiceSnapshot(
    val smsGuard: Boolean,
    val runnableSources: Int,
    val text: String,
) {
    val required: Boolean
        get() = smsGuard || runnableSources > 0
}
