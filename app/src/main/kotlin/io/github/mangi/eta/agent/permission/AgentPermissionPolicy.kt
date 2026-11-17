package io.github.mangi.eta.agent.permission

import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AgentPermissionMode
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

internal object AgentPermissionPolicy {
    fun modeFlow(): Flow<AgentPermissionMode> = SettingsDataStore.agentPermissionModeFlow()

    suspend fun mode(): AgentPermissionMode =
        runCatching { SettingsDataStore.agentPermissionMode() }.getOrDefault(AgentPermissionMode.SMART_APPROVAL)

    suspend fun setMode(mode: AgentPermissionMode) = SettingsDataStore.setAgentPermissionMode(mode)

    suspend fun effectiveMode(controller: io.github.mangi.eta.agent.runtime.AgentRunController? =
        io.github.mangi.eta.agent.runtime.RuntimeInvocationScope.controller): AgentPermissionMode {
        val state = try { SettingsDataStore.agentPermissionState() } catch (failure: Exception) {
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            io.github.mangi.eta.data.model.AgentPermissionState(AgentPermissionMode.MANUAL_APPROVAL,
                Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE)
        }
        return controller?.approvalPolicy?.effectiveMode(state) ?: state.mode
    }

    /** True routes to the selected approver: an independent reviewer or the real user. */
    fun requiresManualApproval(
        mode: AgentPermissionMode,
        effect: EffectClass,
        toolName: String,
        arguments: JSONObject,
        protectedRootRequest: Boolean = false,
    ): Boolean {
        if (effect == EffectClass.READ) return false
        return when (mode) {
            AgentPermissionMode.FULL_ACCESS -> false
            // Root identity lets a reversible file write, copy or move reach paths the user
            // cannot otherwise touch, so it is reviewed in both approval tiers.
            AgentPermissionMode.SMART_APPROVAL,
            AgentPermissionMode.MANUAL_APPROVAL -> protectedRootRequest || effect in APPROVAL_EFFECTS ||
                isFinancialRequest(toolName, arguments)
        }
    }

    fun requiresManualAppControl(
        mode: AgentPermissionMode,
        isWrite: Boolean,
        isConfirm: Boolean,
        capabilityId: String,
        arguments: JSONObject,
    ): Boolean {
        if (!isWrite && !isConfirm) return false
        return when (mode) {
            AgentPermissionMode.FULL_ACCESS -> false
            AgentPermissionMode.SMART_APPROVAL -> isConfirm || isFinancialRequest(capabilityId, arguments)
            AgentPermissionMode.MANUAL_APPROVAL -> isConfirm || isWrite
        }
    }

    fun isFinancialRequest(toolName: String, arguments: JSONObject): Boolean {
        val haystack = (toolName + " " + arguments.toString()).lowercase()
        return FINANCIAL_MARKERS.any(haystack::contains)
    }

    fun isRootRequest(toolName: String, arguments: JSONObject): Boolean =
        "root" in toolName.lowercase() || arguments.optString("identity").equals("root", ignoreCase = true)

    fun isSecurityBoundaryRequest(toolName: String): Boolean =
        SECURITY_MARKERS.any { it in toolName.lowercase() }

    private val FINANCIAL_MARKERS = listOf(
        "payment", "purchase", "checkout", "billing", "budget", "price", "recharge",
        "transfer", "subscription", "amount", "currency", "credits",
        "支付", "付款", "购买", "下单", "收银", "充值", "余额", "转账", "预算", "费用", "金额", "订阅",
    )
    private val SECURITY_MARKERS = listOf("permission", "approval", "credential", "authentication", "security")
    private val APPROVAL_EFFECTS = setOf(
        EffectClass.DESTRUCTIVE,
        EffectClass.EXTERNAL_SIDE_EFFECT,
        EffectClass.HIGH_RISK,
    )
}
