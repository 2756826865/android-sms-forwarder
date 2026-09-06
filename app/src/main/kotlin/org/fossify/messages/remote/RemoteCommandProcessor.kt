package org.fossify.messages.remote

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.fossify.messages.forwarding.ForwardingRuleEngine
import org.fossify.messages.forwarding.ForwardingRulesConfig
import org.fossify.messages.helpers.RemoteCommandRepository
import org.fossify.messages.messaging.SimResolutionRequest
import org.fossify.messages.messaging.SimSendResolver
import org.fossify.messages.messaging.SubscriptionResolver
import org.fossify.messages.models.RemoteCommandContext
import org.fossify.messages.models.RemoteCommandSourceType
import org.fossify.messages.models.RemoteCommandType
import org.fossify.messages.permissions.PermissionCapability
import org.fossify.messages.permissions.XXPermissionGateway
import org.fossify.messages.remote.repository.RemoteSourceInstance
import org.fossify.messages.remote.repository.RemoteSourceRepository
import org.fossify.messages.remote.repository.RemoteSourceType
import java.security.MessageDigest
import java.util.Calendar

/**
 * 统一远程指令载荷信封
 */
data class RemoteCommandEnvelope(
    val sourceType: RemoteSourceType,
    val sourceInstanceId: String = "",
    val sourceMessageKey: String,
    val senderId: String,
    val senderName: String = "",
    val groupId: String = "",
    val rawContent: String,
    val isMentioned: Boolean = false,
    val receivedAt: Long = System.currentTimeMillis(),
    val subscriptionId: Int = -1,
    val messageId: Long = 0L,
    val extraMeta: Map<String, String> = emptyMap()
)

/**
 * 拦截与处理结果
 */
sealed class RemoteProcessResult {
    data class Success(val commandId: String, val target: String, val content: String, val sendMode: Int) : RemoteProcessResult()
    data class Ignored(val reason: String) : RemoteProcessResult()
    data class Rejected(val commandId: String?, val reason: String, val detail: String = "") : RemoteProcessResult()
    data class Duplicate(val existingCommandId: String) : RemoteProcessResult()
}

/**
 * 统一远程命令拦截校验与分发引擎
 */
object RemoteCommandProcessor {

    private const val MAX_COMMAND_AGE_MS = 10 * 60 * 1000L // 10分钟防滞后
    private const val MAX_SMS_CONTENT_LENGTH = 1000

    /**
     * 12步统一拦截校验并提交发信
     */
    fun process(context: Context, envelope: RemoteCommandEnvelope): RemoteProcessResult {
        val remoteRepo = RemoteSourceRepository.getInstance(context)
        val isSmsSource = envelope.sourceType == RemoteSourceType.SMS

        // 步骤 1: 来源查找与开关检查。网络来源禁止按类型回退到第一个实例。
        if (!isSmsSource && envelope.sourceInstanceId.isBlank()) {
            return RemoteProcessResult.Rejected(
                commandId = null,
                reason = "SOURCE_INSTANCE_REQUIRED",
                detail = "网络远程指令缺少来源实例标识"
            )
        }

        val instance = when {
            envelope.sourceInstanceId.isNotBlank() -> remoteRepo.getSourceById(envelope.sourceInstanceId)
            isSmsSource -> remoteRepo.getSourcesByType(RemoteSourceType.SMS).firstOrNull { it.enabled }
            else -> null
        }

        if (instance != null && instance.type != envelope.sourceType) {
            return RemoteProcessResult.Rejected(
                commandId = null,
                reason = "SOURCE_TYPE_MISMATCH",
                detail = "来源实例类型与指令声明类型不一致"
            )
        }

        // 短信指令允许从旧 RemoteSmsCommandConfig 兜底兼容
        val isSourceEnabled = instance?.enabled ?: if (isSmsSource) {
            RemoteSmsCommandConfig(context).enabled
        } else {
            false
        }

        if (!isSourceEnabled) {
            return RemoteProcessResult.Ignored("来源未启用或已关闭 [${envelope.sourceType.label}]")
        }

        // 步骤 2: 消息时效性检查（防滞后严重指令）
        val now = System.currentTimeMillis()
        if (envelope.receivedAt > 0 &&
            (envelope.receivedAt < now - MAX_COMMAND_AGE_MS || envelope.receivedAt > now + MAX_COMMAND_AGE_MS)
        ) {
            return RemoteProcessResult.Ignored("指令时间超出允许窗口（前后10分钟）")
        }

        // 步骤 3: 授权检查。开启白名单时必须配置；关闭时明确接受全部用户。
        val effectiveAuthorizedUsers = if (instance != null) {
            instance.authorizedUsers
        } else if (isSmsSource) {
            RemoteSmsCommandConfig(context).authorizedList().toSet()
        } else {
            emptySet()
        }
        val whitelistEnabled = instance?.whitelistEnabled ?: true

        if (whitelistEnabled && effectiveAuthorizedUsers.isEmpty()) {
            return RemoteProcessResult.Rejected(
                commandId = null,
                reason = "AUTHORIZED_USERS_REQUIRED",
                detail = "未配置授权用户白名单，默认拒绝所有远程指令"
            )
        }

        val isAuthorized = !whitelistEnabled || effectiveAuthorizedUsers.any { auth ->
            if (isSmsSource) {
                numbersEquivalent(auth, envelope.senderId)
            } else {
                auth.equals(envelope.senderId, ignoreCase = true)
            }
        }
        if (!isAuthorized) {
            return RemoteProcessResult.Rejected(
                commandId = null,
                reason = "NOT_AUTHORIZED_USER",
                detail = "发件人/触发方未在授权白名单中"
            )
        }

        // 步骤 4: 群聊授权与 @ 机器人检查 (空群白名单默认拒绝群聊指令)
        if (envelope.groupId.isNotBlank()) {
            val authorizedGroups = instance?.authorizedGroups ?: emptySet()
            if (whitelistEnabled && authorizedGroups.isEmpty()) {
                return RemoteProcessResult.Rejected(
                    commandId = null,
                    reason = "AUTHORIZED_GROUPS_REQUIRED",
                    detail = "未配置授权群组白名单，默认拒绝群聊远程指令"
                )
            }
            if (whitelistEnabled && !authorizedGroups.contains(envelope.groupId)) {
                return RemoteProcessResult.Rejected(
                    commandId = null,
                    reason = "NOT_AUTHORIZED_GROUP",
                    detail = "群组未在授权群列表中"
                )
            }
            if (instance?.requireMention == true && !envelope.isMentioned) {
                return RemoteProcessResult.Ignored("群消息未 @ 机器人，已忽略")
            }
        }

        // 步骤 5: 命令前缀与语法结构解析（必须从开头匹配，严禁 indexOf 逃逸）
        val customPrefix = instance?.customCommandPrefix?.ifBlank {
            if (isSmsSource) RemoteSmsCommandConfig(context).customPrefix else ""
        }.orEmpty()

        val parsedCommand = RemoteSmsCommand.parse(envelope.rawContent, customPrefix)
            ?: return RemoteProcessResult.Ignored("内容不符合远程发信命令语法规范")

        // 步骤 6: 目标号码与正文有效性
        val targetNumber = parsedCommand.targetNumber.trim()
        val content = parsedCommand.content.trim()

        if (!isValidTargetNumber(targetNumber)) {
            return RemoteProcessResult.Rejected(
                commandId = null,
                reason = "INVALID_TARGET_NUMBER",
                detail = "目标号码格式非法: $targetNumber"
            )
        }

        if (content.length > MAX_SMS_CONTENT_LENGTH) {
            return RemoteProcessResult.Rejected(
                commandId = null,
                reason = "CONTENT_TOO_LONG",
                detail = "短信内容超过最大限制长度: ${content.length} > $MAX_SMS_CONTENT_LENGTH"
            )
        }

        // 步骤 7: 免打扰时段检查
        if (instance != null && instance.quietHoursEnabled) {
            val calendar = Calendar.getInstance()
            val currentHour = calendar.get(Calendar.HOUR_OF_DAY)
            if (isTimeInQuietHours(currentHour, instance.quietHoursStart, instance.quietHoursEnd)) {
                return RemoteProcessResult.Rejected(
                    commandId = null,
                    reason = "QUIET_HOURS_BLOCKED",
                    detail = "处于夜间免打扰时段 (${instance.quietHoursStart}:00 - ${instance.quietHoursEnd}:00)"
                )
            }
        }

        // 步骤 8: 频率限制检查
        val legacySmsRateLimited = instance == null && isSmsSource && RemoteSmsCommandConfig(context).isRateLimited(envelope.senderId)
        if (legacySmsRateLimited) {
            return RemoteProcessResult.Rejected(
                commandId = null,
                reason = "RATE_LIMITED",
                detail = "已达到该远程来源的每小时或每日发送上限"
            )
        }

        // 步骤 9: 永久幂等声明与持久化
        val sendMode = parsedCommand.effectiveSendMode(instance?.defaultSimMode ?: SimSendResolver.MODE_FOLLOW_RECEIVE)
        val idempotencyMessageKey = envelope.sourceInstanceId
            .takeIf(String::isNotBlank)
            ?.let { "$it:${envelope.sourceMessageKey}" }
            ?: envelope.sourceMessageKey
        val cmdContext = RemoteCommandContext(
            sourceType = mapSourceTypeToLegacy(envelope.sourceType),
            sourceMessageKey = idempotencyMessageKey,
            commandType = RemoteCommandType.SEND_SMS,
            rawTarget = targetNumber,
            rawPayload = content,
            requestedSimMode = sendMode,
            rawRequester = envelope.senderId,
            receivedAt = envelope.receivedAt
        )

        val claimResult = runBlocking {
            RemoteCommandRepository.claimOrGetDuplicate(context, cmdContext)
        }

        if (claimResult is RemoteCommandRepository.ClaimResult.Duplicate) {
            return RemoteProcessResult.Duplicate(claimResult.existingCommandId)
        }

        val commandId = (claimResult as? RemoteCommandRepository.ClaimResult.NewCommand)?.commandId.orEmpty()

        // 步骤 10: 转发规则安全联动检查（默认不阻断远程发信，仅在显式关联时阻断）
        val rulesConfig = ForwardingRulesConfig(context)
        if (rulesConfig.affectsRemoteCommands() && rulesConfig.rules.any { it.enabled }) {
            val decision = ForwardingRuleEngine(rulesConfig.rules).evaluate(
                sender = envelope.senderId,
                body = "$targetNumber $content",
                subscriptionId = envelope.subscriptionId,
                channelCandidates = emptySet(),
                simSlotIndex = null
            )
            if (decision.matchedRules.isEmpty()) {
                if (commandId.isNotBlank()) {
                    RemoteCommandRepository.recordAuthorization(context, commandId, authorized = false, reason = "RULE_BLOCKED")
                }
                return RemoteProcessResult.Rejected(
                    commandId = commandId,
                    reason = "RULE_BLOCKED",
                    detail = "普通转发规则策略拒绝了此远程发信指令"
                )
            }
        }

        // 步骤 11: 发送卡解析与可用性检查（真实 slotIndex 查找）
        val simResult = SubscriptionResolver.resolve(
            context,
            SimResolutionRequest(
                receivedSubId = envelope.subscriptionId.takeIf { it >= 0 },
                configuredMode = sendMode,
                targetAddress = targetNumber,
                allowFallback = true
            )
        )

        if (sendMode in setOf(SubscriptionResolver.MODE_SIM1, SubscriptionResolver.MODE_SIM2) &&
            !simResult.isSuccessful && !simResult.isFallback
        ) {
            if (commandId.isNotBlank()) {
                RemoteCommandRepository.recordExecutionFailure(
                    context,
                    commandId,
                    errorClass = "SimUnavailableException",
                    errorMessage = "未找到可用的${SimSendResolver.modeLabel(sendMode)}"
                )
            }
            return RemoteProcessResult.Rejected(
                commandId = commandId,
                reason = "SIM_UNAVAILABLE",
                detail = "指定的物理卡槽未就绪或未插入对应 SIM 卡"
            )
        }

        // 步骤 12: 权限前置检查与入队执行
        val hasSmsPerm = runBlocking {
            XXPermissionGateway.check(context, PermissionCapability.SEND_SMS)
        }
        if (!hasSmsPerm) {
            if (commandId.isNotBlank()) {
                RemoteCommandRepository.recordExecutionFailure(
                    context,
                    commandId,
                    errorClass = "SecurityException",
                    errorMessage = "缺少 SEND_SMS 系统权限"
                )
            }
            return RemoteProcessResult.Rejected(
                commandId = commandId,
                reason = "PERMISSION_DENIED",
                detail = "系统未授予发送短信权限 (SEND_SMS)"
            )
        }

        // The check and reservation must be one synchronized operation. Otherwise two
        // simultaneous commands can both pass the limit before either one is recorded.
        if (instance != null && !remoteRepo.tryRecordMessageReceived(instance.id)) {
            if (commandId.isNotBlank()) {
                RemoteCommandRepository.recordAuthorization(context, commandId, authorized = false, reason = "RATE_LIMITED")
            }
            return RemoteProcessResult.Rejected(
                commandId = commandId,
                reason = "RATE_LIMITED",
                detail = "已达到该远程来源的每小时或每日发送上限"
            )
        }

        if (commandId.isNotBlank()) {
            RemoteCommandRepository.recordAuthorization(context, commandId, authorized = true, reason = "VERIFIED")
            RemoteCommandRepository.recordQueued(context, commandId)
        }

        if (isSmsSource) {
            RemoteSmsCommandConfig(context).markExecution(envelope.senderId)
        }

        val uniqueFingerprint = sha256("${envelope.sourceType.name}-$idempotencyMessageKey-$targetNumber-$content")

        RemoteSmsCommandWorker.enqueue(
            context = context,
            target = targetNumber,
            content = content,
            subId = envelope.subscriptionId,
            uniqueId = uniqueFingerprint,
            sendMode = sendMode,
            requester = envelope.extraMeta["receiptTarget"].orEmpty().ifBlank { envelope.senderId },
            source = mapSourceLabel(envelope.sourceType),
            commandId = commandId,
            sourceInstanceId = envelope.sourceInstanceId
        )

        return RemoteProcessResult.Success(
            commandId = commandId,
            target = targetNumber,
            content = content,
            sendMode = sendMode
        )
    }

    private fun isValidTargetNumber(number: String): Boolean {
        if (number.length < 3 || number.length > 25) return false
        val clean = if (number.startsWith("+")) number.substring(1) else number
        return clean.all { it.isDigit() || it == '-' || it == ' ' }
    }

    private fun isTimeInQuietHours(hour: Int, start: Int, end: Int): Boolean {
        return if (start <= end) {
            hour in start until end
        } else {
            hour >= start || hour < end
        }
    }

    private fun mapSourceTypeToLegacy(type: RemoteSourceType): String = when (type) {
        RemoteSourceType.SMS -> RemoteCommandSourceType.SMS
        RemoteSourceType.DINGTALK -> RemoteCommandSourceType.DINGTALK
        RemoteSourceType.FEISHU -> RemoteCommandSourceType.FEISHU
        RemoteSourceType.EMAIL -> RemoteCommandSourceType.EMAIL
        RemoteSourceType.TELEGRAM -> RemoteCommandSourceType.TELEGRAM
        RemoteSourceType.WEBSOCKET -> RemoteCommandSourceType.WEBSOCKET
    }

    private fun mapSourceLabel(type: RemoteSourceType): String = when (type) {
        RemoteSourceType.SMS -> SOURCE_SMS
        RemoteSourceType.DINGTALK -> SOURCE_DINGTALK
        RemoteSourceType.FEISHU -> SOURCE_FEISHU
        RemoteSourceType.EMAIL -> SOURCE_EMAIL
        RemoteSourceType.TELEGRAM -> SOURCE_TELEGRAM
        RemoteSourceType.WEBSOCKET -> SOURCE_WEBSOCKET
    }

    private fun normalizeNumber(value: String): String = value.filter(Char::isDigit).takeLast(11)

    private fun numbersEquivalent(a: String, b: String): Boolean {
        val left = normalizeNumber(a)
        val right = normalizeNumber(b)
        return left.isNotEmpty() && (left == right || left.endsWith(right) || right.endsWith(left))
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
