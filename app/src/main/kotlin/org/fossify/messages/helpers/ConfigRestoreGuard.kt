package org.fossify.messages.helpers

import android.content.Context
import android.util.AtomicFile
import org.fossify.messages.forwarding.AndroidKeystoreCipher
import org.json.JSONObject
import java.io.File

/** Local encrypted journal; never a portable backup or a promise of cross-store atomicity. */
internal object ConfigRestoreGuard {
    private val names = listOf("Prefs", "multi_channel_forwarding", "pushplus_forwarding", "forwarding_rules",
        "sms_auto_reply", "remote_sms_command", "remote_source_repository_prefs") + BackupPreferences.portableKeys.keys
    private fun configKeys(name: String): Set<String>? = when (name) {
        "remote_sms_command" -> setOf("enabled", "authorized_numbers", "custom_prefix")
        "remote_source_repository_prefs" -> setOf("remote_sources", "auto_backfilled_whitelist_ids")
        else -> null
    }
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "config-restore-pending.enc"))
    private fun retained(context: Context) = File(context.noBackupFilesDir, "config-before-restore.enc")
    private fun state(context: Context, text: String) {
        context.getSharedPreferences("config_restore_status", Context.MODE_PRIVATE).edit().putString("status", text).commit()
    }
    fun status(context: Context): String = context.getSharedPreferences("config_restore_status", Context.MODE_PRIVATE)
        .getString("status", "尚未执行配置恢复").orEmpty()
    fun hasPending(context: Context): Boolean = file(context).baseFile.exists() || File(file(context).baseFile.path + ".bak").exists()
    @Synchronized fun begin(context: Context) {
        check(!hasPending(context)) { "Pending rollback must complete first" }
        val root = JSONObject()
        names.distinct().forEach { name ->
            val values = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            val keys = configKeys(name)
            root.put(name, BackupPreferences.encode(if (keys == null) values else values.filterKeys { it in keys }))
        }
        val encrypted = AndroidKeystoreCipher.encrypt(root.toString())
        check(encrypted.isNotBlank()) { "Cannot protect rollback snapshot" }
        // Keep one encrypted pre-import snapshot even after success, excluded from Android Backup.
        val retainedFile = AtomicFile(retained(context))
        val retainedStream = retainedFile.startWrite()
        try { retainedStream.write(encrypted.toByteArray(Charsets.UTF_8)); retainedFile.finishWrite(retainedStream) }
        catch (e: Exception) { retainedFile.failWrite(retainedStream); throw e }
        val journal = file(context)
        val stream = journal.startWrite()
        try { stream.write(encrypted.toByteArray(Charsets.UTF_8)); journal.finishWrite(stream) }
        catch (e: Exception) { journal.failWrite(stream); throw e }
        state(context, "恢复前本机加密备份已保存")
    }
    @Synchronized fun commit(context: Context) {
        names.distinct().forEach { name -> check(context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().commit()) }
        file(context).delete()
        check(!file(context).baseFile.exists()) { "Cannot commit configuration restore" }
        state(context, "配置恢复完成，已保留一份本机加密旧配置")
    }
    @Synchronized fun rollback(context: Context): Boolean = runCatching {
        val journal = file(context)
        if (!journal.baseFile.exists() && !File(journal.baseFile.path + ".bak").exists()) return@runCatching true
        val decrypted = AndroidKeystoreCipher.decrypt(journal.openRead().use { it.readBytes().toString(Charsets.UTF_8) })
        check(decrypted.isNotBlank())
        val root = JSONObject(decrypted)
        // Validate the entire snapshot before any write.
        val snapshots = names.distinct().associateWith { BackupPreferences.decode(root.getJSONObject(it)) }
        snapshots.forEach { (name, values) ->
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val keys = configKeys(name)
            if (keys != null) {
                // Execution dedupe/rate facts may advance during import: never rewind them.
                val merged = prefs.all.mapNotNull { (key, value) ->
                    if (key !in keys && value != null) key to value else null
                }.toMap() + values.filterKeys { it in keys }
                BackupPreferences.write(prefs, merged, replace = true)
            } else BackupPreferences.write(prefs, values, replace = true)
        }
        journal.delete()
        check(!journal.baseFile.exists())
        state(context, "已回滚至恢复前配置")
        true
    }.getOrElse { state(context, "回滚未完成，已保留加密快照；下次启动将暂停后台初始化，请重新启动或恢复原备份"); false }
}
