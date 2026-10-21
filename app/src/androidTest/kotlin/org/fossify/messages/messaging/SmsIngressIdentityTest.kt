package org.fossify.messages.messaging

import android.content.Context
import org.fossify.messages.testing.ForwardingTestContext
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class SmsIngressIdentityTest {
    private val context: Context get() = ForwardingTestContext.context
    private fun key() = UUID.randomUUID().toString()
    private fun id() = (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE).coerceAtLeast(1)
    @Test fun broadcastAndProviderWithZeroSentTimeShareIdentity() {
        val body=key();val fp=key();val n=id()
        val a=SmsIngressIdentity(context).broadcast("fake",body,10,10000,1,fp)
        assertEquals(a,SmsIngressIdentity(context).provider("fake",body,0,9900,1,n))
        assertEquals(a,SmsIngressIdentity(context).provider("fake",body,0,9900,1,n))
    }
    @Test fun providerFirstAndDuplicateBroadcastShareIdentity() {
        val body=key();val fp=key();val n=id()
        val a=SmsIngressIdentity(context).provider("fake",body,0,10000,1,n)
        assertEquals(a,SmsIngressIdentity(context).broadcast("fake",body,10,10001,1,fp))
        assertEquals(a,SmsIngressIdentity(context).broadcast("fake",body,10,20000,1,fp))
    }
    @Test fun differentSimsDoNotMerge() {
        val body=key()
        val a=SmsIngressIdentity(context).broadcast("fake",body,10,10000,1,key())
        assertNotEquals(a.workId,SmsIngressIdentity(context).provider("fake",body,0,10000,2,id()).workId)
    }
    @Test fun twoIdenticalSmsStaySeparateWhenAmbiguous() {
        val body=key()
        val a=SmsIngressIdentity(context).broadcast("fake",body,10,10000,1,key())
        val b=SmsIngressIdentity(context).broadcast("fake",body,11,10001,1,key())
        val c=SmsIngressIdentity(context).provider("fake",body,0,10000,1,id())
        assertEquals(3,setOf(a.workId,b.workId,c.workId).size)
    }
    @Test fun incompatibleSentTimeDoesNotMerge() {
        val body=key()
        val a=SmsIngressIdentity(context).broadcast("fake",body,10,10000,1,key())
        assertNotEquals(a.workId,SmsIngressIdentity(context).provider("fake",body,11,10000,1,id()).workId)
    }
    @Test fun boundProviderCannotAbsorbAnotherSms() {
        val body=key();val a=SmsIngressIdentity(context).broadcast("fake",body,10,10000,1,key())
        assertEquals(a,SmsIngressIdentity(context).provider("fake",body,0,10000,1,id()))
        assertNotEquals(a.workId,SmsIngressIdentity(context).provider("fake",body,0,10000,1,id()).workId)
    }
    @Test fun reusedProviderIdHasNewOwner() {
        val n=id();val a=SmsIngressIdentity(context).provider("fake",key(),0,10000,1,n)
        val b=SmsIngressIdentity(context).provider("fake",key(),0,20000,1,n)
        assertNotEquals(a.workId,b.workId)
    }
    @Test fun concurrentAliasesKeepOneOwner() {
        val body=key();val fp=key();val n=id();val pool=java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val results=pool.invokeAll((0 until 16).map { i -> java.util.concurrent.Callable {
                if(i%2==0)SmsIngressIdentity(context).broadcast("fake",body,10,10000,1,fp)
                else SmsIngressIdentity(context).provider("fake",body,0,10000,1,n)
            } }).map{it.get().workId}.toSet()
            assertEquals(1,results.size)
        } finally {pool.shutdownNow()}
    }
}
