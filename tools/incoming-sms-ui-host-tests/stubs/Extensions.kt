package org.fossify.messages.extensions
import android.content.Context
class Dao(val c:Context){fun getMessageWithId(id:Long)=c.local[id]}
val Context.messagesDB get()=Dao(this)
fun Context.syncThreadToLocal(id:Long){
 synced.add(id)
 if(!noOpSync)rows.filter{(it["thread_id"] as Number).toLong()==id}.forEach{
  val key=(it["id"] as Number).toLong();local[key]=android.content.LocalMessage(key,id)
 }
}
