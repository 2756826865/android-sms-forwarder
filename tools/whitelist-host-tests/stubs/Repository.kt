package org.fossify.messages.remote.repository
import android.content.Context
import org.json.JSONObject

enum class RemoteSourceType { SMS, TELEGRAM, DINGTALK, FEISHU, WECOM, WEBSOCKET, EMAIL }
enum class RemoteSourceConnectionState { CONFIG_REQUIRED, CONNECTING, READY, ERROR, DISABLED }
data class RemoteSourceInstance(val id:String,val name:String="test",val type:RemoteSourceType=RemoteSourceType.TELEGRAM,
    var enabled:Boolean=true,var configJson:String="{}",val customCommandPrefix:String="",val whitelistEnabled:Boolean=true,
    val authorizedUsers:Set<String> = emptySet(),val authorizedGroups:Set<String> = emptySet(),val requireMention:Boolean=false,
    val defaultSimMode:Int=0,val quietHoursEnabled:Boolean=false,val quietHoursStart:Int=23,val quietHoursEnd:Int=7,
    val hourlyLimit:Int=10,val dailyLimit:Int=100,val connectionState:RemoteSourceConnectionState=RemoteSourceConnectionState.READY,
    val lastErrorMessage:String="") {
    fun hasValidCredentials()=true
    fun optString(key:String)=JSONObject(configJson).optString(key)
}
class RemoteSourceRepository {
    val sources=mutableMapOf<String,RemoteSourceInstance>()
    fun getSourceById(id:String)=sources[id]
    fun getEnabledSources()=sources.values.filter { it.enabled }
    fun updateConnectionState(id:String,state:RemoteSourceConnectionState,errorMessage:String="",errorCode:Int=0) {}
    companion object { val instance=RemoteSourceRepository(); fun getInstance(context:Context)=instance }
}
