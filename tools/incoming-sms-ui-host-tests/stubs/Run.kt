package check
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.runBlocking
import org.fossify.messages.helpers.Counts
import org.fossify.messages.messaging.IncomingSmsUiSyncWorker as Ui
fun row(id:Long=123,thread:Long=9,sub:Int=2,address:String="sender",body:String="test")=mapOf<String,Any?>(
 "id" to id,"thread_id" to thread,"sub_id" to sub,"address" to address,"body" to body)
fun data(sub:Int=2)=workDataOf(Ui.KEY_RECEIVED_AT to 1_000_000L,
 Ui.KEY_CONTENT_HASH to Ui.contentHash("sender","test"),Ui.KEY_SUBSCRIPTION to sub)
suspend fun run(c:Context,attempts:Int=0,d:Data=data()):String{
 Counts.reset();return Ui(c,WorkerParameters(d,attempts)).doWork().kind
}
fun main()= runBlocking {
 var c=Context();c.rows=listOf(row());check(run(c)=="success");check(c.synced==listOf(9L)&&c.local[123]?.threadId==9L&&Counts.messages==1&&Counts.conversations==1&&c.closes==1)
 c=Context();c.rows=listOf(row());c.delayQueries=2;check(run(c)=="success"&&c.queries==3&&c.closes==3&&c.synced.size==1)
 c=Context();c.rows=listOf(row(sub=1));check(run(c)=="retry"&&c.synced.isEmpty()&&Counts.messages==0)
 c=Context();c.rows=listOf(row(sub=-1));check(run(c)=="success")
 c=Context();c.rows=listOf(row()+mapOf("sub_id" to null));check(run(c)=="success")
 c=Context();c.rows=listOf(row(address="other"),row(body="other"));check(run(c)=="retry"&&c.synced.isEmpty())
 c=Context();c.rows=listOf(row(id=0),row(thread=0));check(run(c)=="retry"&&c.local.isEmpty())
 c=Context();c.permission=false;check(run(c)=="retry"&&c.queries==0&&Counts.messages==0)
 c=Context();c.throwRead=SecurityException("revoked");check(run(c)=="retry"&&c.closes==1&&Counts.messages==0)
 c=Context();c.nullCursor=true;check(run(c)=="retry"&&c.queries==4)
 c=Context();c.nullCursor=true;check(run(c,12)=="failure"&&c.queries==4)
 c=Context();c.rows=listOf(row());c.noOpSync=true;check(run(c)=="retry"&&Counts.messages==0&&Counts.conversations==0)
 c=Context();c.rows=listOf(row());c.noConversationSync=true
 check(run(c)=="retry"&&c.local[123]?.threadId==9L&&Counts.messages==0&&Counts.conversations==0)
 c=Context();c.throwQuery=kotlinx.coroutines.CancellationException("cancelled");var cancelled=false
 try{run(c)}catch(e:kotlinx.coroutines.CancellationException){cancelled=true};check(cancelled&&c.queries==1)
 c=Context();check(run(c,d=Data())=="failure"&&c.queries==0)
 c=Context();c.rows=(1..256).map{row(id=it.toLong(),address="other")}+row()
 check(run(c)=="retry"&&c.synced.isEmpty()&&c.closes==4)
 c=Context();Ui.enqueue(c,"sender","test",1_000_000,2,"fingerprint");Ui.enqueue(c,"sender","test",1_000_000,2,"fingerprint")
 check(c.enqueued.size==2&&c.enqueued.map{it.first}.distinct()==listOf("sms-ui-sync-fingerprint"))
 check(c.enqueued.all{it.second==ExistingWorkPolicy.KEEP&&it.third.data.values.size==3&&it.third.data.values.values.none{v->v=="sender"||v=="test"}})
 println("Actual read-only UI worker: 17 scenarios passed; Provider and WorkManager simulated, no SMS sent")
}
