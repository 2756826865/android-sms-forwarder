package org.fossify.messages.forwarding

/** Body ownership is independent of whether formatting has already happened. */
internal object SimLabelPresentation {
    fun preserveBody(alreadyRendered: Boolean, allowPrefix: Boolean, explicitGlobalTemplate: Boolean): Boolean =
        explicitGlobalTemplate || (alreadyRendered && !allowPrefix)

    fun addLabel(content: String, label: String, privacyEnabled: Boolean): String {
        if (label.isBlank()) return content
        // Formatting masks the body before transport adaptation; never append a raw phone/card number.
        val visibleLabel = if (privacyEnabled) PrivacyDataMasker.mask(label) else label
        val alreadyLabeled = content.lineSequence().any {
            val line = it.trim()
            line == "【$visibleLabel】" || line == "卡槽：$visibleLabel" || line == "📶卡槽：$visibleLabel"
        }
        return if (alreadyLabeled) content else "【$visibleLabel】\n$content"
    }
}
