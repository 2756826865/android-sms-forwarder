package androidx.work
import android.content.Context
import java.util.concurrent.TimeUnit
class Data(val values:Map<String,Any?> = emptyMap()) {
 fun getLong(k:String,d:Long)=(values[k] as? Long)?:d
 fun getInt(k:String,d:Int)=(values[k] as? Int)?:d
 fun getString(k:String)=values[k] as? String
}
fun workDataOf(vararg pairs:Pair<String,Any?>)=Data(mapOf(*pairs))
class WorkerParameters(val data:Data=Data(),val attempts:Int=0)
abstract class CoroutineWorker(val applicationContext:Context,p:WorkerParameters) {
 val inputData=p.data;val runAttemptCount=p.attempts
 abstract suspend fun doWork():Result
 data class Result(val kind:String){ companion object {
  fun success()=Result("success");fun retry()=Result("retry");fun failure()=Result("failure")
 } }
}
enum class BackoffPolicy{EXPONENTIAL}
enum class ExistingWorkPolicy{KEEP}
class OneTimeWorkRequest(val data:Data)
class OneTimeWorkRequestBuilder<T> {
 var data=Data()
 fun setInputData(d:Data)=apply{data=d}
 fun setBackoffCriteria(p:BackoffPolicy,n:Long,u:TimeUnit)=apply{check(n==10L&&p==BackoffPolicy.EXPONENTIAL)}
 fun build()=OneTimeWorkRequest(data)
}
class WorkManager(val context:Context){
 fun enqueueUniqueWork(n:String,p:ExistingWorkPolicy,r:OneTimeWorkRequest){context.enqueued.add(Triple(n,p,r))}
 companion object{fun getInstance(c:Context)=WorkManager(c)}
}
