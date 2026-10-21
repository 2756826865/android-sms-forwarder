package org.fossify.messages.forwarding

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DeliveryFailurePolicyTest {
    @Test fun acknowledgementTimeoutIsUnknown() {
        val error = assertThrows(IOException::class.java) { DeliveryFailurePolicy.requireRemoteAccepted(false,"ack_timeout","timeout") }
        assertTrue(DeliveryFailurePolicy.isUnknown(error))
    }
    @Test fun invalidAckIsUnknown() {
        assertThrows(IOException::class.java) { DeliveryFailurePolicy.requireRemoteAccepted(false,"invalid_ack","invalid") }
    }
    @Test fun explicitRejectionIsNotUnknown() {
        val error = assertThrows(IllegalStateException::class.java) { DeliveryFailurePolicy.requireRemoteAccepted(false,"rejected","rejected",400) }
        assertFalse(DeliveryFailurePolicy.isUnknown(error))
    }
    @Test fun acceptedMessageSucceeds() { DeliveryFailurePolicy.requireRemoteAccepted(true,"success","accepted") }
    @Test fun preparationIoFailureIsNotSubmitted() {
        assertFalse(DeliveryFailurePolicy.isUnknown(SmsDirectPreSubmitFailure(IOException("preparation"))))
    }
}
