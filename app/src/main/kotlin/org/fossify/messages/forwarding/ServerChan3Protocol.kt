package org.fossify.messages.forwarding

import org.json.JSONObject

internal object ServerChan3Protocol {
    fun requireAccepted(response: JSONObject) {
        check(response.has("code") && response.optInt("code", -1) == 0) {
            "Server酱³ 服务端未受理请求（错误码 ${response.optInt("code", -1)}）"
        }
    }
}
