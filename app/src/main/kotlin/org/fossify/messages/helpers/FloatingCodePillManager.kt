package org.fossify.messages.helpers

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.fossify.messages.autofill.AutofillConfig

/**
 * 屏幕顶部 5 秒悬浮验证码小胶囊管理器
 * 纯本地 WindowManager 动态绘制，支持单点一键复制，5 秒自动淡出
 */
object FloatingCodePillManager {

    private var floatingView: View? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var dismissRunnable: Runnable? = null

    fun showPill(context: Context, code: String) {
        val autofillConfig = AutofillConfig(context)
        if (!autofillConfig.enabled || !autofillConfig.enableFloatingPill) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return

        mainHandler.post {
            showFloatingPillInternal(context, code)
        }
    }

    private fun showFloatingPillInternal(context: Context, code: String) {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        dismissPill(context)

        val dp = context.resources.displayMetrics.density

        // 胶囊容器
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24 * dp
                setColor(Color.parseColor("#EE1E293B")) // 93% Slate-800
                setStroke((1 * dp).toInt(), Color.parseColor("#33FFFFFF"))
            }
            elevation = 12 * dp
        }

        // 复制图标
        val iconView = ImageView(context).apply {
            setImageResource(org.fossify.commons.R.drawable.ic_copy_vector)
            setColorFilter(Color.parseColor("#38BDF8"))
            layoutParams = LinearLayout.LayoutParams((18 * dp).toInt(), (18 * dp).toInt()).apply {
                marginEnd = (8 * dp).toInt()
            }
        }
        container.addView(iconView)

        // 文案: 验证码: 951332 (点此复制)
        val textView = TextView(context).apply {
            text = "验证码 $code · 点击复制"
            setTextColor(Color.WHITE)
            textSize = 14f
            paint.isFakeBoldText = true
        }
        container.addView(textView)

        // 关闭图标
        val closeView = ImageView(context).apply {
            setImageResource(org.fossify.commons.R.drawable.ic_cross_vector)
            setColorFilter(Color.parseColor("#94A3B8"))
            layoutParams = LinearLayout.LayoutParams((14 * dp).toInt(), (14 * dp).toInt()).apply {
                marginStart = (12 * dp).toInt()
            }
            setOnClickListener {
                dismissPill(context)
            }
        }
        container.addView(closeView)

        // 点击一键复制
        container.setOnClickListener {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("VerificationCode", code))
            Toast.makeText(context, "验证码 $code 已复制到剪贴板", Toast.LENGTH_SHORT).show()
            dismissPill(context)
        }

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (48 * dp).toInt()
            windowAnimations = android.R.style.Animation_Toast
        }

        runCatching {
            windowManager.addView(container, params)
            floatingView = container

            // 5 秒自动优雅消失
            val runnable = Runnable {
                dismissPill(context)
            }
            dismissRunnable = runnable
            mainHandler.postDelayed(runnable, 5000L)
        }
    }

    fun dismissPill(context: Context) {
        dismissRunnable?.let { mainHandler.removeCallbacks(it) }
        dismissRunnable = null
        val view = floatingView ?: return
        floatingView = null

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        runCatching {
            view.animate()
                .alpha(0f)
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(250L)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        runCatching { windowManager.removeView(view) }
                    }
                })
        }.onFailure {
            runCatching { windowManager.removeView(view) }
        }
    }
}
