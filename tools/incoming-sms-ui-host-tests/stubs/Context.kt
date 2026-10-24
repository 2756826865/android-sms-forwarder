package android.content
import androidx.work.OneTimeWorkRequest
import androidx.work.ExistingWorkPolicy
class Context {
 val applicationContext get()=this
 var permission=true; var queries=0; var closes=0; var nullCursor=false; var noOpSync=false
 var delayQueries=0; var throwQuery:Exception?=null; var throwRead:Exception?=null
 var rows=emptyList<Map<String,Any?>>()
 val contentResolver=Resolver(this)
 val local=mutableMapOf<Long,LocalMessage>();val synced=mutableListOf<Long>()
 val enqueued=mutableListOf<Triple<String,ExistingWorkPolicy,OneTimeWorkRequest>>()
 fun insert():Nothing=error("System Provider insert forbidden")
 fun update():Nothing=error("System Provider update forbidden")
 fun delete():Nothing=error("System Provider delete forbidden")
}
data class LocalMessage(val id:Long,val threadId:Long)
class Resolver(val c:Context) {
 fun query(uri:String,projection:Array<String>,selection:String?,args:Array<String>?,order:String?):Cursor? {
  c.queries++; c.throwQuery?.let{throw it}
  check(selection!!.contains("type=?")&&selection.contains("date>=?")&&selection.contains("date<=?"))
  check(args!![0]=="1"&&args[1].toLong()>=0&&args[2].toLong()>args[1].toLong())
  if(c.nullCursor)return null
  return Cursor(c, if(c.queries<=c.delayQueries)emptyList() else c.rows,projection)
 }
}
class Cursor(val c:Context,val rows:List<Map<String,Any?>>,val columns:Array<String>):java.io.Closeable {
 var i=-1
 fun getColumnIndex(s:String)=columns.indexOf(s)
 fun getColumnIndexOrThrow(s:String)=getColumnIndex(s).also{check(it>=0)}
 fun moveToNext():Boolean{c.throwRead?.let{throw it};i++;return i<rows.size}
 fun getLong(i:Int)=(rows[this.i][columns[i]] as Number).toLong()
 fun isNull(i:Int)=rows[this.i][columns[i]]==null
 fun getInt(i:Int)= (rows[this.i][columns[i]] as? Number)?.toInt()?:0
 fun getString(i:Int)=rows[this.i][columns[i]] as? String
 override fun close(){c.closes++}
}
