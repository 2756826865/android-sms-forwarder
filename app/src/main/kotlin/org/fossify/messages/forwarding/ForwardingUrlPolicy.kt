package org.fossify.messages.forwarding

import java.net.URI

object ForwardingUrlPolicy {
    fun isAllowed(url: String, allowPrivateHttp: Boolean): Boolean = runCatching {
        val uri = URI(url.trim())
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase()?.removePrefix("[")?.removeSuffix("]").orEmpty()
        when (scheme) {
            "https" -> host.isNotBlank()
            "http" -> allowPrivateHttp && isPrivateHost(host)
            else -> false
        }
    }.getOrDefault(false)

    fun requireAllowed(url: String, allowPrivateHttp: Boolean) {
        require(isAllowed(url, allowPrivateHttp)) {
            "地址默认必须使用 HTTPS；HTTP 仅允许局域网地址"
        }
    }

    private fun isPrivateHost(host: String): Boolean {
        if (host.isBlank()) return false
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return true
        if (':' in host) {
            return host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe8") ||
                host.startsWith("fe9") || host.startsWith("fea") || host.startsWith("feb")
        }
        if (host.none { it == '.' }) return true

        val octets = host.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            octets[0] == 127 ||
            octets[0] == 169 && octets[1] == 254 ||
            octets[0] == 192 && octets[1] == 168 ||
            octets[0] == 172 && octets[1] in 16..31
    }
}
