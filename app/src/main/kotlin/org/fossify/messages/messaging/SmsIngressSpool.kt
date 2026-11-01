package org.fossify.messages.messaging

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.util.UUID
import org.fossify.messages.security.crypto.KeyStoreHelper
import org.json.JSONArray
import org.json.JSONObject

/** Emergency encrypted copy, independent of SQLite locks. Files never participate in backups. */
internal class SmsIngressSpool(private val context: Context) {
    private val directory
        get() = File(context.noBackupFilesDir, "sms-ingress-spool").apply { mkdirs() }

    @Suppress(
        "TooGenericExceptionCaught"
    ) // Roll back AtomicFile on every platform/storage exception.
    fun save(source: Intent) {
        val id = source.getStringExtra(EXTRA_ID) ?: UUID.randomUUID().toString()
        require(java.util.UUID.fromString(id).toString() == id) { "invalid_spool_id" }
        @Suppress("DEPRECATION")
        val pdus =
            (source.getSerializableExtra("pdus") as? Array<*>)?.map {
                it as? ByteArray ?: error("invalid_pdu")
            } ?: error("missing_pdus")
        require(pdus.sumOf { it.size } <= MAX_PDU_BYTES) { "pdu_payload_too_large" }
        val extras = JSONObject()
        for (key in
            ReceivingIdentityPolicy.subscriptionKeys +
                ReceivingIdentityPolicy.slotKeys +
                ReceivingIdentityPolicy.ambiguousVendorKeys) {
            @Suppress("DEPRECATION") val value = source.extras?.get(key)
            if (value is Int || value is Long || value is String) extras.put(key, value)
        }
        val payload =
            JSONObject()
                .put("id", id)
                .put("action", source.action)
                .put("at", source.getLongExtra(EXTRA_RECEIVED_AT, System.currentTimeMillis()))
                .put("format", source.getStringExtra("format"))
                .put("extras", extras)
                .put("event", source.getStringExtra(SmsIngressJournal.EXTRA_EVENT))
                .put("pdus", JSONArray(pdus.map { Base64.encodeToString(it, Base64.NO_WRAP) }))
        val file = AtomicFile(File(directory, "$id.json"))
        val encrypted = KeyStoreHelper.encrypt(payload.toString()).toByteArray(Charsets.UTF_8)
        val output = file.startWrite()
        try {
            output.write(encrypted)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    fun remove(source: Intent) {
        val id = source.getStringExtra(EXTRA_ID) ?: return
        require(java.util.UUID.fromString(id).toString() == id) { "invalid_spool_id" }
        AtomicFile(File(directory, "$id.json")).delete()
    }

    fun drain(limit: Int = BATCH_SIZE) {
        directory
            .listFiles()
            ?.filter { it.extension == "json" }
            ?.sortedBy { it.lastModified() }
            ?.take(limit)
            ?.forEach { file ->
                runCatching {
                    val clear =
                        KeyStoreHelper.decrypt(
                            AtomicFile(file).readFully().toString(Charsets.UTF_8)
                        ) ?: error("spool_key_unavailable")
                    val p = JSONObject(clear)
                    val a = p.getJSONArray("pdus")
                    val intent =
                        Intent(p.getString("action"))
                            .putExtra("format", p.optString("format"))
                            .putExtra(
                                "pdus",
                                Array(a.length()) {
                                    Base64.decode(a.getString(it), Base64.NO_WRAP)
                                },
                            )
                            .putExtra(EXTRA_ID, p.getString("id"))
                            .putExtra(EXTRA_RECEIVED_AT, p.getLong("at"))
                    p.optString("event").takeIf(String::isNotBlank)?.let {
                        intent.putExtra(SmsIngressJournal.EXTRA_EVENT, it)
                    }
                    val extras = p.getJSONObject("extras")
                    extras.keys().forEach { key ->
                        when (val value = extras.get(key)) {
                            is Int -> intent.putExtra(key, value)
                            is Long -> intent.putExtra(key, value)
                            is String -> intent.putExtra(key, value)
                        }
                    }
                    SmsIngressJournal(context).stage(intent)
                    AtomicFile(file).delete() // Only after the journal transaction committed.
                }
                    .onFailure {
                        context
                            .getSharedPreferences("sms_ingress_spool_status", Context.MODE_PRIVATE)
                            .edit()
                            .putString("error", it.javaClass.simpleName)
                            .apply()
                    }
            }
    }

    fun hasPending(): Boolean = directory.listFiles()?.any { it.extension == "json" } == true

    companion object {
        const val EXTRA_ID = "org.fossify.messages.SPOOL_ID"
        const val EXTRA_RECEIVED_AT = "org.fossify.messages.RECEIVED_AT"
        private const val MAX_PDU_BYTES = 128 * 1024
        private const val BATCH_SIZE = 16
    }
}
