package org.fossify.messages.autofill

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import java.util.concurrent.atomic.AtomicReference

class SmsAutofillAccessibilityService : AccessibilityService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var config: AutofillConfig

    override fun onServiceConnected() {
        super.onServiceConnected()
        config = AutofillConfig(this)
        instanceRef.set(this)
        Log.i(TAG, "SmsAutofillAccessibilityService connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instanceRef.compareAndSet(this, null)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Accessibility event monitoring if needed
    }

    override fun onInterrupt() {
        Log.w(TAG, "SmsAutofillAccessibilityService interrupted")
    }

    private fun attemptAutofill(code: String) {
        if (!config.enabled) return
        val rootNode = rootInActiveWindow ?: return

        val currentPackage = rootNode.packageName?.toString().orEmpty()
        if (config.isPackageExcluded(currentPackage) || currentPackage == packageName) {
            Log.d(TAG, "Package $currentPackage is excluded or self, skip autofill")
            return
        }

        val targetInput = findVerificationCodeInput(rootNode)
        if (targetInput != null) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
            }
            val filled = targetInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            Log.i(TAG, "Autofill code $code result: $filled in $currentPackage")

            if (filled) {
                mainHandler.post {
                    Toast.makeText(this, "已自动填充验证码: $code", Toast.LENGTH_SHORT).show()
                }

                if (config.autoSubmit) {
                    mainHandler.postDelayed({
                        val submitButton = findSubmitButton(rootInActiveWindow ?: rootNode)
                        submitButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }, 500)
                }
            }
        }
    }

    private fun findVerificationCodeInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectEditTextNodes(root, candidates)

        // 优先 1: 带有焦点且支持输入的输入框
        val focused = candidates.firstOrNull { it.isFocused }
        if (focused != null) return focused

        // 优先 2: hint 或 text 中包含“验证码 / 动态码 / code”等特征
        val codeKeywords = listOf("验证码", "动态码", "校验码", "code", "Code", "OTP")
        for (node in candidates) {
            val hint = node.hintText?.toString().orEmpty()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            if (codeKeywords.any { hint.contains(it, true) || text.contains(it, true) || desc.contains(it, true) }) {
                return node
            }
        }

        // 优先 3: 唯一的输入框
        return candidates.firstOrNull()
    }

    private fun collectEditTextNodes(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.className?.toString()?.contains("EditText", ignoreCase = true) == true && node.isEditable) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            collectEditTextNodes(node.getChild(i), list)
        }
    }

    private fun findSubmitButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val submitKeywords = listOf("登录", "确定", "下一步", "提交", "验证", "完成", "Login", "Submit", "Verify", "Next", "OK")
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        collectClickableNodes(root, buttons)

        for (button in buttons) {
            val text = button.text?.toString().orEmpty()
            val desc = button.contentDescription?.toString().orEmpty()
            if (submitKeywords.any { text.contains(it, true) || desc.contains(it, true) }) {
                return button
            }
        }
        return null
    }

    private fun collectClickableNodes(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        if (node.isClickable && node.isEnabled) {
            list.add(node)
        }
        for (i in 0 until node.childCount) {
            collectClickableNodes(node.getChild(i), list)
        }
    }

    companion object {
        private const val TAG = "SmsAutofillService"
        private val instanceRef = AtomicReference<SmsAutofillAccessibilityService?>(null)

        fun isServiceRunning(): Boolean = instanceRef.get() != null

        fun onNewVerificationSms(context: Context, body: String) {
            val code = VerificationCodeExtractor.extractCode(body) ?: return
            val config = AutofillConfig(context)

            if (config.copyToClipboard) {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                clipboard?.setPrimaryClip(ClipData.newPlainText("VerificationCode", code))
            }

            // 弹出屏幕顶部 5 秒悬浮胶囊
            org.fossify.messages.helpers.FloatingCodePillManager.showPill(context, code)

            val service = instanceRef.get()
            if (service != null && config.enabled) {
                service.mainHandler.post {
                    service.attemptAutofill(code)
                }
            }
        }
    }
}
