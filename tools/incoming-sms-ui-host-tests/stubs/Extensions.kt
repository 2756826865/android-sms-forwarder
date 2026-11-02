package org.fossify.messages.extensions
import android.content.Context
class Dao(val c:Context){fun getMessageWithId(id:Long)=c.local[id]}
val Context.messagesDB get()=Dao(this)
class ConversationDao(val c:Context){fun getConversationWithThreadId(id:Long)=id.takeIf{it in c.conversations}}
val Context.conversationsDB get()=ConversationDao(this)
fun Context.syncThreadToLocal(id:Long, requiredSmsIds:Set<Long> = emptySet(), requireProvider:Boolean = false){
 synced.add(id)
 requiredRequests.add(requiredSmsIds)
 strictRequests.add(requireProvider)
 throwSync?.let { throw it }
 val threadRows=rows.filter{(it["thread_id"] as Number).toLong()==id}
 val selected=(threadRows.takeLast(syncLimit)+threadRows.filter{(it["id"] as Number).toLong() in requiredSmsIds})
     .distinctBy{(it["id"] as Number).toLong()}
 if(!noOpSync)selected.forEach{
  val key=(it["id"] as Number).toLong();local[key]=android.content.LocalMessage(key,id)
  if(!noConversationSync)conversations.add(id)
 }
}

fun android.content.Context.clearPendingSmsNotification(event: String) {}
