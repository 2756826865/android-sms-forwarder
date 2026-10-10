package org.fossify.messages.forwarding
import android.content.Context
class MultiForwardConfig(context:Context) {
 var lastCapturedWeComChatId=""
 fun appendTelegramRemoteLog(s:String) {}
 fun appendDingTalkRemoteLog(s:String) {}
 fun appendFeishuRemoteLog(s:String) {}
 fun appendWeComRemoteLog(s:String) {}
 fun appendWebSocketRemoteLog(s:String) {}
 fun appendEmailRemoteLog(s:String) {}
}
