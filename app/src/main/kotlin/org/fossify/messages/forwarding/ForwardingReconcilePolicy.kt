package org.fossify.messages.forwarding

internal data class ForwardingWorkState(val state: String, val attempts: Int)

internal object ForwardingReconcilePolicy {
    const val GRACE_MS = 120_000L
    val pending = setOf("queued", "waiting_network", "retry", "running")
    enum class Decision { KEEP, ACTIVE, STOPPED_UNSTARTED, UNKNOWN }

    fun canRepair(works: List<ForwardingWorkState>, attempts: Int, ageMs: Long,
                  snapshotVersion: Int, allowed: Boolean, claimed: Boolean, isTest: Boolean, snapshotMatches: Boolean): Boolean =
        works.isNotEmpty() && works.all { it.state == "FAILED" && it.attempts == 0 } &&
            attempts == 0 && ageMs in 0..86_400_000L && snapshotVersion == 1 && allowed && !claimed && !isTest && snapshotMatches

    fun decide(status: String, attempts: Int, ageMs: Long, works: List<ForwardingWorkState>, repairClaimed: Boolean = false): Decision {
        // A submission error may happen after WorkManager committed the request.
        // Re-observe live ownership, but never auto-replay a result marked unknown.
        if (status == "unknown") return if (works.any { it.state in setOf("RUNNING", "ENQUEUED", "BLOCKED") }) Decision.ACTIVE else Decision.KEEP
        if (status !in pending) return Decision.KEEP
        if (works.any { it.state in setOf("RUNNING", "ENQUEUED", "BLOCKED") }) return Decision.ACTIVE
        if (repairClaimed) return if (ageMs < GRACE_MS) Decision.KEEP else Decision.UNKNOWN
        if (works.isEmpty()) return if (ageMs < GRACE_MS) Decision.KEEP else Decision.UNKNOWN
        if (attempts == 0 && works.all { it.attempts == 0 && it.state in setOf("FAILED", "CANCELLED") })
            return Decision.STOPPED_UNSTARTED
        return Decision.UNKNOWN
    }
}
