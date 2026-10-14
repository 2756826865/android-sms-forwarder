package org.fossify.messages.helpers

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog

enum class CallLogAccess { NOT_GRANTED, READABLE, EMPTY, NUMBER_UNAVAILABLE, PROVIDER_UNAVAILABLE, DENIED, FAILED }

data class CallAccessResult(
    val phoneStateGranted: Boolean,
    val callLogGranted: Boolean,
    val callLogAccess: CallLogAccess
) {
    val canEnable: Boolean get() = phoneStateGranted && callLogGranted &&
        callLogAccess in setOf(CallLogAccess.READABLE, CallLogAccess.EMPTY, CallLogAccess.NUMBER_UNAVAILABLE)

    fun summary(): String = buildString {
        appendLine("电话状态权限：${if (phoneStateGranted) "已授权" else "未授权"}")
        appendLine("通话记录权限：${if (callLogGranted) "已授权" else "未授权"}")
        append("通话记录访问：" + when (callLogAccess) {
            CallLogAccess.NOT_GRANTED -> "未测试，请先授权"
            CallLogAccess.READABLE -> "正常，最近记录的号码字段可读取"
            CallLogAccess.EMPTY -> "正常，目前没有通话记录"
            CallLogAccess.NUMBER_UNAVAILABLE -> "可访问，但最近记录的号码为空或被隐藏"
            CallLogAccess.PROVIDER_UNAVAILABLE -> "系统未返回可读取的数据"
            CallLogAccess.DENIED -> "系统拒绝访问，请检查权限或设备管理限制"
            CallLogAccess.FAILED -> "读取失败，可重试并检查系统限制"
        })
        append("\n此检测不证明来电广播、息屏监听或推送通道正常，请再做一次真实未接来电测试。")
    }
}

/** A read-only probe: never sends, changes or stores a call record or its number. */
object CallAccessDiagnostics {
    fun check(context: Context): CallAccessResult {
        val phone = context.checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        val calls = context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED
        return check(phone, calls) {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER),
                null, null, "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                if (!cursor.moveToFirst()) CallLogAccess.EMPTY
                else {
                    val number = cursor.getString(cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER))?.trim().orEmpty()
                    if (number.isBlank() || number in setOf("-1", "-2", "-3")) CallLogAccess.NUMBER_UNAVAILABLE
                    else CallLogAccess.READABLE
                }
            } ?: CallLogAccess.PROVIDER_UNAVAILABLE
        }
    }

    internal fun check(phone: Boolean, calls: Boolean, probe: () -> CallLogAccess): CallAccessResult {
        val access = if (!calls) CallLogAccess.NOT_GRANTED else try {
            probe()
        } catch (_: SecurityException) {
            CallLogAccess.DENIED
        } catch (_: Exception) {
            CallLogAccess.FAILED
        }
        return CallAccessResult(phone, calls, access)
    }
}
