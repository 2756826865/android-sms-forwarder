package org.fossify.messages.ui.compose.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import org.fossify.messages.activities.AboutActivity
import org.fossify.messages.activities.UserGuideActivity

internal fun openSupportDestination(context: Context, destination: String) {
    val mode = context.getSharedPreferences("gateway_appearance", 0).getInt("mode", 0)
    val dark = mode == 2 || (mode == 0 && (context.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES)
    val dialogContext = android.view.ContextThemeWrapper(context, if (dark) android.R.style.Theme_Material_Dialog_Alert else android.R.style.Theme_Material_Light_Dialog_Alert)
    try {
        when (destination) {
            "app:settings" -> context.startActivity(Intent(context, org.fossify.messages.activities.SettingsActivity::class.java))
            "app:faq", "app:known", "app:notice", "app:licenses" -> {
                val resources = when(destination) {
                    "app:faq" -> org.fossify.messages.R.string.about_common_questions to org.fossify.messages.R.string.about_common_questions_text
                    "app:known" -> org.fossify.messages.R.string.about_known_issues to org.fossify.messages.R.string.about_known_issues_text
                    "app:notice" -> org.fossify.messages.R.string.about_special_statement to org.fossify.messages.R.string.first_use_notice_message
                    else -> org.fossify.messages.R.string.about_licenses to org.fossify.messages.R.string.about_licenses_text
                }
                android.app.AlertDialog.Builder(dialogContext).setTitle(resources.first).setMessage(resources.second).setPositiveButton("关闭", null).show()
            }
            "app:guide" -> context.startActivity(Intent(context, UserGuideActivity::class.java))
            "app:qq" -> {
                (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("QQ 交流群", "569321348"))
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AboutActivity.QQ_GROUP_INVITE))
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                try { context.startActivity(Intent(intent).setPackage("com.tencent.mobileqq")) }
                catch (_: android.content.ActivityNotFoundException) { context.startActivity(intent) }
            }
            "app:changelog" -> android.app.AlertDialog.Builder(dialogContext)
                .setTitle("1.2.7 开发版更新日志")
                .setMessage("• 设置分类管理，支持 Miuix／Material 风格切换。\n• 引入主题预览、深浅配色及胶囊导航。\n• 新增诊断日志预览、文件保存与分享。\n• 大盘移除快捷自愈及队列深度面板。\n• 全量同步逐条更新读取进度，每 1000 条批量写入。\n\n本页记录开发分支改动，构建通过不代表真机功能已验收。")
                .setPositiveButton("关闭", null).show()
            else -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
        }
    } catch (_: Exception) {
        Toast.makeText(context, if(destination == "app:qq") "无法打开加群链接，群号569321348已复制" else "无法打开，请重试", Toast.LENGTH_LONG).show()
    }
}
