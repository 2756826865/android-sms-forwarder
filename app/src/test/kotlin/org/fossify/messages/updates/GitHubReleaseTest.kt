package org.fossify.messages.updates

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GitHubReleaseTest {
    private fun release(code: Long = 1206, name: String = "1.2.3(101703)"): JSONObject = JSONObject()
        .put("name", name).put("tag_name", "v$name")
        .put("body", "# 更新\n\n版本代码：$code，可覆盖安装。\n\n新增手动检查更新。")
        .put("html_url", "https://github.com/${GitHubRelease.REPOSITORY}/releases/tag/v$name")
        .put("assets", JSONArray().put(JSONObject()
            .put("name", "SMS-Forwarder-$name-release.apk")
            .put("state", "uploaded").put("size", 7980030)
            .put("browser_download_url", "https://github.com/${GitHubRelease.REPOSITORY}/releases/download/v$name/app.apk")
            .put("digest", "sha256:" + "ab".repeat(32))))

    @Test fun onlyGreaterNumericCodeTriggersAnUpdate() {
        val parsed = GitHubRelease.parse(release().toString())
        assertTrue(parsed.isNewerThan(1205))
        assertFalse(parsed.isNewerThan(1206))
        assertFalse(parsed.isNewerThan(1207))
    }

    @Test fun dateAndNameNeverOverrideNumericCode() {
        val parsed = GitHubRelease.parse(release(1205, "99.0.0(123199)").toString())
        assertFalse(parsed.isNewerThan(1205))
    }

    @Test fun releaseNotesAndChecksumArePreserved() {
        val data = release()
        val parsed = GitHubRelease.parse(data.toString())
        assertEquals(data.getString("body"), parsed.notes)
        assertEquals("ab".repeat(32), parsed.sha256)
        assertEquals(7980030L, parsed.apkSize)
    }

    @Test(expected = IllegalStateException::class)
    fun missingCodeDoesNotFallBackToGuessingFromVersionName() {
        GitHubRelease.parse(release().put("body", "新版1.2.4").toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingDigestIsRejected() {
        val json = release()
        json.getJSONArray("assets").getJSONObject(0).remove("digest")
        GitHubRelease.parse(json.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun foreignApkUrlIsRejected() {
        val json = release()
        json.getJSONArray("assets").getJSONObject(0)
            .put("browser_download_url", "https://github.com/other/repo/releases/download/v1/app.apk")
        GitHubRelease.parse(json.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun multipleApksRequireManualSelection() {
        val json = release()
        json.getJSONArray("assets").put(json.getJSONArray("assets").getJSONObject(0))
        GitHubRelease.parse(json.toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun previewReleaseIsRejected() {
        GitHubRelease.parse(release().put("prerelease", true).toString())
    }

    @Test(expected = IllegalArgumentException::class)
    fun oversizedDownloadIsRejected() {
        val json = release()
        json.getJSONArray("assets").getJSONObject(0).put("size", GitHubRelease.MAX_APK_BYTES + 1)
        GitHubRelease.parse(json.toString())
    }
}
