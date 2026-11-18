package org.fossify.messages.helpers

import android.content.Context
import android.util.Log
import android.util.LruCache
import org.json.JSONArray
import java.io.BufferedReader
import java.io.InputStreamReader

data class YellowPageEntry(
    val brand: String,
    val number: String,
    val category: String,
    val logoUrl: String
)

data class YellowPageResult(
    val brand: String,
    val category: String,
    val logoUrl: String
)

object YellowPageHelper {
    private const val TAG = "YellowPageHelper"
    private const val ASSETS_FILE = "yellow_pages.json"

    // 匹配常见的短信开头签名：如【京东】、[招商银行]、〔中国移动〕（禁止内部跨嵌套括号）
    private val signatureStartRegex = Regex("""^(?:\[|【|〔)([^\[【〔\]】〕]+)(?:\]|】|〕)""")
    // 匹配常见的短信结尾签名：如【腾讯科技】、[美团]（禁止内部跨嵌套括号）
    private val signatureEndRegex = Regex("""(?:\[|【|〔)([^\[【〔\]】〕]+)(?:\]|】|〕)$""")
    private val pureCodeSignatureRegex = Regex("""^(?:[A-Za-z]-)?\d+$""")

    @Volatile
    internal var isInitialized = false
        private set
    private val lock = Any()

    private val numberMap = HashMap<String, YellowPageEntry>()
    private val brandList = ArrayList<YellowPageEntry>()
    private val resultCache = LruCache<String, YellowPageResult>(500)
    // An unverified 11-digit mobile number can be reassigned to a private subscriber.
    // Do not let bundled data rename a contact or enable body-signature matching for it.
    private val privateMobilePattern = Regex("1[3-9][0-9]{9}")

    fun ensureInitialized(context: Context) {
        if (isInitialized) return
        synchronized(lock) {
            if (isInitialized) return
            try {
                val inputStream = context.assets.open(ASSETS_FILE)
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                val jsonString = reader.use { it.readText() }
                val jsonArray = JSONArray(jsonString)

                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val brand = obj.optString("brand").trim()
                    val number = obj.optString("number").trim()
                    val category = obj.optString("category").trim()
                    val logoUrl = obj.optString("logo_url").trim()

                    if (brand.isNotEmpty() && logoUrl.isNotEmpty()) {
                        val entry = YellowPageEntry(brand, number, category, logoUrl)
                        if (number.isNotEmpty()) {
                            val cleanNumber = normalizeNumber(number)
                            if (!privateMobilePattern.matches(cleanNumber)) numberMap[cleanNumber] = entry
                        }
                        brandList.add(entry)
                    }
                }
                // 按品牌名称长度降序排序，匹配时优先匹配更长、更精准的名称
                brandList.sortByDescending { it.brand.length }
                isInitialized = true
                Log.i(TAG, "Initialized with ${brandList.size} yellow page entries, ${numberMap.size} numbers.")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load yellow_pages.json from assets: ${e.message}")
            }
        }
    }

    /**
     * 判断号码是否为企业服务通道、特服号或系统通道号。
     * 只有通道号/特服号才允许从短信正文中提取【发件方】签名来识别黄页品牌；
     * 严禁对普通 11 位私人手机号进行正文签名模糊识别，避免误伤私人联系人（例如朋友转发一条带有【淘宝】的短信）。
     */
    fun isCommercialOrSpecialAddress(address: String): Boolean {
        val clean = normalizeNumber(address)
        if (clean.isBlank()) return false
        if (privateMobilePattern.matches(clean)) return false

        // 1. 在内置直配表中已登记的官方服务号（如 13800138000 官方充值号）
        if (numberMap.containsKey(clean)) return true

        // 2. 106 商业短信扩展通道号（如 1065, 1068, 1069 开头，通常 8~20 位）
        if (clean.startsWith("106")) return true

        // 3. 常见公用/运营商/政务/金融/企业特服前缀
        if (clean.startsWith("100") || clean.startsWith("95") || clean.startsWith("96") ||
            clean.startsWith("123") || clean.startsWith("400") || clean.startsWith("800")) {
            return true
        }

        // 4. 特服短代码（长度 <= 8 位的纯数字国内特服号或政企接入号；带国际区号 + 的短号除外）
        if (!address.trim().startsWith("+") && clean.length in 3..8 && clean.all { it.isDigit() }) return true

        // 5. 包含字母/符号的网关地址（如字母网关 Alipay、Email 发送方；去掉前导国际区号 '+' 后判断，防止将 +1/+852 等境外私人号码误判为商业网关）
        val withoutPlus = clean.removePrefix("+")
        if (withoutPlus.isNotEmpty() && withoutPlus.any { !it.isDigit() }) return true

        // 6. 普通私人手机号码（含境内外私人号码），绝不可启用正文签名匹配
        return false
    }

    /**
     * 从短信正文中提取【发件方】签名（优先取头部，次选尾部；过滤纯数字验证码括号如 [123456]）
     */
    fun extractSignature(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val trimmed = text.trim()
        val startMatch = signatureStartRegex.find(trimmed)
        if (startMatch != null) {
            val sig = startMatch.groupValues[1].trim()
            if (sig.length in 2..30 && !pureCodeSignatureRegex.matches(sig)) return sig
        }
        val endMatch = signatureEndRegex.find(trimmed)
        if (endMatch != null) {
            val sig = endMatch.groupValues[1].trim()
            if (sig.length in 2..30 && !pureCodeSignatureRegex.matches(sig)) return sig
        }
        return null
    }

    /**
     * 规范化电话号码（去除空格、连字符及国家码）
     */
    fun normalizeNumber(rawNumber: String): String {
        var num = rawNumber.trim().replace(" ", "").replace("-", "")
        if (num.startsWith("+86")) {
            num = num.substring(3)
        } else if (num.startsWith("0086")) {
            num = num.substring(4)
        }
        return num
    }

    /**
     * 智能查询黄页结果（号码直配 + 通道短信签名识别）
     */
    fun lookup(context: Context, address: String, text: String? = null): YellowPageResult? {
        if (address.isBlank()) return null
        ensureInitialized(context)

        val cleanAddress = normalizeNumber(address)
        val signature = if (text.isNullOrBlank()) null else extractSignature(text)
        val cacheKey = if (signature.isNullOrBlank()) cleanAddress else "$cleanAddress#$signature"
        resultCache.get(cacheKey)?.let { return it }

        // 1. 号码精确直配（如 10086、95588、95188、12345、13800138000 等）
        numberMap[cleanAddress]?.let {
            val res = YellowPageResult(it.brand, it.category, it.logoUrl)
            resultCache.put(cacheKey, res)
            return res
        }

        // 2. 短信正文签名识别：严密保护私人号码，只有确定为商业通道/特服号时才启用签名匹配
        if (!signature.isNullOrBlank() && isCommercialOrSpecialAddress(address)) {
            val matchedEntry = findBySignature(signature)
            if (matchedEntry != null) {
                val res = YellowPageResult(matchedEntry.brand, matchedEntry.category, matchedEntry.logoUrl)
                resultCache.put(cacheKey, res)
                return res
            }
        }

        return null
    }

    /**
     * 根据短信签名模糊匹配品牌
     */
    private fun findBySignature(signature: String): YellowPageEntry? {
        // 第一轮：精确匹配
        for (entry in brandList) {
            if (entry.brand.equals(signature, ignoreCase = true)) {
                return entry
            }
        }

        // 第二轮：签名包含品牌名，或品牌名包含足够长的签名主干（中文至少2字、纯英文至少3字符，防止单字“中/微/A”误配）
        val sigParts = signature.split(" ", "-", "_", "/", "·")
        val primarySig = sigParts.firstOrNull { it.isNotBlank() } ?: signature
        val minPrimaryLen = if (primarySig.all { it.code < 128 }) 3 else 2
        val allowReverseContain = primarySig.length >= minPrimaryLen

        for (entry in brandList) {
            val brand = entry.brand
            if (signature.contains(brand, ignoreCase = true) ||
                (allowReverseContain && brand.contains(primarySig, ignoreCase = true))
            ) {
                return entry
            }
        }

        // 第三轮：核心词匹配（去掉“客服”、“专线”、“银行”等后缀）
        for (entry in brandList) {
            val coreBrand = entry.brand
                .replace("客服", "")
                .replace("专线", "")
                .replace("查询", "")
                .replace("售后", "")
                .replace("服务", "")
                .replace("银行", "")
                .trim()

            if (coreBrand.length >= 2) {
                if (signature.contains(coreBrand, ignoreCase = true) || primarySig.contains(coreBrand, ignoreCase = true)) {
                    return entry
                }
            }
        }

        return null
    }
}
