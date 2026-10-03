package org.fossify.messages.forwarding

/** Read-only configuration checks. Never include credentials or message content. */
object ForwardingConfigAudit {
    fun inspect(instances: List<ForwardingChannelInstance>, rules: List<ForwardingRule>): List<String> {
        val issues = mutableListOf<String>()
        val byId = instances.associateBy { it.id }
        instances.forEachIndexed { index, item ->
            if (item.enabled && item.channelType != ForwardingChannels.CHANNEL_GROUP && !item.hasDispatchConfiguration())
                issues.add("通道 ${index + 1}：缺少必要配置")
            if (item.channelType == ForwardingChannels.CHANNEL_GROUP) {
                val members = runCatching { org.json.JSONObject(item.configJson).optJSONArray("members") }.getOrNull()
                if (members == null || members.length() == 0) issues.add("通道 ${index + 1}：通道组没有成员")
                else for (i in 0 until members.length()) {
                    val id = members.optString(i)
                    if (id !in byId && id !in ForwardingChannels.allRuleChannels) issues.add("通道 ${index + 1}：成员目标不存在")
                }
            }
        }
        rules.filter { it.enabled }.forEachIndexed { index, rule ->
            rule.actions.filter { it.enabled }.forEach { action ->
                if (action.targetInstanceId.isNotBlank()) {
                    val target = byId[action.targetInstanceId]
                    if (target == null) issues.add("启用规则 ${index + 1}：目标实例已删除")
                    else if (!target.enabled) issues.add("启用规则 ${index + 1}：目标实例已停用")
                }
            }
            if (rule.actions.isEmpty() && rule.targetInstanceIds.isEmpty() && rule.channels.isEmpty())
                issues.add("启用规则 ${index + 1}：没有投递目标")
        }
        fun cyclic(id: String, path: Set<String>): Boolean {
            if (id in path) return true
            val item = byId[id] ?: return false
            if (item.channelType != ForwardingChannels.CHANNEL_GROUP) return false
            if (path.size >= 16) return true
            val members = runCatching { org.json.JSONObject(item.configJson).optJSONArray("members") }.getOrNull() ?: return false
            return (0 until members.length()).any { cyclic(members.optString(it), path + id) }
        }
        instances.forEachIndexed { index, item -> if (cyclic(item.id, emptySet())) issues.add("通道 ${index + 1}：通道组循环或嵌套超过16层") }
        return issues.distinct()
    }
}
