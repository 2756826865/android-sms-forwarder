package org.fossify.messages.remote
// Host-only replacement for the Android SMS dependency. No network or telephony operations.
data class RemoteSmsCommand(val targetNumber: String, val content: String) {
    companion object {
        fun parse(content: String, customPrefix: String = ""): RemoteSmsCommand? {
            val prefix = customPrefix.ifBlank { "/发信" }
            if (!content.startsWith("$prefix ")) return null
            val parts = content.removePrefix(prefix).trim().split(" ", limit = 2)
            return if (parts.size == 2) RemoteSmsCommand(parts[0], parts[1]) else null
        }
    }
}
