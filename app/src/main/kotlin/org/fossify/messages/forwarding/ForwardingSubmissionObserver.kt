package org.fossify.messages.forwarding

import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/** Observes enqueue completion without blocking the caller or confusing log errors with enqueue errors. */
internal object ForwardingSubmissionObserver {
    fun observe(result: ListenableFuture<*>, executor: Executor, onAccepted: () -> Unit,
                onRejected: () -> Unit, onRecordingError: () -> Unit) {
        result.addListener({
            val accepted = try { result.get(); true }
            catch (_: InterruptedException) { Thread.currentThread().interrupt(); false }
            catch (_: Exception) { false }
            try {
                if (accepted) onAccepted() else onRejected()
            } catch (_: Exception) { onRecordingError() }
        }, executor)
    }
}
