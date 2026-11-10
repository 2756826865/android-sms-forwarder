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
            "app:changelog" -> showTextDialog("1.2.9 更新日志",
                "• 短信直发防无限发与频率限制：新增本机双卡自收自转拦截、已转发模板特征拦截，支持仅断网时发送及 1分钟(默认10条)/1小时(默认30条)频率限制。\n• 系统短信通知兜底模式：内置 vivo/OPPO/魅族/华为等主流系统短信包名，支持默认短信被夺回时自动通过通知栏兜底转发，且正常持有默认短信权限时自动跳过防重复。\n• Bark 通道增强：新增通知提醒级别（默认/突破专注模式/忽略静音警告/静默）、自定义分组，以及收到验证码时在 iPhone 自动复制（autoCopy=1）。\n• 规则与群发增强：规则编辑器支持单通道独立模板，经典版规则开放独立模板与正则替换清洗，群发间隔扩展至 1~60 秒；新增鸿蒙 NEXT 卓易通容器环境引导。\n• 双卡自定义名称与无障碍自动填充修复：兼容 subId/simSlot/slotIdx 广播参数，注册无障碍自动填充服务并对齐经典版与开发版（Miuix/Material）。")
            else -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
        }
    } catch (_: Exception) {
        Toast.makeText(context, if(destination == "app:qq") "无法打开加群链接，群号569321348已复制" else "无法打开，请重试", Toast.LENGTH_LONG).show()
    }
}
