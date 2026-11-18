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

        val targetInput = findVerificationCodeInput(rootNode, currentPackage)
        if (targetInput != null) {
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
            }
            val filled = targetInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            Log.i(TAG, "Autofill result: $filled")

            if (filled) {
                mainHandler.post {
                    Toast.makeText(this, "已自动填充验证码", Toast.LENGTH_SHORT).show()
                }

                if (config.autoSubmit) {
                    mainHandler.postDelayed({
                        val freshRoot = rootInActiveWindow ?: return@postDelayed
                        val submitButton = findSubmitButton(freshRoot)
                        submitButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    }, 500)
                }
            }
        }
    }

    private fun findVerificationCodeInput(
        root: AccessibilityNodeInfo,
        currentPackage: String,
    ): AccessibilityNodeInfo? {
        val candidates = mutableListOf<AccessibilityNodeInfo>()
        collectEditTextNodes(root, candidates)
        if (candidates.isEmpty()) return null

        val codeKeywords = listOf("验证码", "动态码", "校验码", "安全码", "确认码", "code", "otp", "pin")
        fun hasCodeHint(node: AccessibilityNodeInfo): Boolean {
            val hint = node.hintText?.toString().orEmpty()
            val text = node.text?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            return codeKeywords.any { kw ->
                hint.contains(kw, ignoreCase = true) ||
                    text.contains(kw, ignoreCase = true) ||
                    desc.contains(kw, ignoreCase = true) ||
                    viewId.contains(kw, ignoreCase = true)
            }
        }

        // 优先 1: 具备明确验证码特征且当前聚焦的输入框，或任意具备明确验证码特征的输入框
        val hintedCandidates = candidates.filter(::hasCodeHint)
        if (hintedCandidates.isNotEmpty()) {
            return hintedCandidates.firstOrNull { it.isFocused } ?: hintedCandidates.first()
        }

        // 优先 2: 若当前聚焦的输入框无明确提示词，必须在非即时通讯软件中，且当前内容为空或极短（避免覆盖微信/QQ聊天输入框）
        if (currentPackage !in CHAT_PACKAGES) {
            val focused = candidates.firstOrNull { it.isFocused }
            if (focused != null && !focused.isMultiLine) {
                val existingLen = focused.text?.toString()?.trim()?.length ?: 0
                if (existingLen <= 8) {
                    return focused
                }
            }
        }

        return null
    }

    private fun collectEditTextNodes(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        val isEditClass = node.className?.toString()?.contains("EditText", ignoreCase = true) == true
        if (node.isEditable || (isEditClass && node.isEnabled)) {
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
        private val CHAT_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.tencent.wework",
            "com.ss.android.lark",
            "com.alibaba.android.rimet",
            "org.telegram.messenger"
        )

        fun isServiceRunning(context: Context? = null): Boolean {
            if (instanceRef.get() != null) return true
            if (context == null) return false
            val enabledServices = runCatching {
                android.provider.Settings.Secure.getString(
                    context.contentResolver,
                    android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            }.getOrNull().orEmpty()
            val myComponent = android.content.ComponentName(
                context,
                SmsAutofillAccessibilityService::class.java
            ).flattenToString()
            return enabledServices.contains(myComponent, ignoreCase = true) ||
                enabledServices.contains(
                    "${context.packageName}/${SmsAutofillAccessibilityService::class.java.canonicalName}",
                    ignoreCase = true
                )
        }

        fun onNewVerificationSms(context: Context, body: String) {
            val code = VerificationCodeExtractor.extractCode(body) ?: return
            val config = AutofillConfig(context)
            if (!config.enabled) return

            if (config.copyToClipboard) {
                runCatching {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        ?: error("Clipboard service unavailable")
                    val clip = ClipData.newPlainText("VerificationCode", code)
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        clip.description.extras = android.os.PersistableBundle().apply {
                            putBoolean("android.content.extra.IS_SENSITIVE", true)
                        }
                    }
                    clipboard.setPrimaryClip(clip)
                }.onFailure {
                    Log.w(TAG, "Clipboard write failed: ${it.javaClass.simpleName}")
                }
            }

            if (config.enableFloatingPill) {
                runCatching {
                    org.fossify.messages.helpers.FloatingCodePillManager.showPill(context, code)
                }.onFailure {
                    Log.w(TAG, "Code pill failed: ${it.javaClass.simpleName}")
                }
            }

            instanceRef.get()?.let { service ->
                service.mainHandler.post {
                    runCatching {
                        service.attemptAutofill(code)
                    }.onFailure {
                        Log.w(TAG, "Accessibility autofill failed: ${it.javaClass.simpleName}")
                    }
                }
            }
        }
    }
}
