package org.fossify.messages.remote
import android.content.Context
import org.fossify.messages.remote.repository.RemoteSourceType

data class RemoteCommandEnvelope(val sourceType:RemoteSourceType,val sourceInstanceId:String="",val sourceMessageKey:String,
 val senderId:String,val senderName:String="",val groupId:String="",val rawContent:String,val isMentioned:Boolean=false,
 val receivedAt:Long=0,val subscriptionId:Int = -1,val extraMeta:Map<String,String> = emptyMap())
sealed class RemoteProcessResult {
 data class Success(val target:String):RemoteProcessResult()
 data class Duplicate(val existingCommandId:String):RemoteProcessResult()
 data class Rejected(val reason:String,val detail:String=""):RemoteProcessResult()
 data class Ignored(val reason:String):RemoteProcessResult()
}
object RemoteCommandProcessor {
 var calls=0
 fun process(context:Context,envelope:RemoteCommandEnvelope):RemoteProcessResult {calls++;return RemoteProcessResult.Rejected("UNLISTED")}
}
data class RemoteControlPendingReceipt(val sourceInstanceId:String,val requester:String,val commandId:String="")
class EmailRemoteCommandPoller(context:Context,sourceInstanceId:String) { fun stop(){}; fun start(intervalMs:Long=60000,onStatus:(String)->Unit){} }
class WebSocketRemoteClient(context:Context,sourceInstanceId:String,onStatus:(String)->Unit) {
 fun start(){}; fun stop(){}
 companion object {fun sendReceiptOverSocket(id:String,status:String,body:String,sourceId:String)=false}
}
