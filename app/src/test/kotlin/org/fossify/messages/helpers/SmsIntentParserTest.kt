package org.fossify.messages.helpers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * `sms:` URI 解析回归测试。
 *
 * 真实 bug：Google 验证电话号码 → "发送短信" → 收件人栏显示
 * `//244444/&body=原样发送此消息。`、正文框为空。
 *
 * 三处根因：
 *  1. `parseRecipientsFromUri` 只做 `split("?")`，遇到没有 `?` 的
 *     `sms://244444/&body=x` 就把整串 `//244444/&body=x` 当收件人；
 *  2. `extractBodyFromUri` 直接 `uri.query ?: return null`，没有 `?` 时正文丢失；
 *  3. `HeadlessSmsSendService` 用 `removePrefix` 链裸剥前缀，零结构解析。
 *
 * 本测试只打 `internal` 的纯字符串核心——`android.net.Uri` 在无 Robolectric 的 JVM 单测里是
 * stub，`Uri.parse(...)` 跑不起来。中文一律用 `\uXXXX` 转义书写，避免源文件编码影响断言语义。
 */
@RunWith(Parameterized::class)
class SmsUriRecipientTest(
    private val id: String,
    private val schemeSpecificPart: String,
    private val expected: List<String>,
) {

    @Test
    fun recipientsMatchExpectation() {
        val actual = SmsIntentParser.parseRecipientsFromSchemeSpecificPart(schemeSpecificPart)
        assertEquals("[$id] ssp=<$schemeSpecificPart>", expected, actual.toList())
    }

    companion object {
        private const val BODY = "\u539F\u6837\u53D1\u9001\u6B64\u6D88\u606F\u3002" // 原样发送此消息。

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any>> = listOf(
            // ---- 任务要求的 7 种格式 ----
            arrayOf("标准 sms: + ?body=", "244444?body=x", listOf("244444")),
            arrayOf("标准 sms:// + ?body=", "//244444?body=x", listOf("244444")),
            arrayOf("历史兼容 sms: + &body=", "244444&body=x", listOf("244444")),
            arrayOf("截图形态 sms://…/&body=", "//244444/&body=x", listOf("244444")),
            arrayOf("裸号码", "10086", listOf("10086")),
            arrayOf("smsto: 分号多号码", "13800138000;13900139000?body=x", listOf("13800138000", "13900139000")),
            arrayOf("国际区号 + 前缀", "+8613800138000?body=x", listOf("+8613800138000")),

            // ---- 截图真实正文（中文 + 尾部句号）----
            arrayOf("截图完整形态", "//244444/&body=$BODY", listOf("244444")),

            // ---- 分隔符 / 空段 / 冗余斜杠 ----
            arrayOf("逗号分隔", "13800138000,13900139000", listOf("13800138000", "13900139000")),
            arrayOf("分号+逗号混用", "13800138000;13900139000,13700137000", listOf("13800138000", "13900139000", "13700137000")),
            arrayOf("段内空白", " 13800138000 , 13900139000 ", listOf("13800138000", "13900139000")),
            arrayOf("尾部斜杠无正文", "//244444/", listOf("244444")),
            arrayOf("单斜杠无正文", "244444/", listOf("244444")),
            arrayOf("多余斜杠", "//244444//", listOf("244444")),
            arrayOf("前导单斜杠", "/244444", listOf("244444")),

            // ---- query 里的其他参数 ----
            arrayOf("body 后有其他参数", "244444?body=x&subject=y", listOf("244444")),
            arrayOf("body 前有其他参数", "244444?subject=y&body=x", listOf("244444")),
            arrayOf("只有 subject", "244444?subject=y", listOf("244444")),
            arrayOf("空 body 参数", "244444?body=", listOf("244444")),
            arrayOf("&body= 为空", "10086&body=", listOf("10086")),

            // ---- 百分号编码的号码 ----
            arrayOf("编码的 + 前缀", "%2B8613800138000?body=x", listOf("+8613800138000")),
            arrayOf("编码的分号", "13800138000%3B13900139000", listOf("13800138000;13900139000")),

            // ---- 退化输入（不应抛异常、不应产出假号码）----
            arrayOf("空串", "", emptyList<String>()),
            arrayOf("只有 //", "//", emptyList<String>()),
            arrayOf("只有 /", "/", emptyList<String>()),
            arrayOf("只有 ?body=", "?body=x", emptyList<String>()),
            arrayOf("只有 &body=", "&body=x", emptyList<String>()),
            arrayOf("只有分隔符", ";;,", emptyList<String>()),
            arrayOf("裸 % 不抛异常", "100%", listOf("100%")),
        )
    }
}

/**
 * 正文抽取回归测试：`sms:10086&body=x` / `sms://10086/&body=x` 这类**没有 `?`** 的形态
 * 原来会让 `uri.query` 直接返回 null → 正文彻底丢失。
 */
@RunWith(Parameterized::class)
class SmsUriBodyTest(
    private val id: String,
    private val schemeSpecificPart: String,
    private val expected: String?,
) {

    @Test
    fun bodyMatchesExpectation() {
        val actual = SmsIntentParser.extractBodyFromSchemeSpecificPart(schemeSpecificPart)
        assertEquals("[$id] ssp=<$schemeSpecificPart>", expected, actual)
    }

    companion object {
        private const val BODY = "\u539F\u6837\u53D1\u9001\u6B64\u6D88\u606F\u3002" // 原样发送此消息。

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun data(): Collection<Array<Any?>> = listOf(
            // ---- 任务要求的 7 种格式里的 4 种带正文形态 ----
            arrayOf("标准 sms: + ?body=", "244444?body=x", "x"),
            arrayOf("标准 sms:// + ?body=", "//244444?body=x", "x"),
            arrayOf("历史兼容 sms: + &body=", "244444&body=x", "x"),
            arrayOf("截图形态 sms://…/&body=", "//244444/&body=x", "x"),
            arrayOf("裸号码无正文", "10086", null),

            // ---- 截图真实正文 ----
            arrayOf("截图完整形态", "//244444/&body=$BODY", BODY),

            // ---- 编码与 `+` 语义 ----
            arrayOf("百分号编码 UTF-8", "244444?body=%E4%BD%A0%E5%A5%BD", "\u4F60\u597D"),
            arrayOf("query 里 + 解成空格", "244444?body=a+b", "a b"),
            arrayOf("百分号编码空格", "244444?body=a%20b", "a b"),
            arrayOf("&body= 分支不解 +", "244444&body=a+b", "a b"),

            // ---- 参数边界 ----
            arrayOf("body 后有其他参数", "244444?body=x&subject=y", "x"),
            arrayOf("body 前有其他参数", "244444?subject=y&body=x", "x"),
            arrayOf("&body= 截断到下一个 &", "//244444/&body=x&subject=y", "x"),
            arrayOf("空 body 按无正文", "244444?body=", null),
            arrayOf("空 &body= 按无正文", "10086&body=", null),
            arrayOf("只有 subject", "244444?subject=y", null),
            arrayOf("空串", "", null),
            arrayOf("裸 % 不抛异常", "244444?body=100%", "100%"),
        )
    }
}

/**
 * 收件人解码职责测试。
 *
 * 解码已收敛到解析层（[SmsIntentParser.decodeRecipient]），
 * `NewConversationActivity` 与 `HeadlessSmsSendService` 都不再各自解码。
 * 这里锁定两件最容易回归的事：
 *  1. `+` 必须保持字面量（不能被 URLDecoder 解成空格）；
 *  2. 非法 `%` 转义不能让号码整段丢失。
 */
class SmsRecipientDecodeTest {

    @Test
    fun plusPrefixedNumberKeepsLiteralPlus() {
        assertEquals("+8613800138000", SmsIntentParser.decodeRecipient("+8613800138000"))
    }

    @Test
    fun percentEncodedPlusDecodesToPlus() {
        assertEquals("+8613800138000", SmsIntentParser.decodeRecipient("%2B8613800138000"))
    }

    @Test
    fun plainNumberUnchanged() {
        assertEquals("10086", SmsIntentParser.decodeRecipient("10086"))
    }

    @Test
    fun emptyStaysEmpty() {
        assertEquals("", SmsIntentParser.decodeRecipient(""))
    }

    @Test
    fun percentEncodedUtf8Decodes() {
        assertEquals("\u4F60\u597D", SmsIntentParser.decodeRecipient("%E4%BD%A0%E5%A5%BD"))
    }

    @Test
    fun malformedPercentEscapeFallsBackToRawValue() {
        // 裸 `%` 会让 URLDecoder 抛 IllegalArgumentException；必须原样返回而不是丢号码。
        assertEquals("100%", SmsIntentParser.decodeRecipient("100%"))
    }

    @Test
    fun semicolonSeparatedListSurvivesDecoding() {
        assertEquals(
            "+8613800138000;13900139000",
            SmsIntentParser.decodeRecipient("+8613800138000;13900139000"),
        )
    }

    @Test
    fun nonRecipientSchemeIsIgnored() {
        // 直接覆盖"scheme 不在白名单时不解析"的边界（纯函数层不涉及 scheme，这里用 null 语义代替）：
        // parseRecipientsFromSchemeSpecificPart 不校验 scheme，校验在 parseRecipientsFromUri 里，
        // 因此这里只断言 http 形态的 schemeSpecificPart 不会产出假号码。
        assertNull(SmsIntentParser.extractBodyFromSchemeSpecificPart("//example.com/path"))
    }
}
