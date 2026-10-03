package org.fossify.messages.forwarding

import org.json.JSONObject
import org.junit.Test

class ServerChan3ProtocolTest {
    @Test fun acceptsExplicitCodeZero() {
        ServerChan3Protocol.requireAccepted(JSONObject("""{"code":0,"message":"SUCCESS"}"""))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsHttpStyleCodeTwoHundred() {
        ServerChan3Protocol.requireAccepted(JSONObject("""{"code":200,"message":"SUCCESS"}"""))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsMissingBusinessCode() {
        ServerChan3Protocol.requireAccepted(JSONObject("""{"message":"SUCCESS"}"""))
    }

    @Test(expected = IllegalStateException::class)
    fun rejectsFailureCodeEvenWhenMessageSaysSuccess() {
        ServerChan3Protocol.requireAccepted(JSONObject("""{"code":500,"message":"success later"}"""))
    }
}
