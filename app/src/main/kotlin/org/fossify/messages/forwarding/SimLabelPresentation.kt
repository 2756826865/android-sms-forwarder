package org.fossify.messages.forwarding

/** Body ownership is independent of whether formatting has already happened. */
internal object SimLabelPresentation {
    fun describeSlot(slotIndex: Int, customLabel: String, carrier: String = ""): String {
        if (slotIndex < 0) return "未知接收卡"
        val slot = "SIM${slotIndex + 1}"
        val custom = customLabel.trim()
        if (custom.equals(slot, ignoreCase = true)) return slot
        // Accept names saved with an existing slot prefix without adding that prefix twice.
        val prefix = Regex("^SIM\\s*${slotIndex + 1}(?:\\s*[·:：-]\\s*|\\s+)", RegexOption.IGNORE_CASE)
        val name = custom.replaceFirst(prefix, "").trim().ifBlank { carrier.trim() }
        return if (name.isBlank()) slot else "$slot · $name"
    }

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
