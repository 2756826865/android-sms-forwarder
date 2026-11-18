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
                "1.3.0 更新日志",
                "• 零延迟通知兜底与标准规则引擎打通：当厂商收回默认短信或屏蔽验证码广播时，" +
                    "系统短信通知 0ms 零延迟走标准短信规则、正则清洗、验证码提取与隐私脱敏秒发。\n" +
                    "• 双向毫秒级防重与合成会话自愈：广播入口 T=0ms 原子抢占，真实短信落库时自动" +
                    "原地替换临时合成记录，修复详情页刷新丢失保底短信及非默认模式无法删除会话问题。\n" +
                    "• 3 万+ 短信全链路性能飞跃：新增 messages(type, date) 索引，重写会话列表查询为 EXISTS，" +
                    "合并千次逐行独立事务为单次批量事务，彻底消除全量同步与列表刷新的 IO 风暴。\n" +
                    "• 转发引擎与安全增强：补齐自定义规则模板隐私脱敏、报活与低电量原文标记、" +
                    "来电转发多实例与主动拒接识别、无障碍验证码防误填聊天框保护。"
            )
            else -> context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(destination)))
        }
    } catch (_: Exception) {
        Toast.makeText(context, if(destination == "app:qq") "无法打开加群链接，群号569321348已复制" else "无法打开，请重试", Toast.LENGTH_LONG).show()
    }
}
