package org.fossify.messages.forwarding

import java.util.Base64
import java.nio.charset.StandardCharsets

internal object NtfyProtocol {
    private val topicPattern = Regex("[-_A-Za-z0-9]{1,64}")

    fun requireTopic(topic: String): String = topic.trim().also {
        require(topicPattern.matches(it)) { "ntfy Topic 只能包含字母、数字、下划线和连字符（最长 64 字符）" }
    }

    fun titleHeader(title: String): String = if (title.all { it.code in 32..126 }) {
        title
    } else {
        "=?UTF-8?B?${Base64.getEncoder().encodeToString(title.toByteArray(StandardCharsets.UTF_8))}?="
    }
}
