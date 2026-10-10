package org.fossify.messages.remote

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList
import org.fossify.messages.remote.repository.*
import org.fossify.messages.remote.runtime.RemoteSourceRuntimeManager
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

// Android storage and SMS engine are test doubles. The poller and runtime manager are production sources.
class BotWhitelistHostIntegrationTest {
    class HostContext : ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,arrayOf(SharedPreferences::class.java)
        ) { _, method, args -> when(method.name) { "getLong" -> args?.get(1) ?: 0L; "getString" -> args?.get(1); else -> null } } as SharedPreferences
    }
    private fun context(): Context {
        val field=sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe").apply { isAccessible=true }
        return (field.get(null) as sun.misc.Unsafe).allocateInstance(HostContext::class.java) as Context
    }
    private fun update(text: String,group: Boolean=false) = JSONObject().put("message",JSONObject()
        .put("message_id",12).put("from",JSONObject().put("id",123456))
        .put("chat",JSONObject().put("id",if(group)-100123456 else 123456).put("type",if(group)"supergroup" else "private"))
        .put("text",text))
    private fun process(poller:TelegramRemotePoller,update:JSONObject) = TelegramRemotePoller::class.java
        .getDeclaredMethod("processUpdate",JSONObject::class.java,String::class.java,String::class.java,String::class.java)
        .apply { isAccessible=true }.invoke(poller,update,"unused","fake-token","test-source")

    @Test fun telegramUnlistedUserGetsIdsThroughOriginalChatWithoutSmsOrAutomaticEnrollment() {
        val bodies=CopyOnWriteArrayList<String>()
        val server=HttpServer.create(InetSocketAddress("127.0.0.1",0),0)
        server.createContext("/botfake-token/sendMessage") { exchange ->
            bodies += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val response="{\"ok\":true}".toByteArray()
            exchange.sendResponseHeaders(200,response.size.toLong());exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val source=RemoteSourceInstance("test-source",configJson=JSONObject().put("botToken","fake-token")
                .put("customHost","http://127.0.0.1:${server.address.port}").toString())
            RemoteSourceRepository.instance.sources[source.id]=source
            RemoteCommandProcessor.calls=0
            val poller=TelegramRemotePoller(context(),source.id,{})
            assertEquals(TelegramRemotePoller.UpdateProcessResult.CONSUMED,process(poller,update("@test_bot 申请群白名单",true)))
            val reply=JSONObject(bodies.single())
            assertEquals("-100123456",reply.getString("chat_id"))
            assertTrue(reply.getString("text").contains("user_id）：\n123456\n"))
            assertTrue(reply.getString("text").contains("chat_id）：\n-100123456\n"))
            assertTrue(source.authorizedUsers.isEmpty())
            assertEquals(0,RemoteCommandProcessor.calls)
            // Same callback and unmentioned group discovery cannot emit another reply.
            process(poller,update("@test_bot 申请群白名单",true))
            process(poller,update("申请用户白名单",true).apply { getJSONObject("message").put("message_id",13) })
            assertEquals(1,bodies.size)
        } finally { server.stop(0);RemoteSourceRepository.instance.sources.clear() }
    }

    @Test fun telegramDisabledSourceNeverRepliesAndNeverCallsSmsProcessor() {
        RemoteSourceRepository.instance.sources["test-source"]=RemoteSourceInstance("test-source",enabled=false,
            configJson="{\"botToken\":\"fake-token\"}")
        RemoteCommandProcessor.calls=0
        val poller=TelegramRemotePoller(context(),"test-source",{})
        assertEquals(TelegramRemotePoller.UpdateProcessResult.IGNORED_PERMANENTLY,process(poller,update("申请用户白名单")))
        assertEquals(0,RemoteCommandProcessor.calls)
        RemoteSourceRepository.instance.sources.clear()
    }

    @Test fun runtimeRejectsDisabledStaleAndUnknownHandlesBeforeReplyEnqueue() {
        val context=context()
        val constructor=RemoteSourceRuntimeManager::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible=true }
        val manager=constructor.newInstance(context)
        val source=RemoteSourceInstance("test-source",type=RemoteSourceType.DINGTALK,enabled=false)
        RemoteSourceRepository.instance.sources[source.id]=source
        val client=DingTalkStreamClient("unused","unused",onCommand={},onStatus={},onWhitelistRequest={})
        val handle=RemoteSourceRuntimeManager.RuntimeHandle.DingTalk(source.id,"stale",client)
        val handles=RemoteSourceRuntimeManager::class.java.getDeclaredField("runningHandles").apply { isAccessible=true }
        @Suppress("UNCHECKED_CAST") val map=handles.get(manager) as java.util.concurrent.ConcurrentHashMap<String,RemoteSourceRuntimeManager.RuntimeHandle>
        map[source.id]=handle
        val invoke=RemoteSourceRuntimeManager::class.java.getDeclaredMethod("replyToWhitelistRequest",RemoteSourceRuntimeManager.RuntimeHandle::class.java,BotWhitelistRequest::class.java).apply { isAccessible=true }
        val request=BotWhitelistRequest("message",DingTalkWhitelistRequest.Kind.USER,"钉钉","001","senderStaffId","cid","conversationId",true,true,"https://oapi.dingtalk.com/unused")
        invoke.invoke(manager,handle,request)
        source.enabled=true
        invoke.invoke(manager,handle,request)
        map.clear()
        invoke.invoke(manager,handle,request)
        val executor=RemoteSourceRuntimeManager::class.java.getDeclaredField("whitelistReplyExecutor").apply { isAccessible=true }.get(manager) as java.util.concurrent.ThreadPoolExecutor
        assertEquals(0,executor.taskCount)
        executor.shutdownNow()
        RemoteSourceRepository.instance.sources.clear()
    }
}
