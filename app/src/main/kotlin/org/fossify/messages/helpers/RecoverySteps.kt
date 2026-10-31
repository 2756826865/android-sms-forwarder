package org.fossify.messages.helpers

import java.util.concurrent.CancellationException

/** Run independent recovery registrations without one failed subsystem hiding the rest. */
internal class RecoverySteps(private val report: (String, Exception?) -> Unit) {
    fun run(name: String, action: () -> Unit) {
        val error = try {
            action()
            null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            failure
        }
        report(name, error)
    }
}
