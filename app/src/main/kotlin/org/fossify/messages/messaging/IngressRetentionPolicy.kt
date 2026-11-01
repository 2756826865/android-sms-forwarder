package org.fossify.messages.messaging

/** Retained retry history and queue submissions keep their original frozen SIM snapshots. */
internal object IngressRetentionPolicy {
    fun referenced(roots: List<String>, workIds: Set<String>): Boolean = roots.any { root ->
        workIds.any { it == root || it.startsWith("$root-") }
    }

    fun canExpireSnapshot(requestId: String, references: Set<String>): Boolean =
        requestId !in references
}
