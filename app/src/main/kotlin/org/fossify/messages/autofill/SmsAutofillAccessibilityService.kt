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
                        if (freshRoot.packageName?.toString().orEmpty() != currentPackage) {
                            return@postDelayed
                        }
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

        val cnCodeKeywords = listOf("验证码", "动态码", "校验码", "安全码", "确认码")
        val enCodeHintRegex = Regex(
            """(?:^|[^a-zA-Z])(otp|pin|code)(?:$|[^a-zA-Z])|(?:otp|pin|code)[A-Z]|[a-z](?:Otp|Pin|Code)""",
            RegexOption.IGNORE_CASE,
        )
        fun existingInputLength(node: AccessibilityNodeInfo): Int {
            if (android.os.Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) return 0
            val raw = node.text?.toString().orEmpty()
            val hint = node.hintText?.toString().orEmpty()
            if (hint.isNotEmpty() && raw == hint) return 0
            return raw.trim().length
        }
        fun matchesHintLabel(label: String): Boolean {
            if (label.isBlank()) return false
            return cnCodeKeywords.any { label.contains(it, ignoreCase = true) } ||
                enCodeHintRegex.containsMatchIn(label)
        }
        fun hasCodeHint(node: AccessibilityNodeInfo): Boolean {
            val hint = node.hintText?.toString().orEmpty()
            val desc = node.contentDescription?.toString().orEmpty()
            val viewId = node.viewIdResourceName.orEmpty()
            val placeholderText = if (
                (android.os.Build.VERSION.SDK_INT >= 26 && node.isShowingHintText) ||
                (!node.isFocused && node.text?.toString() == hint)
            ) {
                node.text?.toString().orEmpty().ifBlank { hint }
            } else ""
            return matchesHintLabel(hint) ||
                matchesHintLabel(desc) ||
                matchesHintLabel(viewId) ||
                matchesHintLabel(placeholderText)
        }

        // 优先 1: 具备明确验证码提示特征的输入框（即时通讯应用内仍保护多行与长草稿聊天框不被覆盖）
        val hintedCandidates = candidates.filter { node ->
            hasCodeHint(node) && (
                currentPackage !in CHAT_PACKAGES ||
                    (!node.isMultiLine && existingInputLength(node) <= 8)
                )
        }
        if (hintedCandidates.isNotEmpty()) {
            return hintedCandidates.firstOrNull { it.isFocused } ?: hintedCandidates.first()
        }

        // 优先 2: 若当前聚焦的输入框无明确提示词，必须在非即时通讯软件中，且当前内容为空或极短（避免覆盖微信/QQ聊天输入框）
        if (currentPackage !in CHAT_PACKAGES) {
            val focused = candidates.firstOrNull { it.isFocused }
            if (focused != null && !focused.isMultiLine) {
                if (existingInputLength(focused) <= 8) {
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
        val cnSubmitKeywords = listOf("登录", "确定", "下一步", "提交", "立即验证", "安全验证", "完成")
        val excludeActionKeywords = listOf("获取", "发送", "重发", "重新", "倒计时", "get", "send", "resend")
        val enSubmitRegex = Regex("""(?<![a-zA-Z])(?:login|sign\s*in|submit|verify|next|confirm|ok)(?![a-zA-Z])""", RegexOption.IGNORE_CASE)
        val buttons = mutableListOf<AccessibilityNodeInfo>()
        collectClickableNodes(root, buttons)

        fun isSubmitLabel(label: String): Boolean {
            val trimmed = label.trim()
            if (trimmed.isBlank() || trimmed.length > 16) return false
            if (excludeActionKeywords.any { trimmed.contains(it, ignoreCase = true) }) return false
            return cnSubmitKeywords.any { trimmed.contains(it, ignoreCase = true) } ||
                trimmed == "验证" ||
                enSubmitRegex.containsMatchIn(trimmed)
        }

        for (button in buttons) {
            val text = button.text?.toString().orEmpty()
            val desc = button.contentDescription?.toString().orEmpty()
            if (isSubmitLabel(text) || isSubmitLabel(desc)) {
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
