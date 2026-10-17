package org.fossify.messages.updates

import org.json.JSONObject
import java.net.URI

/** Version names are display labels; only the published numeric code controls updates. */
internal data class GitHubRelease(
    val name: String,
    val versionCode: Long,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String,
) {
    fun isNewerThan(installedCode: Long) = versionCode > installedCode

    companion object {
        const val REPOSITORY = "2756826865/android-sms-forwarder"
        const val LATEST_PAGE = "https://github.com/$REPOSITORY/releases/latest"
        const val API_URL = "https://api.github.com/repos/$REPOSITORY/releases/latest"
        const val MAX_APK_BYTES = 128L * 1024 * 1024
        private val versionCodePattern = Regex(
            """(?im)^\s*(?:版本代码|版本码|version\s*code)\s*[:：]\s*([0-9]+)\b"""
        )

        fun parse(json: String): GitHubRelease {
            val release = JSONObject(json)
            require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) {
                "该发布不是正式版本"
            }
            val notes = release.optString("body")
            val code = versionCodePattern.find(notes)?.groupValues?.get(1)?.toLongOrNull()
                ?: error("发布信息缺少版本代码，无法可靠比较，请打开发布页查看")
            require(code > 0) { "发布版本代码无效" }
            val assets = release.optJSONArray("assets") ?: error("发布页还没有安装包")
            val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter {
                val name = it.optString("name")
                name.startsWith("SMS-Forwarder-") && name.endsWith("-release.apk") &&
                    it.optString("state") == "uploaded"
            }
            require(apks.size == 1) { "无法确定正式安装包，请打开发布页下载" }
            val apk = apks.single()
            val url = apk.getString("browser_download_url")
            require(isRepositoryUrl(url, "/releases/download/")) { "安装包地址不属于本项目" }
            val size = apk.getLong("size")
            require(size in 1..MAX_APK_BYTES) { "安装包大小异常" }
            val digest = apk.optString("digest").removePrefix("sha256:").lowercase()
            require(digest.matches(Regex("[0-9a-f]{64}"))) {
                "发布信息缺少安装包校验值，请打开发布页下载"
            }
            val page = release.optString("html_url").takeIf { isRepositoryUrl(it, "/releases/tag/") }
                ?: LATEST_PAGE
            return GitHubRelease(
                name = release.optString("name").ifBlank { release.getString("tag_name") },
                versionCode = code,
                notes = notes.take(16000),
                pageUrl = page,
                apkUrl = url,
                apkSize = size,
                sha256 = digest,
            )
        }

        private fun isRepositoryUrl(value: String, suffix: String): Boolean = runCatching {
            val uri = URI(value)
            uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
                (uri.port == -1 || uri.port == 443) && uri.path.startsWith("/$REPOSITORY$suffix")
        }.getOrDefault(false)
    }
}
