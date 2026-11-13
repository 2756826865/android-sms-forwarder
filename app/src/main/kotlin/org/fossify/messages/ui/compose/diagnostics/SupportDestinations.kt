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
                "1.2.10 更新日志",
                "• 系统短信通知写入本地会话：彻底解决 ColorOS 17 / vivo / 小米 等强制夺取默认短信角色" +
                    "或屏蔽验证码广播时，短信通知有弹窗但 App 对话列表不显示的顽疾。\n" +
                    "• 通用发件人解析与双向号码归一化：智能解包 MessagingStyle person.uri 与 " +
                    "conversationTitle，过滤「信息/短信/验证码」等占位词；支持带与不带 +86 双向匹配。\n" +
                    "• 通知保底双卡识别与单卡自动推断：支持从通知 extras 提取 subId/simSlot 私有键，" +
                    "单卡设备自动推断绑定唯一有效 SIM 卡，双卡设备精准映射，杜绝未知接收卡。\n" +
                    "• 空白正文与模板穿透熔断：全面过滤零宽字符与全角/窄空格；模板渲染为空时前置拦截熔断，" +
                    "杜绝外部通道出现空白暗色气泡。\n" +
                    "• 内存健康与长时保活：缓存严格限定上限与修剪机制，工作线程池装配未捕获异常守护处理器，" +
                    "超长会话采用差量写入与分页上限，确保打开万条会话永不卡死。"
            )
            else -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
        }
    } catch (_: Exception) {
        Toast.makeText(context, if(destination == "app:qq") "无法打开加群链接，群号569321348已复制" else "无法打开，请重试", Toast.LENGTH_LONG).show()
    }
}
