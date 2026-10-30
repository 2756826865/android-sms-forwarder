package org.fossify.messages.extensions
import android.content.Context
class Dao(val c:Context){fun getMessageWithId(id:Long)=c.local[id]}
val Context.messagesDB get()=Dao(this)
class ConversationDao(val c:Context){fun getConversationWithThreadId(id:Long)=id.takeIf{it in c.conversations}}
val Context.conversationsDB get()=ConversationDao(this)
fun Context.syncThreadToLocal(id:Long){
 synced.add(id)
 if(!noOpSync)rows.filter{(it["thread_id"] as Number).toLong()==id}.forEach{
  val key=(it["id"] as Number).toLong();local[key]=android.content.LocalMessage(key,id)
  if(!noConversationSync)conversations.add(id)
 }
}
