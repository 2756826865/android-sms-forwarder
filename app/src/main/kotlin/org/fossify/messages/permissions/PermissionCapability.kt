package org.fossify.messages.permissions

/**
 * 业务能力抽象枚举，业务层仅面向能力申请，不直接拼接底层权限字符串
 */
enum class PermissionCapability(val displayName: String) {
    RECEIVE_SMS("接收短信"),
    READ_SMS("读取短信"),
    SEND_SMS("发送短信"),
    READ_CONTACTS("读取联系人"),
    READ_SIM_INFO("获取电话状态与卡槽信息"),
    MAKE_CALL("拨打电话"),
    SHOW_NOTIFICATIONS("显示通知")
}

data class PermissionRationale(
    val title: String,
    val description: String
)

sealed interface PermissionResult {
    data object Granted : PermissionResult

    data class PartiallyGranted(
        val granted: List<String>,
        val denied: List<String>
    ) : PermissionResult

    data class Denied(
        val denied: List<String>,
        val permanentlyDenied: Boolean
    ) : PermissionResult

    data class Unsupported(
        val reason: String
    ) : PermissionResult
}
