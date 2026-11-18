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
    // Native dialogs do not inherit Compose's content colors. Set every text role
    // explicitly so OEM/activity text appearances cannot produce white on white.
    fun showTextDialog(title: CharSequence, message: CharSequence) {
        val foreground = if (dark) android.graphics.Color.rgb(230, 230, 230) else android.graphics.Color.rgb(32, 33, 36)
        val background = if (dark) android.graphics.Color.rgb(30, 32, 36) else android.graphics.Color.WHITE
        fun colored(text: CharSequence) = android.text.SpannableString(text).apply {
            setSpan(android.text.style.ForegroundColorSpan(foreground), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        val dialog = android.app.AlertDialog.Builder(dialogContext)
            .setTitle(colored(title)).setMessage(colored(message))
            .setPositiveButton("关闭", null).create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(background)
                cornerRadius = 16 * context.resources.displayMetrics.density
            })
            dialog.window?.decorView?.clipToOutline = true
            dialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextColor(foreground)
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)?.setTextColor(foreground)
        }
        dialog.show()
    }
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
                showTextDialog(context.getText(resources.first), context.getText(resources.second))
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
            "app:changelog" -> showTextDialog(
                "1.3.1 更新日志",
                "• 收发守恒与防漏发加固：广播与通知配对制防重支持连发同内容验证码 1:1 守恒；" +
                    "通知兜底无真实号码或 0 命中时自动释放占位，确保广播与补偿扫描不漏发。\n" +
                    "• 零误扣费与远控安全：第三方通知默认隔离短信直发，规则绑定直发严守命中范围；" +
                    "远程短信指令增加跨路径去重，群发短信支持落盘断点续发防重试双扣费。\n" +
                    "• 全协议栈与系统稳定性：修复 Android 12+ 前台服务超时崩溃与数据库跨版本升级自愈；" +
                    "优化 SMTP/OAuth 提交前重试、QQ/Webhook/ntfy/WxPusher 协议细节与跨天免打扰。\n" +
                    "• 经典版与开发版全量对齐：打通多实例渠道、远控来源、规则引擎、备份恢复双向同步，消除列表卡顿。"
            )
            else -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
        }
    } catch (_: Exception) {
        Toast.makeText(context, if(destination == "app:qq") "无法打开加群链接，群号569321348已复制" else "无法打开，请重试", Toast.LENGTH_LONG).show()
    }
}
