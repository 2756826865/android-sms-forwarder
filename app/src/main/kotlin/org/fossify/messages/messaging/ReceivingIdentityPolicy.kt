package org.fossify.messages.messaging

/**
 * Pure receiving-evidence policy. Sending defaults and single-SIM inference are deliberately
 * absent.
 */
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
        val fieldName: String? = null,
    )

    val subscriptionKeys = listOf(
        "android.telephony.extra.SUBSCRIPTION_INDEX", "android.telephony.extra.SUBSCRIPTION_ID",
        "subscription", "subscription_id", "subscriptionIndex", "sub_id", "subId",
    )
    val slotKeys = listOf(
        "android.telephony.extra.SLOT_INDEX", "phone", "slot", "slot_id", "slotIndex",
        "phone_id", "phoneId", "simSlot", "slotIdx",
        )

    val ambiguousVendorKeys = listOf("simId", "sim_id", "simnum",
    )

    private data class Observation(
        val raw: Map<String, Any?>,
        val at: Long,
        val subscriptions: List<Int>,
        val slots: List<Int>,
    ) {
        val sub
            get() = subscriptions.firstOrNull()

        val slot
            get() = slots.firstOrNull()

        fun result(
            status: Status,
            reason: String,
            confirmed: Int? = null,
            confirmedSlot: Int? = null,
            source: Source = if (sub != null) Source.SUBSCRIPTION_FIELD else Source.NONE,
            observed: Int? = sub,
        ): Result {
            val field =
                when (source) {
                    Source.SLOT_MAPPING ->
                        slotKeys.firstOrNull { number(raw[it]) == slot && slot != null }
                    Source.SUBSCRIPTION_FIELD ->
                        subscriptionKeys.firstOrNull { number(raw[it]) == sub && sub != null }
                    Source.NONE -> null
                }
            return Result(
                observed,
                confirmed,
                confirmedSlot,
                source,
                status,
                reason,
                at,
                raw,
                field,
            )
        }
    }

    fun resolve(
        extras: Map<String, Any?>,
        active: List<ActiveSubscription>,
        readStatus: ReadStatus,
        observedAt: Long,
    ): Result {
        val observation =
            Observation(
                (subscriptionKeys + slotKeys + ambiguousVendorKeys)
                    .filter(extras::containsKey).associateWith(extras::get),
                observedAt,
                subscriptionKeys.mapNotNull { number(extras[it]) }.distinct(),
                slotKeys.mapNotNull { number(extras[it]) }.distinct(),
            )
        val usable = active.filter { number(it.subscriptionId) != null && it.slotIndex >= 0 }
        return when {
            observation.subscriptions.size > 1 || observation.slots.size > 1 ->
                observation.result(Status.CONFLICT, "conflicting_fields")
            readStatus != ReadStatus.AVAILABLE ->
                observation.result(
                    if (observation.sub == null) Status.UNKNOWN else Status.UNVERIFIED,
                    "subscriptions_${readStatus.name.lowercase()}",
                )
            observation.sub != null -> resolveSubscription(observation, usable)
            observation.slot != null -> resolveSlot(observation, usable)
            else ->
                observation.result(
                    Status.UNKNOWN,
                    when {
                        subscriptionKeys.any(observation.raw::containsKey) ->
                            "invalid_subscription_fields"
                        slotKeys.any(observation.raw::containsKey) -> "invalid_slot_fields"
                        ambiguousVendorKeys.any(observation.raw::containsKey) ->
                            "ambiguous_vendor_fields"
                        else -> "no_receiving_evidence"
                    },
                )
        }
    }

    private fun resolveSubscription(
        observation: Observation,
        active: List<ActiveSubscription>,
    ): Result {
            val matches = active.filter { it.subscriptionId == observation.sub }
            if (matches.size != 1) return observation.result(Status.UNVERIFIED, "subscription_not_uniquely_active")
            val info = matches.single()
        return when {
            observation.slot != null && observation.slot != info.slotIndex ->
                observation.result(Status.CONFLICT, "subscription_slot_conflict")
            active.count { it.slotIndex == info.slotIndex } != 1 ->
                observation.result(
                    Status.CONFIRMED,
                    "subscription_verified_slot_ambiguous",
                    confirmed = info.subscriptionId,
                )
            else ->
                observation.result(Status.CONFIRMED, "subscription_verified", confirmed = info.subscriptionId,
                    confirmedSlot = info.slotIndex)
        }
    }

    private fun resolveSlot(observation: Observation, active: List<ActiveSubscription>): Result {
            val matches = active.filter { it.slotIndex == observation.slot }
        return if (matches.size == 1) {
                val info = matches.single()
            observation.result(Status.CONFIRMED, "slot_mapped",
                confirmed = info.subscriptionId,
                confirmedSlot = info.slotIndex, source = Source.SLOT_MAPPING,
                observed = info.subscriptionId,
            )
        } else observation.result(Status.UNKNOWN, "slot_not_uniquely_active")
    }

    private fun number(value: Any?): Int? = when (value) {
        is Int -> value
        is Long -> value.takeIf { it in 0 until Int.MAX_VALUE.toLong() }?.toInt()
        // Strings require an explicit vendor contract, not a global heuristic.
        else -> null
    }?.takeIf { it in 0 until Int.MAX_VALUE }
}
