package org.fossify.messages.updates

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fossify.messages.BuildConfig
import org.fossify.messages.extensions.showSmsStyled
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** No startup update check, worker, timer or onResume check: the click is the only entry point. */
class ManualUpdateController(
    private val activity: AppCompatActivity,
    private val checkButton: TextView,
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .build()
    private var job: Job? = null
    @Volatile private var activeCall: Call? = null

    init {
        activity.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { cancel() }
        })
    }

    private fun cancel() {
        job?.cancel()
        activeCall?.cancel()
    }

    fun check() = runTask("正在检查更新…") {
        val release = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(GitHubRelease.API_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SMS-Forwarder/${BuildConfig.VERSION_NAME}")
                .header("Cache-Control", "no-cache")
                .build()
            http.newCall(request).also { activeCall = it }.execute().use { response ->
                check(response.isSuccessful) {
                    if (response.code == 403 || response.code == 429) "GitHub 请求受限，请稍后重试"
                    else "检查失败（HTTP ${response.code}）"
                }
                GitHubRelease.parse(response.body?.string() ?: error("发布信息为空"))
            }
        }
        if (!release.isNewerThan(BuildConfig.VERSION_CODE.toLong())) {
            Toast.makeText(activity, "已是最新版本：${BuildConfig.VERSION_NAME}", Toast.LENGTH_LONG).show()
        } else {
            AlertDialog.Builder(activity)
                .setTitle("发现新版：${release.name}")
                .setMessage("当前版本：${BuildConfig.VERSION_NAME}\n\n${release.notes}")
                .setPositiveButton("下载安装") { _, _ -> download(release) }
                .setNeutralButton("发布页") { _, _ -> openPage(release.pageUrl) }
                .setNegativeButton("取消", null)
                .create().showSmsStyled()
        }
    }

    private fun download(release: GitHubRelease) = runTask("正在下载并校验安装包…") {
        val apk = withContext(Dispatchers.IO) {
            val directory = File(activity.cacheDir, "updates").apply { mkdirs() }
            val destination = File(directory, "sms-${release.versionCode}.apk")
            val partial = File(directory, "sms-${release.versionCode}.part.apk")
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                http.newCall(Request.Builder().url(release.apkUrl).build()).also { activeCall = it }.execute().use { response ->
                    check(response.isSuccessful) { "下载失败（HTTP ${response.code}）" }
                    val body = response.body ?: error("下载内容为空")
                    var downloaded = 0L
                    body.byteStream().use { input ->
                        partial.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = input.read(buffer)
                                if (count < 0) break
                                downloaded += count
                                check(downloaded <= release.apkSize) { "安装包大小不匹配" }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                        }
                    }
                    check(downloaded == release.apkSize) { "安装包未下载完整，请重试" }
                }
                val actualHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
                check(actualHash == release.sha256) { "安装包校验失败，请重新下载" }
                verifyPackage(partial, release)
                check(partial.renameTo(destination)) { "无法保存安装包，请重试" }
                destination
            } finally {
                partial.delete()
            }
        }
        showInstall(apk, release)
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(file: File, release: GitHubRelease) {
        val manager = activity.packageManager
        val archive = manager.getPackageArchiveInfo(file.absolutePath, PackageManager.GET_SIGNATURES)
            ?: error("安装包无法解析")
        check(archive.packageName == activity.packageName) { "安装包应用不匹配" }
        check(PackageInfoCompat.getLongVersionCode(archive) == release.versionCode &&
            release.isNewerThan(BuildConfig.VERSION_CODE.toLong())) { "安装包版本不匹配" }
        val installed = manager.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
        val original = installed.signatures.orEmpty().map { it.toCharsString() }.toSet()
        val incoming = archive.signatures.orEmpty().map { it.toCharsString() }.toSet()
        check(original.isNotEmpty() && original == incoming) { "安装包签名不匹配，无法覆盖安装" }
    }

    private fun showInstall(apk: File, release: GitHubRelease) {
        AlertDialog.Builder(activity)
            .setTitle("安装包已下载并校验")
            .setMessage("${release.name}\n点击安装后，由系统确认覆盖安装并保留配置。")
            .setPositiveButton("安装") { _, _ -> install(apk, release) }
            .setNeutralButton("发布页") { _, _ -> openPage(release.pageUrl) }
            .setNegativeButton("取消", null)
            .create().showSmsStyled()
    }

    private fun install(apk: File, release: GitHubRelease) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity)
                .setTitle("允许安装更新")
                .setMessage("请在系统设置中允许本应用安装更新。授权返回后，再点击“安装”继续。")
                .setPositiveButton("去设置") { _, _ ->
                    // Keep an explicit install action available when the user returns; never auto-install.
                    showInstall(apk, release)
                    try {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                            Uri.parse("package:${activity.packageName}")))
                    } catch (_: ActivityNotFoundException) {
                        showError("无法打开安装授权设置，请从系统设置中允许安装未知应用")
                    }
                }
                .setNegativeButton("取消", null)
                .create().showSmsStyled()
            return
        }
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", apk)
            activity.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: ActivityNotFoundException) {
            showError("无法打开系统安装器，请通过发布页下载安装")
        } catch (_: IllegalArgumentException) {
            showError("无法打开安装包，请重新下载")
        }
    }

    private fun runTask(title: String, action: suspend () -> Unit) {
        if (job?.isActive == true) return
        checkButton.isEnabled = false
        val progress = AlertDialog.Builder(activity).setTitle(title)
            .setMessage("可取消后重试，或通过发布页下载。")
            .setNegativeButton("取消") { _, _ -> cancel() }
            .create()
        progress.setOnCancelListener { cancel() }
        progress.showSmsStyled()
        job = activity.lifecycleScope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                showError(error.message ?: "操作失败，请稍后重试")
            } finally {
                activeCall = null
                progress.dismiss()
                checkButton.isEnabled = true
            }
        }
    }

    private fun showError(message: String) {
        AlertDialog.Builder(activity).setTitle("更新未完成")
            .setMessage(message)
            .setPositiveButton("确定", null)
            .setNeutralButton("打开发布页") { _, _ -> openPage(GitHubRelease.LATEST_PAGE) }
            .create().showSmsStyled()
    }

    private fun openPage(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, "未找到可打开链接的应用", Toast.LENGTH_LONG).show()
        }
    }
}
