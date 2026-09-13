package org.fossify.messages.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P0-5「首次安装即转发 7 天历史短信」回归锁。
 *
 * 背景：`SmsRecoveryWorker` 是 `CoroutineWorker`，`doWork()` 强依赖 `Context` /
 * `ContentResolver` / WorkManager，而本源集是纯 JVM 单测（`app/build.gradle` 未配置
 * Robolectric），无法直接构造实例。因此本测试采用两层防护：
 *
 * 1. **源码守卫**：直接读 `SmsRecoveryWorker.kt` 源码断言关键不变量 —— 任一条被改回
 *    旧写法（首装回溯 7 天 / 深回溯回拨水位 / 首装仍回放深回溯窗口），本测试立刻变红。
 * 2. **规格镜像**：把水位计算逻辑按生产实现 1:1 抄成纯函数，锁定「给定输入 → since /
 *    待写入水位」的期望值，作为活文档。
 *
 * 若后续把这段计算从 `doWork()` 抽成 `internal` 纯函数，第 2 层应改为直接调用生产函数，
 * 第 1 层即可删除。
 */
class SmsRecoveryWaterMarkTest {

    // ============================================================
    // 第 1 层：源码守卫（防止 P0-5 修复被回退）
    // ============================================================

    @Test
    fun firstLookbackConstantMustNotComeBack() {
        val src = sourceOfWorker()
        assertFalse(
            "FIRST_LOOKBACK_MS 已删除，首装不应再存在任何回溯窗口常量；" +
                "若重新引入，新装用户第一次扫描会全量转发历史短信（P0-5）",
            src.contains("FIRST_LOOKBACK_MS"),
        )
        assertFalse(
            "水位缺省值不得再是 now - N 天（首装水位必须是 0L）",
            Regex("""getLong\(\s*KEY_LAST_CHECKED\s*,\s*now\s*-""").containsMatchIn(src),
        )
    }

    @Test
    fun firstInstallMustScanFromNowOnly() {
        val src = sourceOfWorker()
        assertTrue(
            "必须显式区分水位是否存在（firstRun 判定，或 storedLastChecked > 0L 判定）",
            FIRST_RUN_DECL.containsMatchIn(src) || STORED_WATER_MARK_DECL.containsMatchIn(src),
        )
        // 两种等价写法都接受：if (firstRun) { now } else {...}  /  if (storedLastChecked > 0L) {...} else { now }
        val formA = Regex("""val\s+baseSince\s*=\s*if\s*\(\s*firstRun\s*\)\s*\{\s*now\s*\}\s*else\s*\{""")
        val formB = Regex("""val\s+baseSince\s*=\s*if\s*\(\s*storedLastChecked\s*>\s*0L\s*\)[\s\S]{0,240}?else\s*\{\s*now\s*\}""")
        assertTrue(
            "水位缺失时 baseSince 必须取 now（只登记水位、不回溯历史短信）",
            formA.containsMatchIn(src) || formB.containsMatchIn(src),
        )
    }

    @Test
    fun firstInstallMustIgnoreDeepResyncInput() {
        val src = sourceOfWorker()
        assertTrue(
            "首装时必须忽略一次性深回溯入参 KEY_FORCED_SINCE；" +
                "否则开机广播 / 回前台触发的 FullResync 会让新装用户首次扫描就回放最近 N 条历史短信",
            Regex("""forcedSince\s*=\s*if\s*\(\s*firstRun\s*\)\s*0L\s*else""").containsMatchIn(src),
        )
    }

    @Test
    fun lookbackWindowsMustStayNarrowed() {
        val src = sourceOfWorker()
        assertTrue(
            "MAX_LOOKBACK_MS 必须保持 6 小时（原 7 天）",
            Regex("""MAX_LOOKBACK_MS\s*=\s*6\s*\*\s*60\s*\*\s*60\s*\*\s*1000L""").containsMatchIn(src),
        )
        assertTrue(
            "FULL_RESYNC_LOOKBACK_MS 必须保持 1 小时（原 24 小时）",
            Regex("""FULL_RESYNC_LOOKBACK_MS\s*=\s*60\s*\*\s*60\s*\*\s*1000L""").containsMatchIn(src),
        )
    }

    @Test
    fun waterMarkMustAlwaysBePersisted() {
        val src = sourceOfWorker()
        assertTrue(
            "doWork() 结束前必须把水位写回 KEY_LAST_CHECKED，否则下次进入仍被当作首次安装，" +
                "历史短信会一直悬着、一旦某次写入失败就补爆",
            Regex("""prefs\.edit\(\)\.putLong\(\s*KEY_LAST_CHECKED\s*,\s*newestSeen\s*\)""").containsMatchIn(src),
        )
    }

    @Test
    fun fullResyncMustNotRewindWaterMark() {
        val src = sourceOfWorker()
        assertFalse(
            "enqueueFullResync 不得再回拨 KEY_LAST_CHECKED 水位；" +
                "回拨会把补偿窗口持久化，下一次扫描对同一批历史短信再次全量转发",
            src.contains("putLong(KEY_LAST_CHECKED, forcedSince)"),
        )
        assertTrue(
            "深回溯应改为一次性入参下发：workDataOf(KEY_FORCED_SINCE to ...)",
            Regex("""workDataOf\(\s*KEY_FORCED_SINCE\s+to\s+\(""").containsMatchIn(src),
        )
        assertTrue(
            "forcedSince 只允许影响本次扫描的 since，不得写回水位",
            Regex("""val\s+since\s*=\s*if\s*\(\s*forcedSince\s*>\s*0L\s*\)\s*minOf\(\s*baseSince\s*,\s*forcedSince\s*\)\s*else\s*baseSince""")
                .containsMatchIn(src),
        )
    }

    // ============================================================
    // 第 2 层：水位计算规格（与生产实现 1:1 的纯函数镜像）
    // ============================================================

    private val overlapMs = 60_000L
    private val maxLookbackMs = 6 * 60 * 60 * 1000L
    private val fullResyncLookbackMs = 60 * 60 * 1000L

    private data class ScanPlan(val since: Long, val waterMark: Long)

    /** `SmsRecoveryWorker.doWork()` 水位段逻辑的镜像实现（见生产文件 :52-65、:226）。 */
    private fun plan(
        storedLastChecked: Long,
        now: Long,
        forcedSince: Long = 0L,
        newestMessageDate: Long? = null,
    ): ScanPlan {
        val firstRun = storedLastChecked <= 0L
        val lastChecked = if (firstRun) now else storedLastChecked
        val baseSince = if (firstRun) {
            now
        } else {
            (lastChecked - overlapMs).coerceAtLeast(now - maxLookbackMs)
        }
        val effectiveForcedSince = if (firstRun) 0L else forcedSince
        val since = if (effectiveForcedSince > 0L) minOf(baseSince, effectiveForcedSince) else baseSince
        var newestSeen = lastChecked
        if (newestMessageDate != null) newestSeen = maxOf(newestSeen, newestMessageDate)
        return ScanPlan(since = since, waterMark = newestSeen)
    }

    @Test
    fun firstInstallDoesNotBackfillHistory() {
        val now = 1_700_000_000_000L
        val p = plan(storedLastChecked = 0L, now = now)

        // 关键断言：since 必须等于 now，而不是 now - 7 天
        assertEquals("首装扫描起点必须是 now，不得回溯历史短信", now, p.since)
        assertTrue("首装窗口不得为负", p.since >= now)
        assertFalse("首装不得再出现天级回溯", p.since <= now - 24 * 60 * 60 * 1000L)
    }

    @Test
    fun firstInstallStillRegistersWaterMark() {
        val now = 1_700_000_000_000L
        val p = plan(storedLastChecked = 0L, now = now)

        assertTrue("首装即使一条短信都没扫到，也必须登记水位（>0）", p.waterMark > 0L)
        assertEquals("首装水位必须登记为 now", now, p.waterMark)

        // 第二次进入：以刚才写入的水位为准，不再走首装分支
        val second = plan(storedLastChecked = p.waterMark, now = now + 15 * 60 * 1000L)
        assertEquals(
            "第二次扫描应只回补 15 分钟 + 1 分钟重叠，而不是重新触发首装逻辑",
            now - overlapMs,
            second.since,
        )
    }

    @Test
    fun firstInstallIgnoresOneOffDeepResync() {
        val now = 1_700_000_000_000L
        val p = plan(storedLastChecked = 0L, now = now, forcedSince = now - fullResyncLookbackMs)

        assertEquals(
            "首装即使收到一次性深回溯入参，也必须只扫 now 之后的短信",
            now,
            p.since,
        )
        assertEquals("首装水位仍登记为 now，深回溯不得回拨", now, p.waterMark)
    }

    @Test
    fun steadyStateScanIsCappedByMaxLookback() {
        val now = 1_700_000_000_000L

        // 水位落后 10 小时（OEM 冻结 / 关机）：最多只回补 6 小时
        val stale = plan(storedLastChecked = now - 10 * 60 * 60 * 1000L, now = now)
        assertEquals(now - maxLookbackMs, stale.since)

        // 水位只落后 10 分钟：正常走重叠窗口
        val fresh = plan(storedLastChecked = now - 10 * 60 * 1000L, now = now)
        assertEquals(now - 10 * 60 * 1000L - overlapMs, fresh.since)
    }

    @Test
    fun forcedSinceNeverMovesWaterMarkBackwards() {
        val now = 1_700_000_000_000L
        val stored = now - 60_000L
        val p = plan(storedLastChecked = stored, now = now, forcedSince = now - fullResyncLookbackMs)

        assertEquals("非首装时深回溯应把本次窗口扩展到 1 小时", now - fullResyncLookbackMs, p.since)
        assertEquals("水位必须保持原值，不得被 forcedSince 拉回", stored, p.waterMark)
        assertTrue("水位单向前进", p.waterMark >= stored)
    }

    @Test
    fun waterMarkAdvancesToNewestMessage() {
        val now = 1_700_000_000_000L
        val stored = now - 5 * 60 * 1000L
        val newest = now - 30_000L
        val p = plan(storedLastChecked = stored, now = now, newestMessageDate = newest)

        assertEquals("水位应前进到本轮扫到的最新短信时间", newest, p.waterMark)
        assertTrue(p.waterMark > stored)
    }

    // ============================================================

    private companion object {
        val FIRST_RUN_DECL = Regex("""val\s+firstRun\s*=\s*storedLastChecked\s*<=\s*0L""")
        val STORED_WATER_MARK_DECL =
            Regex("""val\s+storedLastChecked\s*=\s*prefs\.getLong\(\s*KEY_LAST_CHECKED\s*,\s*0L\s*\)""")
    }

    private fun sourceOfWorker(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        repeat(6) {
            if (dir == null) return@repeat
            val candidate = File(dir, "src/main/kotlin/org/fossify/messages/messaging/SmsRecoveryWorker.kt")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile
        }
        throw AssertionError(
            "无法定位 SmsRecoveryWorker.kt 源码；user.dir=${System.getProperty("user.dir")}",
        )
    }
}
