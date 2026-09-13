package org.fossify.messages.helpers

import android.content.Intent
import android.net.Uri
import com.google.android.mms.ContentType
import java.io.UnsupportedEncodingException
import java.net.URLDecoder

// Base on https://cs.android.com/android/platform/superproject/main/+/main:packages/apps/Messaging/src/com/android/messaging/ui/conversation/LaunchConversationActivity.java
object SmsIntentParser {
    private const val SCHEME_SMS = "sms"
    private const val SCHEME_SMSTO = "smsto"
    private const val SCHEME_MMS = "mms"
    private const val SCHEME_MMSTO = "mmsto"
    private val SMS_MMS_SCHEMES = setOf(SCHEME_SMS, SCHEME_SMSTO, SCHEME_MMS, SCHEME_MMSTO)

    private const val MAX_RECIPIENT_LENGTH = 100
    private const val SMS_BODY = "sms_body"
    private const val ADDRESS = "address"

    private const val BODY_PARAM = "body="

    /**
     * Android 历史兼容格式：没有 `?`，`&body=` 直接跟在号码后面
     * （`sms:10086&body=x`、`sms://10086/&body=x`）。
     */
    private const val LEGACY_BODY_MARKER = "&body="

    fun parse(intent: Intent): Pair<String, String>? {
        val action = intent.action
        if (action != Intent.ACTION_SENDTO && action != Intent.ACTION_VIEW) {
            // Unsupported intent action
            return null
        }

        val recipients = parseRecipients(intent)
        val body = extractBodyFromIntent(intent)
        return body.orEmpty() to recipients
    }

    /**
     * `RESPOND_VIA_MESSAGE`（通知栏快速回复 / 车载免提等）入口。
     *
     * 与 [parse] 的差别只有两点，其余全部复用同一套 URI 解析，不维护第二套逻辑：
     * 1. 不做 ACTION 白名单——该路径的 action 是 `android.intent.action.RESPOND_VIA_MESSAGE`，
     *    而 [parse] 只认 `ACTION_SENDTO` / `ACTION_VIEW`；
     * 2. 正文兜底顺序把 `EXTRA_TEXT` 提到最前——快速回复的正文就放在这里，且该 intent 的
     *    `type` 通常为 null，而 [extractBodyFromIntent] 只在 `type == text/plain` 时才认 `EXTRA_TEXT`。
     *
     * @return (正文, `;` 分隔的收件人)。两者都可能是空串，调用方需自行判空。
     */
    fun parseRespondViaMessage(intent: Intent): Pair<String, String> {
        val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
        val body = if (!extraText.isNullOrEmpty()) extraText else extractBodyFromIntent(intent)
        return body.orEmpty() to parseRecipients(intent)
    }

    private fun parseRecipients(intent: Intent): String {
        // URI 段已在解析层解码（见 parseRecipientsFromSchemeSpecificPart）。
        val uriRecipients = parseRecipientsFromUri(intent.data)
        val extraAddress = intent.getStringExtra(ADDRESS)
        val extraEmail = intent.getStringExtra(Intent.EXTRA_EMAIL)
        val extraPhoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER)

        // extras 不是 URI 编码的，但历史实现是在 UI 层对"拼好的整串"解码，extras 同样被解过，
        // 因此这里也走同一套解码以保持行为一致（`+` 前缀号码经解码后不变，见 decodeRecipient）。
        val recipients = when {
            !extraAddress.isNullOrEmpty() -> arrayOf(decodeRecipient(extraAddress))
            !extraEmail.isNullOrEmpty() -> arrayOf(decodeRecipient(extraEmail))
            !uriRecipients.isNullOrEmpty() -> uriRecipients
            // 兜底放在 URI 之后：旧版 HeadlessSmsSendService 只在"没有 data URI"时才用
            // EXTRA_PHONE_NUMBER，保持同样的优先级，避免 URI 与 extra 同时存在时改变既有行为。
            !extraPhoneNumber.isNullOrEmpty() -> arrayOf(decodeRecipient(extraPhoneNumber))
            else -> emptyArray()
        }

        return recipients
            .filter { it.length < MAX_RECIPIENT_LENGTH }
            .joinToString(";")
    }

    private fun parseRecipientsFromUri(uri: Uri?): Array<String>? {
        if (uri == null || uri.scheme !in SMS_MMS_SCHEMES) return null
        return parseRecipientsFromSchemeSpecificPart(uri.schemeSpecificPart)
    }

    /**
     * 纯字符串核心：从 scheme 之后的部分（`Uri.getSchemeSpecificPart()`）里剥出收件人并解码。
     *
     * 抽成 `internal` 纯函数是为了可测——[android.net.Uri] 在无 Robolectric 的 JVM 单测里是 stub，
     * 直接对 `Uri.parse(...)` 断言跑不起来。
     *
     * 依次剥掉 scheme 之后可能出现的结构（**顺序重要**）：
     * 1. `?` 及其后的 query（`sms:10086?body=x`）
     * 2. `&body=` 及其后的内容（Android 历史兼容格式，`sms:10086&body=x`）
     * 3. 前导 `//`（authority 分隔符，`sms://10086`）
     * 4. 首尾多余的 `/` 与空白
     * 最后按 `;` / `,` 分段、逐段 trim、丢掉空串、逐段解码。
     *
     * 必须同时兼容标准与非标准格式：浏览器 / 系统 / 第三方 App 发的形态并不统一。
     * 截图里的 `sms://244444/&body=x` 就是"有 `//`、有 `/`、又有 `&body=`"的混合形态——
     * 旧实现只做 `split("?")`，找不到 `?` 就把整串 `//244444/&body=…` 当成收件人。
     */
    internal fun parseRecipientsFromSchemeSpecificPart(schemeSpecificPart: String): Array<String> {
        var value = schemeSpecificPart

        // 1. `?` 之后一律是 query，不属于收件人
        val queryStart = value.indexOf('?')
        if (queryStart >= 0) value = value.substring(0, queryStart)

        // 2. 历史兼容格式：`&body=` 之后一律是正文，不属于收件人
        val bodyStart = value.indexOf(LEGACY_BODY_MARKER)
        if (bodyStart >= 0) value = value.substring(0, bodyStart)

        // 3. 前导 `//` 是 authority 分隔符，不是号码的一部分
        value = value.trimStart('/')

        // 4. 尾部分隔斜杠 / 空白
        value = value.trimEnd('/', ' ', '\t', '\n', '\r')

        return value.replace(';', ',')
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { decodeRecipient(it) }
            .filter { it.isNotEmpty() }
            .toTypedArray()
    }

    private fun extractBodyFromIntent(intent: Intent): String? {
        val uriBody = extractBodyFromUri(intent.data)
        val smsBody = intent.getStringExtra(SMS_BODY)
        val extraText = if (ContentType.TEXT_PLAIN == intent.type) {
            intent.getStringExtra(Intent.EXTRA_TEXT)
        } else {
            // Invalid URL, probably
            null
        }

        return smsBody ?: uriBody ?: extraText
    }

    private fun extractBodyFromUri(uri: Uri?): String? {
        val schemeSpecificPart = uri?.schemeSpecificPart ?: return null
        return extractBodyFromSchemeSpecificPart(schemeSpecificPart)
    }

    /**
     * 纯字符串核心：从 scheme 之后的部分里取正文。同样抽成 `internal` 纯函数以便单测。
     *
     * 1. 标准格式：`?` 之后是 query，用 `&` 分隔参数，从中找 `body=`
     * 2. 回退：`Uri.query` 取不到（**没有 `?`**）时的 Android 历史兼容格式，直接找 `&body=`
     *
     * 空正文按"没有正文"处理（返回 null），让调用方继续回退到 `sms_body` / `EXTRA_TEXT`，
     * 避免一个空的 `body=` 把真正的正文来源挡掉。
     */
    internal fun extractBodyFromSchemeSpecificPart(schemeSpecificPart: String): String? {
        // 1. 标准格式
        val queryStart = schemeSpecificPart.indexOf('?')
        if (queryStart >= 0) {
            val fromQuery = schemeSpecificPart.substring(queryStart + 1)
                .split('&')
                .firstOrNull { it.startsWith(BODY_PARAM) }
                ?.removePrefix(BODY_PARAM)
            if (fromQuery != null) return decodeBodyOrNull(fromQuery)
        }

        // 2. 历史兼容格式（无 `?`）
        val legacyStart = schemeSpecificPart.indexOf(LEGACY_BODY_MARKER)
        if (legacyStart >= 0) {
            val fromLegacy = schemeSpecificPart
                .substring(legacyStart + LEGACY_BODY_MARKER.length)
                .substringBefore('&')
            return decodeBodyOrNull(fromLegacy)
        }

        return null
    }

    /**
     * 收件人解码。**这是全工程唯一的收件人解码点**——
     * [org.fossify.messages.activities.NewConversationActivity] 与 `HeadlessSmsSendService`
     * 都直接消费本函数的结果，不再各自再 `URLDecoder.decode` 一次
     * （各自解一次会变成双重解码，号码里的 `%` 会被解坏）。
     *
     * `replace("+", "%2b")` 是为了让 `+` 保持字面量：`URLDecoder.decode` 会把 `+` 解成空格，
     * 而 `+8613800138000` 这类号码里的 `+` 是国际区号前缀，必须原样保留。
     *
     * 解码失败（号码里含裸 `%` 等非法转义）时原样返回，绝不因为解码失败把号码整段丢掉。
     * 这里刻意不写日志：本文件要能在无 Robolectric 的 JVM 单测里直接跑，而 `android.util.Log`
     * 在单测里是 stub（调用会抛 RuntimeException）。异常参数命名 `ignored` 是 detekt
     * `SwallowedException` 的豁免命名约定。
     */
    internal fun decodeRecipient(raw: String): String {
        if (raw.isEmpty()) return raw
        return try {
            URLDecoder.decode(raw.replace("+", "%2b"), "UTF-8")
        } catch (ignored: UnsupportedEncodingException) {
            // UTF-8 恒可用，此分支实际不可达；保留是为了满足受检异常，兜底返回原值。
            raw
        } catch (ignored: IllegalArgumentException) {
            // 号码里含裸 `%` 等非法转义：原样返回，绝不丢号码。
            raw
        }
    }

    /**
     * 正文解码：`+` 按表单编码解成空格（与历史行为一致），非法转义时原样返回。
     * 同样刻意不写日志，理由见 [decodeRecipient]。
     */
    private fun decodeBodyOrNull(value: String): String? {
        val decoded = try {
            URLDecoder.decode(value, "UTF-8")
        } catch (ignored: UnsupportedEncodingException) {
            // UTF-8 恒可用，此分支实际不可达；保留是为了满足受检异常。
            value
        } catch (ignored: IllegalArgumentException) {
            // 正文里含裸 `%`：原样返回，不要把整段正文丢掉。
            value
        }
        return decoded.takeIf { it.isNotEmpty() }
    }
}
