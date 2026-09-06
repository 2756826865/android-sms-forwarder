package org.fossify.messages.forwarding.repository

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.fossify.messages.forwarding.ForwardingRule
import org.fossify.messages.forwarding.ForwardingRulesConfig
import java.util.UUID

class RuleRepository private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val config = ForwardingRulesConfig(appContext)

    private val _rulesFlow = MutableStateFlow<List<ForwardingRule>>(emptyList())
    val rulesFlow: StateFlow<List<ForwardingRule>> = _rulesFlow.asStateFlow()

    private val lock = Any()

    init {
        refresh()
    }

    fun refresh() {
        synchronized(lock) {
            _rulesFlow.value = config.rules
        }
    }

    fun getRules(): List<ForwardingRule> = synchronized(lock) {
        config.rules
    }

    fun getRuleById(id: String): ForwardingRule? = synchronized(lock) {
        config.rules.firstOrNull { it.id == id }
    }

    fun saveRules(rules: List<ForwardingRule>) = synchronized(lock) {
        config.rules = rules
        _rulesFlow.value = rules
    }

    fun saveRule(rule: ForwardingRule) = synchronized(lock) {
        val current = config.rules.toMutableList()
        val index = current.indexOfFirst { it.id == rule.id }
        if (index >= 0) {
            current[index] = rule
        } else {
            current.add(rule)
        }
        saveRules(current)
    }

    fun deleteRule(id: String): Boolean = synchronized(lock) {
        val current = config.rules.toMutableList()
        val removed = current.removeAll { it.id == id }
        if (removed) {
            saveRules(current)
        }
        removed
    }

    fun duplicateRule(id: String): ForwardingRule? = synchronized(lock) {
        val target = getRuleById(id) ?: return null
        val duplicated = target.copy(
            id = UUID.randomUUID().toString(),
            name = "${target.name} (副本)",
            enabled = false
        )
        val current = config.rules.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index >= 0) {
            current.add(index + 1, duplicated)
        } else {
            current.add(duplicated)
        }
        saveRules(current)
        duplicated
    }

    fun moveRule(id: String, direction: Int): Boolean = synchronized(lock) {
        val current = config.rules.toMutableList()
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return false
        val newIndex = index + direction
        if (newIndex !in 0 until current.size) return false
        val item = current.removeAt(index)
        current.add(newIndex, item)
        saveRules(current)
        true
    }

    fun isRulesEnabled(): Boolean = config.enabled

    fun setRulesEnabled(enabled: Boolean) = synchronized(lock) {
        config.enabled = enabled
    }

    fun getScope(): Int = config.scope

    fun setScope(scope: Int) = synchronized(lock) {
        config.scope = scope
    }

    fun getLastDecision(): String = config.lastDecision

    fun setLastDecision(decision: String) = synchronized(lock) {
        config.lastDecision = decision
    }

    companion object {
        @Volatile
        private var instance: RuleRepository? = null

        fun getInstance(context: Context): RuleRepository =
            instance ?: synchronized(this) {
                instance ?: RuleRepository(context).also { instance = it }
            }
    }
}
