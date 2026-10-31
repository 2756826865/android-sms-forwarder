package org.fossify.messages.messaging

/** Pure receiving-evidence policy. Sending defaults and single-SIM inference are deliberately absent. */
internal object ReceivingIdentityPolicy {
    enum class ReadStatus { AVAILABLE, EMPTY, PERMISSION_DENIED, ERROR }
    enum class Status { CONFIRMED, UNVERIFIED, UNKNOWN, CONFLICT }
    enum class Source { SUBSCRIPTION_FIELD, SLOT_MAPPING, NONE }
    data class ActiveSubscription(val subscriptionId: Int, val slotIndex: Int)
    data class Result(
        val observedSubscriptionId: Int?,
        val confirmedSubscriptionId: Int?,
        val slotIndex: Int?,
        val source: Source,
        val status: Status,
        val reason: String,
        val observedAt: Long,
        val rawCandidates: Map<String, Any?>,
    )

    val subscriptionKeys = listOf(
        "android.telephony.extra.SUBSCRIPTION_INDEX", "android.telephony.extra.SUBSCRIPTION_ID",
        "subscription", "subscription_id", "subscriptionIndex", "sub_id",
    )
    val slotKeys = listOf(
        "android.telephony.extra.SLOT_INDEX", "phone", "slot", "slot_id", "slotIndex",
        "phone_id", "phoneId", "simId", "sim_id", "simnum",
    )

    fun resolve(
        extras: Map<String, Any?>,
        active: List<ActiveSubscription>,
        readStatus: ReadStatus,
        observedAt: Long,
    ): Result {
        val raw = (subscriptionKeys + slotKeys).filter(extras::containsKey).associateWith(extras::get)
        val subscriptions = subscriptionKeys.mapNotNull { key -> number(extras[key]) }.distinct()
        val slots = slotKeys.mapNotNull { key -> number(extras[key]) }.distinct()
        fun result(status: Status, reason: String, sub: Int? = subscriptions.firstOrNull(),
                   confirmed: Int? = null, slot: Int? = null,
                   source: Source = if (sub != null) Source.SUBSCRIPTION_FIELD else Source.NONE) =
            Result(sub, confirmed, slot, source, status, reason, observedAt, raw)
        if (subscriptions.size > 1 || slots.size > 1) return result(Status.CONFLICT, "conflicting_fields")
        val sub = subscriptions.singleOrNull()
        val slot = slots.singleOrNull()
        if (readStatus != ReadStatus.AVAILABLE) {
            return result(if (sub == null) Status.UNKNOWN else Status.UNVERIFIED,
                "subscriptions_${readStatus.name.lowercase()}")
        }
        val usable = active.filter { number(it.subscriptionId) != null && it.slotIndex >= 0 }
        if (sub != null) {
            val matches = usable.filter { it.subscriptionId == sub }
            if (matches.size != 1) return result(Status.UNVERIFIED, "subscription_not_uniquely_active")
            val info = matches.single()
            if (slot != null && slot != info.slotIndex) return result(Status.CONFLICT, "subscription_slot_conflict")
            return result(Status.CONFIRMED, "subscription_verified", confirmed = sub, slot = info.slotIndex)
        }
        if (slot != null) {
            val matches = usable.filter { it.slotIndex == slot }
            if (matches.size == 1) {
                val info = matches.single()
                return result(Status.CONFIRMED, "slot_mapped", sub = info.subscriptionId,
                    confirmed = info.subscriptionId, slot = slot, source = Source.SLOT_MAPPING)
            }
            return result(Status.UNKNOWN, "slot_not_uniquely_active")
        }
        return result(Status.UNKNOWN, "no_receiving_evidence")
    }

    private fun number(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.takeIf { it in 0 until Int.MAX_VALUE.toLong() }?.toInt()
        // Strings require an explicit vendor contract, not a global heuristic.
        else -> null
    }?.takeIf { it in 0 until Int.MAX_VALUE }
}
