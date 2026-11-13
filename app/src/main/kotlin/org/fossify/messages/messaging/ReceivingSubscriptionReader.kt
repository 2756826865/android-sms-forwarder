package org.fossify.messages.messaging

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.fossify.messages.extensions.subscriptionManagerCompat

/** A fresh read for every observation; no mapping survives a SIM/permission change. */
internal object ReceivingSubscriptionReader {
    fun slotForSubscription(context: Context, sub: Int): Int? =
        try {
            val active = context.subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()
            val info = active.filter { it.subscriptionId == sub }.singleOrNull()
            info?.simSlotIndex?.takeIf { slot ->
                slot >= 0 && active.count { it.simSlotIndex == slot } == 1
            }
        } catch (_: Exception) {
            null
        }

    fun resolveFromBundle(context: Context, extras: android.os.Bundle?): Int {
        val active = try {
            if (context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                context.subscriptionManagerCompat().activeSubscriptionInfoList.orEmpty()
            } else {
                emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }

        if (extras != null) {
            val rawMap = (ReceivingIdentityPolicy.subscriptionKeys +
                    ReceivingIdentityPolicy.slotKeys +
                    ReceivingIdentityPolicy.ambiguousVendorKeys)
                .filter { extras.containsKey(it) }
                .associateWith { @Suppress("DEPRECATION") extras.get(it) }

            if (rawMap.isNotEmpty()) {
                val activeMapped = active.map {
                    ReceivingIdentityPolicy.ActiveSubscription(it.subscriptionId, it.simSlotIndex)
                }
                val result = ReceivingIdentityPolicy.resolve(
                    extras = rawMap,
                    active = activeMapped,
                    readStatus = ReceivingIdentityPolicy.ReadStatus.AVAILABLE,
                    observedAt = System.currentTimeMillis()
                )
                if (result.confirmedSubscriptionId != null && result.confirmedSubscriptionId >= 0) {
                    return result.confirmedSubscriptionId
                }
            }
        }

        if (active.size == 1) {
            val singleSubId = active.first().subscriptionId
            if (singleSubId >= 0) {
                return singleSubId
            }
        }

        return android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    fun read(context: Context, intent: Intent): ReceivingIdentityPolicy.Result {
        val extras =
            (ReceivingIdentityPolicy.subscriptionKeys +
                    ReceivingIdentityPolicy.slotKeys +
                    ReceivingIdentityPolicy.ambiguousVendorKeys)
                .filter { intent.hasExtra(it) }
                .associateWith {
                    @Suppress("DEPRECATION") intent.extras?.get(it)
                }
        var status = ReceivingIdentityPolicy.ReadStatus.AVAILABLE
        val active =
            if (
                context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                status = ReceivingIdentityPolicy.ReadStatus.PERMISSION_DENIED
                emptyList()
            } else
                try {
                    context
                        .subscriptionManagerCompat()
                        .activeSubscriptionInfoList
                        .orEmpty()
                        .map {
                            ReceivingIdentityPolicy.ActiveSubscription(
                                it.subscriptionId,
                                it.simSlotIndex,
                            )
                        }
                        .also {
                            if (it.isEmpty()) status = ReceivingIdentityPolicy.ReadStatus.EMPTY
                        }
                } catch (_: SecurityException) {
                    status = ReceivingIdentityPolicy.ReadStatus.PERMISSION_DENIED
                    emptyList()
                } catch (_: Exception) {
                    status = ReceivingIdentityPolicy.ReadStatus.ERROR
                    emptyList()
                }
        return ReceivingIdentityPolicy.resolve(extras, active, status, System.currentTimeMillis())
            .also {
                context
                    .getSharedPreferences("receiving_identity_diagnostics", Context.MODE_PRIVATE)
                    .edit()
                    .putString("status", it.status.name)
                    .putString("reason", it.reason)
                    .putString("field", it.fieldName)
                    .putString("source", it.source.name)
                    .putLong("observed_at", it.observedAt)
                    .apply()
            }
    }
}
