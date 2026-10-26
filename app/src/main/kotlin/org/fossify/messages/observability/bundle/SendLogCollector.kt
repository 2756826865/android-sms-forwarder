package org.fossify.messages.observability.bundle

import android.content.Context
import org.fossify.messages.extensions.getMessagesDB
import org.fossify.messages.forwarding.ForwardingHistoryStore
import org.json.JSONArray
import org.json.JSONObject

/** Read persisted outcomes without sending messages or exporting message bodies and numbers. */
object SendLogCollector {
    suspend fun collect(context: Context): JSONObject {
        val root = JSONObject().put("generatedAt", System.currentTimeMillis())
            .put("scope", "Recent retained SMS operations (up to 500) and channel records (up to 2000); not lifetime history. Numbers and bodies omitted.")
        val sections = JSONObject()
        root.put("collectionStatus", sections)
        try {
            val records = context.getMessagesDB().SmsSendDao().getRecentOperations(500)
            root.put("smsOperations", JSONArray().apply {
                records.forEach { r -> put(JSONObject()
                    .put("operationId", r.sendOperationId).put("trigger", r.triggerType)
                    .put("subscriptionId", r.subscriptionId ?: JSONObject.NULL)
                    .put("state", r.state).put("errorClass", r.errorClass ?: JSONObject.NULL)
                    .put("partCount", r.partCount ?: JSONObject.NULL)
                    .put("createdAt", r.createdAt).put("updatedAt", r.updatedAt)
                    .put("submittedAt", r.submittedAt ?: JSONObject.NULL)
                    .put("sentAt", r.sentAt ?: JSONObject.NULL)
                    .put("deliveredAt", r.deliveredAt ?: JSONObject.NULL)
                    .put("failedAt", r.failedAt ?: JSONObject.NULL)) }
            })
            sections.put("smsOperations", if (records.isEmpty()) "no_retained_records" else "collected")
        } catch (e: Exception) {
            sections.put("smsOperations", "collection_failed:${e.javaClass.simpleName}")
        }
        try {
            val store = ForwardingHistoryStore(context)
            val records = store.records(2000)
            root.put("channelOperations", JSONArray().apply {
                records.forEach { r -> put(JSONObject()
                    .put("recordId", r.recordId).put("workId", r.workId).put("channel", r.channel)
                    .put("subscriptionId", r.subscriptionId).put("status", r.status)
                    .put("attempts", r.attempts).put("receivedAt", r.receivedAt)
                    .put("updatedAt", r.updatedAt).put("isTest", r.isTest)
                    .put("detail", r.detail.replace(Regex("https?://\\S+"), "[URL]")
                        .replace(Regex("\\d{5,}"), "[REDACTED]"))) }
            })
            sections.put("channelOperations", if (records.isEmpty()) "no_retained_records" else "collected")
        } catch (e: Exception) {
            sections.put("channelOperations", "collection_failed:${e.javaClass.simpleName}")
        }
        return root
    }
}
