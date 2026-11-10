package org.fossify.messages.messaging

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Base64
import java.security.MessageDigest
import org.fossify.messages.forwarding.ForwardingMessageFormatter
import org.fossify.messages.forwarding.ForwardingSimSnapshot
import org.fossify.messages.security.crypto.KeyStoreHelper
import org.fossify.messages.services.IncomingSmsService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Intake uses its own short transaction, independent of slow processing/contacts/queue submission.
 */
internal class SmsIngressStaging(
    private val app: Context,
    private val receipts: SmsIngressJournal,
) {
    private val db
        get() = SmsIngressDatabase.get(app).writableDatabase

    private data class Text(val address: String, val body: String, val sentAt: Long)

    private data class Parsed(
        val source: Intent,
        val pdus: List<ByteArray>,
        val hash: String,
        val identity: ReceivingIdentityPolicy.Result,
        val alias: String,
        val now: Long,
        val text: Text,
    ) {
        val rawSub
            get() = identity.observedSubscriptionId ?: -1

        val address
            get() = text.address

        val body
            get() = text.body

        val sentAt
            get() = text.sentAt
    }

    fun stage(source: Intent): Intent =
        synchronized(lock) {
            receipts.receipt(source)?.let {
                return@synchronized Intent(source)
            }
            val parsed = parse(source)
            db.beginTransaction()
            try {
                val key =
                    findSpoolKey(source)
                        ?: findKey(parsed)
                        ?: java.util.UUID.randomUUID().toString().also {
                            insertReceipt(it, parsed)
                        }
                supplement(key, parsed.identity)
                db.execSQL(
                    "INSERT OR REPLACE INTO ingress_alias(alias,event_key) VALUES(?,?)",
                    arrayOf(parsed.alias, key),
                )
                source.getStringExtra(SmsIngressSpool.EXTRA_ID)?.let { id ->
                    db.execSQL(
                        "INSERT OR IGNORE INTO ingress_alias(alias,event_key) VALUES(?,?)",
                        arrayOf("spool:$id", key),
                    )
                }
                db.setTransactionSuccessful()
                Intent(source).putExtra(SmsIngressJournal.EXTRA_EVENT, key)
            } finally {
                db.endTransaction()
            }
        }

    private fun parse(source: Intent): Parsed {
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(source)
        require(parts.isNotEmpty()) { "missing_pdus" }
        val address = parts.last().originatingAddress.orEmpty()
        require(address.isNotBlank()) { "missing_sender" }
        @Suppress("DEPRECATION")
        val pdus = source.getSerializableExtra("pdus") as? Array<*> ?: error("missing_pdus")
        val bytes = pdus.map { it as? ByteArray ?: error("invalid_pdu") }
        require(bytes.sumOf { it.size } <= MAX_PDU_BYTES) { "pdu_payload_too_large" }
        val digest = MessageDigest.getInstance("SHA-256")
        bytes.forEach {
            digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(it.size).array())
            digest.update(it)
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and UBYTE_MASK) }
        val identity = ReceivingSubscriptionReader.read(app, source)
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val sentAt = parts.minOf { it.timestampMillis }.coerceAtLeast(0L)
        val alias =
            IncomingSmsService.fingerprint(
                address,
                body,
                sentAt,
                identity.observedSubscriptionId ?: -1,
            )
        return Parsed(
            source,
            bytes,
            hash,
            identity,
            alias,
            source.getLongExtra(SmsIngressSpool.EXTRA_RECEIVED_AT, System.currentTimeMillis()),
            Text(address, body, sentAt),
        )
    }

    private fun findSpoolKey(source: Intent): String? {
        val id = source.getStringExtra(SmsIngressSpool.EXTRA_ID) ?: return null
        return db.rawQuery(
                "SELECT event_key FROM ingress_alias WHERE alias=?",
                arrayOf("spool:$id"),
            )
            .use {
                if (it.moveToFirst()) it.getString(0) else null
            }
    }

    private fun findKey(p: Parsed): String? {
        val direct =
            db.rawQuery(
                    """
                    SELECT j.event_key FROM ingress_alias a JOIN ingress_journal j ON j.event_key=a.event_key
                                   WHERE a.alias=? AND j.pdu_hash=? AND j.received_at BETWEEN ? AND ?
                    """
                        .trimIndent(),
                    arrayOf(
                        p.alias,
                        p.hash,
                        (p.now - PAIR_WINDOW_MS).toString(),
                        (p.now + PAIR_WINDOW_MS).toString(),
                    ),
                )
                .use { if (it.moveToFirst()) it.getString(0) else null }
        if (direct != null) return direct
        return db.rawQuery(
                """
                SELECT event_key FROM ingress_journal WHERE pdu_hash=?
                            AND received_at BETWEEN ? AND ? AND first_action!=?
                AND (confirmed_sub=? OR (confirmed_sub<0 AND (raw_sub=? OR raw_sub<0)) OR CAST(? AS INTEGER)<0) LIMIT 2
                """
                    .trimIndent(),
                arrayOf(
                    p.hash,
                    (p.now - PAIR_WINDOW_MS).toString(),
                    p.now.toString(),
                    p.source.action.orEmpty(),
                    p.rawSub.toString(),
                    p.rawSub.toString(),
                    p.rawSub.toString(),
                ),
            )
            .use {
                if (it.count == 1 && it.moveToFirst()) it.getString(0) else null
            }
    }

    private fun insertReceipt(key: String, p: Parsed) {
        val payload =
            JSONObject()
                .put("action", p.source.action)
                .put("format", p.source.getStringExtra("format"))
                .put("pdus", JSONArray(p.pdus.map { Base64.encodeToString(it, Base64.NO_WRAP) }))
        db.execSQL(
            """
            INSERT INTO ingress_journal(event_key,pdu_hash,first_action,raw_sub,confirmed_sub,slot,evidence,
                        observed_at,received_at,payload,routing_digest) VALUES(?,?,?,?,?,?,?,?,?,?,?)
            """
                .trimIndent(),
            arrayOf<Any?>(
                key,
                p.hash,
                p.source.action.orEmpty(),
                p.rawSub,
                p.identity.confirmedSubscriptionId ?: -1,
                p.identity.slotIndex ?: -1,
                p.identity.source.name +
                    ":" +
                    p.identity.fieldName +
                    ":" +
                    p.identity.status.name +
                    ":" +
                    p.identity.reason,
                p.identity.observedAt,
                p.now,
                KeyStoreHelper.encrypt(payload.toString()),
                SmsIngressRouting(app).admissionDigest(),
            ),
        )
        val knownAlias =
            db.rawQuery("SELECT 1 FROM ingress_alias WHERE alias=?", arrayOf(p.alias)).use {
                it.moveToFirst()
            }
        if (!knownAlias && IncomingSmsService.wasPersisted(app, p.alias)) bindLegacyReceipt(key, p)
    }

    private fun bindLegacyReceipt(key: String, p: Parsed) {
        var oldId = -1L
        var oldDate = p.now
        runCatching {
            app.contentResolver
                .query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(Telephony.Sms._ID, Telephony.Sms.DATE),
                    "address=? AND body=? AND date_sent=? AND sub_id=? AND type=1",
                    arrayOf(p.address, p.body, p.sentAt.toString(), p.rawSub.toString()),
                    null,
                )
                ?.use {
                    if (it.count == 1 && it.moveToFirst()) {
                        oldId = it.getLong(0)
                        oldDate = it.getLong(1)
                    }
                }
        }
        db.execSQL(
            "UPDATE ingress_journal SET provider_attempted=1,provider_id=?,received_at=?,reason=? WHERE event_key=?",
            arrayOf<Any?>(oldId, oldDate, if (oldId > 0) "" else "provider_result_uncertain", key),
        )
    }

    private fun supplement(key: String, identity: ReceivingIdentityPolicy.Result) {
        val sub = identity.confirmedSubscriptionId
        if (sub == null) {
            val description = unknownDescription(identity.reason)
            SmsIngressSnapshots(app)
                .saveReceipt(
                    key,
                    ForwardingSimSnapshot(
                        -1,
                        description,
                        "",
                        "",
                        status = identity.status.name,
                        source = identity.source.name + ":" + identity.fieldName,
                        observedAt = identity.observedAt,
                    ),
                )
            return
        }
        db.execSQL(
            """
            UPDATE ingress_journal SET confirmed_sub=?,slot=?,evidence=?,observed_at=?
                        WHERE event_key=? AND confirmed_sub<0
            """
                .trimIndent(),
            arrayOf<Any?>(
                sub,
                identity.slotIndex ?: -1,
                identity.source.name +
                    ":" +
                    identity.fieldName +
                    ":" +
                    identity.status.name +
                    ":" +
                    identity.reason,
                identity.observedAt,
                key,
            ),
        )
        val snapshot =
            if (identity.slotIndex != null)
                ForwardingMessageFormatter.captureSim(app, sub, identity.slotIndex)
            else
                ForwardingSimSnapshot(
                    -1,
                    "订阅已识别，卡槽映射暂不可确认",
                    "",
                    "",
                    sub,
                    source = identity.source.name,
                    observedAt = identity.observedAt,
                )
        SmsIngressSnapshots(app)
            .saveReceipt(
                key,
                snapshot.copy(
                    source = identity.source.name + ":" + identity.fieldName,
                    observedAt = identity.observedAt,
                ),
            )
    }

    private fun unknownDescription(reason: String): String =
        when (reason) {
            "subscriptions_permission_denied" -> "未知接收卡（电话权限未授予）"
            "subscriptions_empty" -> "未知接收卡（订阅字段已保留，卡槽暂不可读）"
            "conflicting_fields",
            "subscription_slot_conflict" -> "未知接收卡（广播身份字段冲突）"
            "subscriptions_error" -> "未知接收卡（订阅读取异常）"
            "no_receiving_evidence" -> "未知接收卡（广播未提供接收身份）"
            "invalid_subscription_fields" -> "未知接收卡（订阅字段类型或数值无效）"
            "invalid_slot_fields" -> "未知接收卡（卡槽字段无效）"
            "ambiguous_vendor_fields" -> "未知接收卡（厂商字段含义未确认）"
            "subscription_not_uniquely_active" -> "未知接收卡（订阅当前不可核验）"
            "slot_not_uniquely_active" -> "未知接收卡（卡槽映射不唯一）"
            else -> "未知接收卡（缺少可核验的映射）"
        }

    companion object {
        private const val UBYTE_MASK = 0xff
        val lock = Any()
        private const val MAX_PDU_BYTES = 128 * 1024
        private const val PAIR_WINDOW_MS = 2000L
    }
}
