package org.fossify.messages.messaging

import android.content.Context

/**
 * Persisted requests are independent of WorkManager input and of broadcast observation watermarks.
 */
internal class SmsRecoveryScanState(context: Context) {
    private val prefs = context.getSharedPreferences("sms_recovery_scan_v128", Context.MODE_PRIVATE)

    data class Page(val date: Long, val id: Long, val until: Long, val generation: Long)

    fun request(since: Long) =
        synchronized(lock) {
            val old = prefs.getLong("requested_since", Long.MAX_VALUE)
            check(
                prefs
                    .edit()
                    .putLong("requested_since", minOf(old, since))
                    .putLong("generation", prefs.getLong("generation", 0) + 1)
                    .commit()
            )
        }

    fun begin(lastChecked: Long, now: Long, installAt: Long): Page =
        synchronized(lock) {
            val floor = prefs.getLong("floor", SmsRecoveryPlan.floor(lastChecked, installAt, now))
            if (!prefs.contains("floor")) check(prefs.edit().putLong("floor", floor).commit())
            val active = prefs.getLong("until", 0)
            if (active > 0)
                return@synchronized Page(
                    prefs.getLong("date", floor),
                    prefs.getLong("id", 0),
                    active,
                    prefs.getLong("active_generation", 0),
                )
            val since =
                SmsRecoveryPlan.since(
                    lastChecked,
                    prefs.getLong("requested_since", Long.MAX_VALUE),
                    floor,
                    now,
                )
            val generation = prefs.getLong("generation", 0)
            check(
                prefs
                    .edit()
                    .putLong("date", since)
                    .putLong("id", 0)
                    .putLong("until", now)
                    .putLong("active_generation", generation)
                    .putLong("requested_since", Long.MAX_VALUE)
                    .commit()
            )
            Page(since, 0, now, generation)
        }

    fun checkpoint(page: Page, date: Long, id: Long) =
        synchronized(lock) {
            check(prefs.getLong("active_generation", -1) == page.generation) { "stale_scan_owner" }
            check(prefs.edit().putLong("date", date).putLong("id", id).commit())
        }

    fun complete(page: Page) =
        synchronized(lock) {
            check(prefs.edit().putLong("until", 0).putLong("completed_until", page.until).commit())
        }

    fun hasActivePage(): Boolean = prefs.getLong("until", 0) > 0

    fun hasRequest(): Boolean = prefs.getLong("requested_since", Long.MAX_VALUE) != Long.MAX_VALUE

    fun observedStatus(): String =
        "检查点=${prefs.getLong("date",0)}/${prefs.getLong("id",0)}；扫描截至=${prefs.getLong("until",0)}；新增请求=${hasRequest()}"

    companion object {
        val lock = Any()
    }
}
