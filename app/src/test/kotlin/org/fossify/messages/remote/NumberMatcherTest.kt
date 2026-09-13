package org.fossify.messages.remote

import org.fossify.messages.remote.repository.RemoteSourceInstance
import org.fossify.messages.remote.repository.RemoteSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * P0-1 远程发短信鉴权绕过 —— 回归验证矩阵。
 *
 * 覆盖三条已知漏洞路径：
 *  A. 白名单开关默认关闭（RemoteSourceRepository.kt whitelistEnabled 默认 false）
 *  B. 发件人无 ASCII 数字 → 归一化为空串 → 空串与任何串 endsWith 恒真
 *  C. endsWith 后缀通配（白名单 1069 被 13800121069 命中）
 *
 * Unicode 数字串一律用码点构造，避免测试源文件编码影响断言语义。
 */
private fun toArabicIndic(ascii: String): String =
    ascii.map { if (it in '0'..'9') (it.code - '0'.code + 0x0660).toChar() else it }.joinToString("")

private fun toFullwidth(ascii: String): String =
    ascii.map { if (it in '0'..'9') (it.code - '0'.code + 0xFF10).toChar() else it }.joinToString("")

/** 23 条可经由 NumberMatcher.equivalent 直接判定的矩阵用例。 */
@RunWith(Parameterized::class)
class NumberMatcherMatrixTest(
    private val id: String,
    private val scene: String,
    private val whitelist: String,
    private val sender: String,
    private val expected: Boolean,
) {

    @Test
    fun equivalentMatchesExpectation() {
        assertEquals(
            "[$id] $scene | whitelist=<$whitelist> sender=<$sender>",
            expected,
            NumberMatcher.equivalent(whitelist, sender),
        )
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun cases(): Collection<Array<Any>> = listOf(
            // ---- 路径 C：endsWith 后缀通配 ----
            arrayOf("R-01", "后缀绕过PoC", "1069", "13800121069", false),
            arrayOf("R-02", "短号vs手机号", "10086", "13800110086", false),
            // ---- 路径 B：发件人无 ASCII 数字 ----
            arrayOf("R-03", "字母发件人", "13800138000", "BANK", false),
            arrayOf("R-04", "空发件人", "13800138000", "", false),
            arrayOf("R-05", "纯符号发件人", "13800138000", "+-*#", false),
            // ---- Unicode 数字（不得放行，亦不得退化为空串后再放行）----
            arrayOf("R-06", "阿拉伯-印度数字", "1069", toArabicIndic("01234567890"), false),
            arrayOf("R-07", "全角数字", "13800138000", toFullwidth("13800138000"), false),
            // ---- 分机号 ----
            arrayOf("R-08", "分机号逗号应截断", "13800138000", "13800138000,123", true),
            arrayOf("R-09", "分机号拼接碰撞", "123", "13800138000;123", false),
            // ---- 国际前缀（修复后必须保持等价）----
            arrayOf("R-10", "国际前缀+86", "13800138000", "+8613800138000", true),
            arrayOf("R-11", "国际前缀0086", "13800138000", "008613800138000", true),
            arrayOf("R-12", "无+的86前缀", "13800138000", "8613800138000", true),
            // ---- takeLast(11) 定长截断绕过 ----
            arrayOf("R-13", "定长截断绕过", "13800138000", "9913800138000", false),
            // ---- 格式化差异（不得误杀）----
            arrayOf("R-14", "带空格", "13800138000", "138 0013 8000", true),
            arrayOf("R-15", "带横线", "13800138000", "138-0013-8000", true),
            arrayOf("R-16", "带括号", "(010) 1234 5678", "01012345678", true),
            arrayOf("R-17", "完全相同", "13800138000", "13800138000", true),
            // ---- 白名单条目全无数字（守卫必须保留）----
            arrayOf("R-21", "白名单全无数字+字母发件人", "BANK", "BANK", false),
            arrayOf("R-22", "白名单全无数字+数字发件人", "BANK", "13800138000", false),
            // ---- 海外号码 ----
            arrayOf("R-23", "海外号码正常等价", "+14155552671", "+14155552671", true),
            arrayOf("R-24", "海外号码脱国家码", "+14155552671", "4155552671", false),
            arrayOf("R-25", "他国号尾部碰撞", "13800138000", "+4913800138000", false),
            arrayOf("R-26", "86前缀不得误剥", "+18605551234", "+18605551234", true),
        )
    }
}

/** normalize() 直接断言，用于快速定位矩阵失败的根因。 */
class NumberMatcherNormalizeTest {

    @Test
    fun extensionIsTruncated() {
        assertEquals("13800138000", NumberMatcher.normalize("13800138000,123"))
        assertEquals("13800138000", NumberMatcher.normalize("13800138000;123"))
    }

    @Test
    fun cnPrefixIsStrippedConditionally() {
        assertEquals("13800138000", NumberMatcher.normalize("+8613800138000"))
        assertEquals("13800138000", NumberMatcher.normalize("008613800138000"))
        assertEquals("13800138000", NumberMatcher.normalize("8613800138000"))
    }

    @Test
    fun noFixedLengthTruncation() {
        // 不得被截断到 11 位
        assertEquals("9913800138000", NumberMatcher.normalize("9913800138000"))
        // 德国号码不得被剥离出 "13800138000"
        assertEquals("4913800138000", NumberMatcher.normalize("+4913800138000"))
    }

    @Test
    fun overseasNumberIsNotMangled() {
        // 美国号码以 1 开头，不得被 "86" 剥离逻辑破坏
        assertEquals("18605551234", NumberMatcher.normalize("+18605551234"))
    }

    @Test
    fun nonAsciiDigitsProduceEmptyKey() {
        assertEquals("", NumberMatcher.normalize(toArabicIndic("01234567890")))
        assertEquals("", NumberMatcher.normalize("BANK"))
        assertEquals("", NumberMatcher.normalize("+-*#"))
        assertEquals("", NumberMatcher.normalize(""))
    }

    @Test
    fun separatorsAreRemoved() {
        assertEquals("13800138000", NumberMatcher.normalize("138 0013 8000"))
        assertEquals("13800138000", NumberMatcher.normalize("138-0013-8000"))
        assertEquals("01012345678", NumberMatcher.normalize("(010) 1234 5678"))
    }
}

/** 白名单归一化与开关默认值（路径 A / R-18 / R-19 / R-20）。 */
class NumberMatcherWhitelistTest {

    @Test
    fun r18NewSourceEnablesWhitelistByDefault() {
        // 路径 A：新建来源默认必须开启白名单，否则 equivalent 再严也拦不住
        val instance = RemoteSourceInstance(name = "test", type = RemoteSourceType.SMS)
        assertTrue("新建来源 whitelistEnabled 必须为 true", instance.whitelistEnabled)
        // 空白名单归一化后仍为空 → 运行时走 AUTHORIZED_USERS_REQUIRED 拒绝
        assertEquals(emptySet<String>(), NumberMatcher.normalizeWhitelist(emptyList()))
    }

    @Test
    fun r19BlankEntriesAreDropped() {
        val normalized = NumberMatcher.normalizeWhitelist(listOf("", "13800138000", "   "))
        assertEquals(setOf("13800138000"), normalized)
        assertTrue(NumberMatcher.equivalent(normalized.first(), "13800138000"))
    }

    @Test
    fun r20DuplicateEntriesAreDeduplicated() {
        val normalized = NumberMatcher.normalizeWhitelist(listOf("13800138000", "13800138000"))
        assertEquals(1, normalized.size)
        assertTrue(NumberMatcher.equivalent(normalized.first(), "13800138000"))
    }

    @Test
    fun shortCodeNeverMatchesMobileNumber() {
        // 路径 C 的不变量：任意短号都不应匹配任意手机号
        val shortCodes = listOf("1069", "10086", "10010", "123", "95588")
        val mobiles = listOf("13800121069", "13800110086", "13800138000", "9913800138000")
        shortCodes.forEach { s ->
            mobiles.forEach { m ->
                assertFalse("短号 <$s> 不应匹配手机号 <$m>", NumberMatcher.equivalent(s, m))
                assertFalse("短号 <$s> 不应匹配手机号 <$m>(反向)", NumberMatcher.equivalent(m, s))
            }
        }
    }

    @Test
    fun anyNonNumericSenderIsRejected() {
        // 路径 B 的不变量：任何无 ASCII 数字的发件人都不得通过
        val senders = listOf("", "BANK", "+-*#", "Verification", toArabicIndic("01234567890"), toFullwidth("13800138000"))
        val whitelists = listOf("13800138000", "1069", "10086", "BANK")
        senders.forEach { s ->
            whitelists.forEach { w ->
                assertFalse("发件人 <$s> 不应匹配白名单 <$w>", NumberMatcher.equivalent(w, s))
            }
        }
    }
}
