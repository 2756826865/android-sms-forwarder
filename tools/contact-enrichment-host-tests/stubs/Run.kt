package check
import android.content.Context
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.fossify.messages.messaging.SmsContactEnrichmentWorker
fun main() = runBlocking {
    suspend fun execute(context: Context, attempt: Int = 0) =
        SmsContactEnrichmentWorker(context, WorkerParameters(workDataOf("message_id" to 9L), attempt)).doWork().kind
    var scenarios = 0
    for (stage in listOf("lookup", "contact", "transaction")) {
        fun failing() = Context().also {
            val error = IllegalStateException("simulated-$stage")
            when(stage) { "lookup" -> it.lookupError = error; "contact" -> it.contactError = error; else -> it.transactionError = error }
        }
        for(attempt in 0..2) {
            val c = failing()
            check(execute(c, attempt) == "retry")
            check(c.failures.isEmpty())
            scenarios++
        }
        val exhausted = failing()
        check(execute(exhausted, 3) == "failure")
        check(exhausted.failures["last_failure_message_id"] == 9L)
        check(exhausted.failures["last_failure_attempts"] == 4)
        check(exhausted.failures["last_failure_reason"] == "IllegalStateException")
        scenarios++
    }
    check(execute(Context().also { it.present = false }) == "success"); scenarios++
    val healthy = Context()
    check(execute(healthy) == "success" && healthy.writes == 2); scenarios++
    for(stage in listOf("lookup", "contact", "transaction")) {
        val c = Context()
        val cancellation = CancellationException("cancel")
        when(stage) { "lookup" -> c.lookupError = cancellation; "contact" -> c.contactError = cancellation; else -> c.transactionError = cancellation }
        try { execute(c); error("Cancellation swallowed") } catch (_: CancellationException) {}
        check(c.failures.isEmpty()); scenarios++
    }
    SmsContactEnrichmentWorker.enqueue(Context(), 9L); scenarios++
    println("Production contact enrichment worker: $scenarios scenarios passed; Android/Room/WorkManager boundaries simulated")
}
