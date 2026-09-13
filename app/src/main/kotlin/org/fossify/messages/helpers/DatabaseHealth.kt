package org.fossify.messages.helpers

import android.content.Context

/**
 * 本地 Room 库（`conversations.db`）的健康状态登记。
 *
 * `MessagesDatabase` 已移除 `fallbackToDestructiveMigration()`：迁移失败或库损坏时不再静默清库，
 * 而是把原库 / -wal / -shm 重命名为 `*.corrupt-<时间戳>` 保留现场，再用全新空库启动，
 * 并在此登记「恢复模式」，供 UI 提示与日志排查使用。
 *
 * 系统短信库（Telephony Provider）不受影响，会话列表会在下次同步时重新从系统库灌回。
 */
object DatabaseHealth {

    private const val PREFS = "database_health"
    private const val KEY_RECOVERY_MODE = "recovery_mode"
    private const val KEY_DEGRADED_IN_MEMORY = "degraded_in_memory"
    private const val KEY_LAST_FAILURE_AT = "last_failure_at"
    private const val KEY_RECOVERY_NOTIFIED = "recovery_notified"

    /** 当前是否处于「迁移/损坏失败后以空库启动」的恢复模式。 */
    fun isRecoveryMode(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RECOVERY_MODE, false)

    /**
     * 是否已降级到内存库——比恢复模式更严重的一级。
     *
     * 此时磁盘库完全不可用（现场移不走，或空库仍建不起来），应用跑在内存库上：
     * 本地数据能读能写，但进程退出即全部丢失，属于「虚假的成功反馈」，必须明确告知用户。
     */
    fun isDegradedToInMemory(context: Context): Boolean =
        prefs(context).getBoolean(KEY_DEGRADED_IN_MEMORY, false)

    /** 上一次失败的时间戳；从未失败过返回 0。 */
    fun lastFailureAt(context: Context): Long =
        prefs(context).getLong(KEY_LAST_FAILURE_AT, 0L)

    /** 本次失败是否已经通知过用户（用于「只提示一次」）。 */
    fun hasNotifiedRecovery(context: Context): Boolean =
        prefs(context).getBoolean(KEY_RECOVERY_NOTIFIED, false)

    fun markRecoveryNotified(context: Context) {
        prefs(context).edit().putBoolean(KEY_RECOVERY_NOTIFIED, true).apply()
    }

    /**
     * 登记一次新的失败。会重置「已通知」与「内存降级」标志，
     * 保证每次失败都会再提示一次，且不会把上一次的内存降级状态带到这次。
     */
    fun markRecoveredFromFailure(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_RECOVERY_MODE, true)
            .putBoolean(KEY_DEGRADED_IN_MEMORY, false)
            .putBoolean(KEY_RECOVERY_NOTIFIED, false)
            .putLong(KEY_LAST_FAILURE_AT, System.currentTimeMillis())
            .apply()
    }

    /**
     * 在 [markRecoveredFromFailure] 之后调用：本次失败最终降级到了内存库。
     * 单独记录是因为它比「空库重启」更严重——本地写入不会落盘。
     */
    fun markDegradedToInMemory(context: Context) {
        prefs(context).edit().putBoolean(KEY_DEGRADED_IN_MEMORY, true).apply()
    }

    /** 用户已知悉提示后调用，同时退出恢复模式与内存降级状态。 */
    fun clearRecoveryMode(context: Context) {
        prefs(context).edit()
            .putBoolean(KEY_RECOVERY_MODE, false)
            .putBoolean(KEY_DEGRADED_IN_MEMORY, false)
            .apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
