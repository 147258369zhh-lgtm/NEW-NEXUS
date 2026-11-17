package io.github.mangi.eta.agent.capability

import android.content.Context
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.agent.control.EtaControlCapabilityInfo
import io.github.mangi.eta.agent.control.EtaControlDanger
import io.github.mangi.eta.agent.permission.AgentPermissionPolicy
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.NexusRunMapper
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.agent.workflow.GoalPlanNotepadStore
import io.github.zhh_lgtm.nexus.application.api.CapabilityAuthorization
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor
import io.github.zhh_lgtm.nexus.kernel.api.RunMode
import io.github.zhh_lgtm.nexus.kernel.api.RunSource
import org.json.JSONObject

/** Mandatory execution-side counterpart to capability activation. */
internal object NexusExecutionGate {
    suspend fun authorize(
        context: Context,
        request: AgentRuntimeWire.RunRequest,
        toolName: String,
        arguments: JSONObject,
        controller: AgentRunController,
        grantExpiresAt: Long,
        approvalTimeoutMillis: Long = 120_000L,
        inputSchema: JSONObject? = null,
        toolDescriptor: ToolDescriptor? = null,
        toolHostId: io.github.zhh_lgtm.nexus.kernel.api.HostId? = null,
        workflowScopeId: String? = null,
    ): CapabilityAuthorization {
        controller.throwIfCancelled()
        if (!toolName.matches(Regex("[A-Za-z0-9][A-Za-z0-9_./:-]{0,127}"))) {
            return CapabilityAuthorization.Denied("INVALID_TOOL_ID", "工具标识无效")
        }
        if (System.currentTimeMillis() >= grantExpiresAt) {
            return CapabilityAuthorization.Denied("GRANT_EXPIRED", "本次任务授权已过期")
        }
        if (!request.toolPolicy.isAllowed(toolName) ||
            controller.approvalPolicy?.toolPolicy?.isAllowed(toolName) == false ||
            !io.github.mangi.eta.agent.runtime.AgentBoundaryStore.load().isAllowed(toolName)) {
            return CapabilityAuthorization.Denied("TOOL_NOT_ALLOWED_BY_RUNTIME_POLICY", "工具不在本次运行权限范围内")
        }
        val etaControlAction = if (toolName == "eta_control") arguments.optString("action").lowercase() else ""
        val delegatedEtaControl = etaControlAction in setOf("execute", "undo")
        val directory = NexusCapabilityDirectory.get(context)
        val nativeDescriptor = directory.describe(toolName)
        // Plan admission and effect downgrades resolve from the local catalog or classifier only.
        // A host, plugin or remote peer labelling a write as READ must not widen the boundary.
        val localDescriptor = (nativeDescriptor ?: externalDescriptor(toolName)).let { local ->
            if (toolName == "eta_control" && etaControlAction in ETA_CONTROL_READ_ACTIONS) {
                local.copy(effectClass = EffectClass.READ)
            } else if (NexusToolEffectClassifier.isProvenReadOnlyCall(toolName, arguments)) {
                local.copy(effectClass = EffectClass.READ)
            } else local
        }
        val descriptor = toolDescriptor ?: localDescriptor
        require(descriptor.id == toolName) { "TOOL_DESCRIPTOR_MISMATCH" }
        val effectiveDescriptor = when {
            toolName == "eta_control" && etaControlAction in ETA_CONTROL_READ_ACTIONS ->
                descriptor.copy(effectClass = EffectClass.READ)
            nativeDescriptor != null -> descriptor.copy(effectClass = localDescriptor.effectClass)
            else -> descriptor
        }
        val requestIdentity = NexusRunMapper.fromLegacy(request).request
        val requestMode = requestIdentity.mode
        val executingMode = controller.executionIdentity?.mode
        val goalScopedRun = requestMode == RunMode.GOAL || executingMode == RunMode.GOAL ||
            (controller.executionIdentity?.source ?: requestIdentity.source) == RunSource.GOAL
        // A committed terminal goal stops this run's effectful work before any approval or side
        // effect. The in-process latch is the fast path; the persisted goal remains authoritative.
        val runGoalId = controller.executionIdentity?.goalId
        val pausedGoalId = runGoalId ?: if (goalScopedRun && workflowScopeId != null) {
            runCatching { GoalPlanNotepadStore.get(context).executionSnapshot(workflowScopeId).goal?.goalId }.getOrNull()
        } else null
        val workflowTerminal = controller.isWorkflowTerminal || (workflowScopeId != null && runGoalId != null &&
            runCatching { GoalPlanNotepadStore.get(context).isTerminalGoal(workflowScopeId, runGoalId) }
                .getOrDefault(false))
        if (workflowTerminal && !workflowTerminalAllows(toolName, arguments, localDescriptor, effectiveDescriptor)) {
            return CapabilityAuthorization.Denied(
                "WORKFLOW_TERMINAL_SIDE_EFFECT_DENIED",
                "目标已进入终态，本 Run 不再执行新的写入或副作用；只读操作仍可继续",
            )
        }
        fun pausedDenial(): CapabilityAuthorization.Denied? {
            if (workflowScopeId == null || pausedGoalId == null) return null
            val paused = runCatching {
                GoalPlanNotepadStore.get(context).isPausedGoal(workflowScopeId, pausedGoalId)
            }.getOrDefault(false)
            if (!paused || workflowPausedAllows(toolName, arguments, localDescriptor, effectiveDescriptor)) return null
            return CapabilityAuthorization.Denied(
                "WORKFLOW_PAUSED_SIDE_EFFECT_DENIED",
                "目标已暂停，本 Run 不再执行新的写入或副作用；只读查询、计划修订和本机确认继续仍可使用",
            )
        }
        pausedDenial()?.let { return it }
        if (!planModeAllows(requestMode, toolName, localDescriptor, arguments) ||
            !planModeAllows(executingMode, toolName, localDescriptor, arguments)
        ) {
            return CapabilityAuthorization.Denied(
                "PLAN_MODE_SIDE_EFFECT_DENIED",
                "计划模式只允许只读调研和计划、目标、记事本草稿；本次工具未执行",
            )
        }
        val highRisk = effectiveDescriptor.effectClass == EffectClass.HIGH_RISK
        if (highRisk && request.handoff?.source == "a2a") {
            return CapabilityAuthorization.Denied("REMOTE_HIGH_RISK_DENIED", "远端 Agent 不能触发本机高风险能力")
        }
        if (request.handoff?.source == "a2a" &&
            !io.github.mangi.eta.fork.a2a.A2AAccessRuntimeGuard.isAllowed(context, request.runId, toolName)) {
            return CapabilityAuthorization.Denied("A2A_CURRENT_GRANT_DENIED", "当前入站授权已撤销或任务已停止")
        }
        val schema = directory.schema(toolName) ?: inputSchema
            ?: return CapabilityAuthorization.Denied("UNKNOWN_TOOL", "工具未在本次运行中登记")
        try {
            io.github.mangi.eta.agent.control.EtaControlSchema.validate(schema, arguments)
        } catch (invalid: io.github.mangi.eta.agent.control.EtaControlException) {
            return CapabilityAuthorization.Denied("INVALID_TOOL_ARGUMENT", invalid.message)
        }
        val permissionMode = AgentPermissionPolicy.effectiveMode(controller)
        val capabilities = AgentToolCapabilities.capture(context)
        val rootAvailable = capabilities.rootAvailable
        val rootIdentityTool = toolName in ROOT_IDENTITY_TOOLS
        val explicitUserIdentity = arguments.optString("identity").equals("user", ignoreCase = true)
        val actionScope = io.github.mangi.eta.agent.approval.ActionScope(
            toolName, toolHostId?.value ?: "native-android",
            io.github.mangi.eta.agent.control.SettingsControlSnapshots.hash(schema), effectiveDescriptor.effectClass,
            controller.executionIdentity?.projectId ?: "conversation:${controller.conversationId ?: workflowScopeId.orEmpty()}",
            io.github.mangi.eta.agent.capability.A2AToolCallIdentity.canonicalArgumentsDigest(arguments),
            // External connections without a trusted account/version identity never learn reuse.
            (toolHostId == null || toolHostId.value == "native-android") && verifiedLearningScope(toolName, arguments),
            targetLabel = if (toolName in LEARNABLE_EXACT_FILE_TOOLS) listOf("environment", "identity", "path", "source", "destination")
                .mapNotNull { key -> arguments.optString(key).takeIf(String::isNotBlank)?.let { "$key=$it" } }
                .joinToString(" · ").take(1_000) else "",
        )
        val requiresApproval = !delegatedEtaControl && AgentPermissionPolicy.requiresManualApproval(
            mode = permissionMode,
            effect = effectiveDescriptor.effectClass,
            toolName = toolName,
            arguments = arguments,
            protectedRootRequest = rootAvailable && rootIdentityTool && !explicitUserIdentity,
        )
        val approved = if (requiresApproval) {
            val details = JSONObject().put("tool", toolName).put("host_id", toolHostId?.value ?: descriptor.hostKind.name)
                .put("effect", descriptor.effectClass.name).put("expires_at", grantExpiresAt)
                .put("approval_mode", permissionMode.wireValue)
                .put("run_id", controller.executionIdentity?.runId?.value ?: request.runId).put("parameters", arguments)
            EtaAppControlConfirmation.awaitApproval(
                info = EtaControlCapabilityInfo(
                    id = "runtime_tool:$toolName",
                    title = if (highRisk) "高风险操作需要你的确认" else "此操作需要你的确认",
                    category = "agent_runtime",
                    summary = descriptor.shortDescription,
                    danger = EtaControlDanger.CONFIRM,
                    parameters = details,
                    canRead = false,
                ),
                parameters = details,
                timeoutMillis = approvalTimeoutMillis,
                effect = effectiveDescriptor.effectClass,
                // Bind the owner approval to the real call arguments. The details envelope also
                // carries run, host and expiry fields that change between pause and resume.
                argumentsDigest =
                    io.github.mangi.eta.agent.capability.A2AToolCallIdentity.canonicalArgumentsDigest(arguments),
                controller = controller,
                actionScope = actionScope,
            )
        } else {
            controller.approvalSession?.store?.auditDecision(actionScope,
                controller.executionIdentity?.runId?.value ?: request.runId, "allow",
                if (permissionMode == io.github.mangi.eta.data.model.AgentPermissionMode.FULL_ACCESS) "run_full_access" else "within_run_scope")
            true
        }
        controller.throwIfCancelled()
        // The goal may have been paused while a local approval or tool preparation was pending.
        pausedDenial()?.let { return it }
        val latestMode = AgentPermissionPolicy.effectiveMode(controller)
        if (latestMode != permissionMode && AgentPermissionPolicy.requiresManualApproval(
                latestMode, effectiveDescriptor.effectClass, toolName, arguments,
                rootAvailable && rootIdentityTool && !explicitUserIdentity,
            ) && !delegatedEtaControl) {
            return authorize(context, request, toolName, arguments, controller, grantExpiresAt,
                approvalTimeoutMillis, inputSchema, toolDescriptor, toolHostId, workflowScopeId)
        }
        if (!request.toolPolicy.isAllowed(toolName) ||
            controller.approvalPolicy?.toolPolicy?.isAllowed(toolName) == false ||
            !io.github.mangi.eta.agent.runtime.AgentBoundaryStore.load().isAllowed(toolName)) {
            return CapabilityAuthorization.Denied("TOOL_NOT_ALLOWED_BY_RUNTIME_POLICY", "本次运行的工具授权已撤销")
        }
        if (!approved) {
            return CapabilityAuthorization.Denied("LOCAL_CONFIRMATION_DENIED", "用户未批准高风险工具")
        }
        val now = System.currentTimeMillis()
        return NexusCapabilityPolicyAdapter.authorize(
            descriptor = effectiveDescriptor,
            capabilities = AgentToolCapabilities.capture(context),
            taskId = controller.executionIdentity?.runId?.value ?: NexusRunMapper.canonicalRunId(request.runId).value,
            highRiskApproved = approved || delegatedEtaControl,
            now = now,
            validUntil = grantExpiresAt,
            allowedTools = setOf(toolName),
            allowedEffects = setOf(effectiveDescriptor.effectClass),
            resolvedHostId = toolHostId,
        )
    }

    private fun externalDescriptor(toolName: String): ToolDescriptor {
        val effect = NexusToolEffectClassifier.classify(toolName)
        return ToolDescriptor(
            id = toolName,
            namespace = toolName.substringBefore('_', "external"),
            shortDescription = "外部能力 $toolName",
            modelDescription = "外部能力 $toolName",
            inputSchemaRef = "nexus-tool://$toolName/input",
            effectClass = effect,
            replayPolicy = NexusToolEffectClassifier.replayPolicy(effect),
            timeoutMs = 120_000,
            hostKind = if (toolName.startsWith("mcp_")) HostKind.MCP else HostKind.NATIVE_ANDROID,
        )
    }

    /**
     * Plan mode reads, and the three plan-management drafts, are the only effects it admits.
     * [localDescriptor] must come from the local catalog or classifier; descriptors reported by
     * external hosts are never an input to this decision.
     */
    internal fun planModeAllows(mode: RunMode?, toolName: String, localDescriptor: ToolDescriptor?,
        arguments: JSONObject? = null): Boolean {
        if (mode != RunMode.PLAN || localDescriptor?.effectClass == EffectClass.READ) return true
        if (toolName !in PLAN_MODE_CONTROL_TOOLS) return false
        if (toolName != "goal_manage") return true
        if (arguments == null) return true // compatibility for catalogue-only admission
        return when (arguments.optString("action", "status")) {
            "status", "revision", "create", "revise" -> true
            "transition" -> arguments.optString("transition") in setOf("start_planning", "pause", "pause_for_budget", "wait_for_user", "fail", "cancel")
            else -> false
        }
    }

    private fun workflowTerminalAllows(
        toolName: String,
        arguments: JSONObject,
        localDescriptor: ToolDescriptor,
        effectiveDescriptor: ToolDescriptor,
    ): Boolean {
        if (localDescriptor.effectClass == EffectClass.READ &&
            effectiveDescriptor.effectClass == EffectClass.READ
        ) return true
        if (toolName !in PLAN_MODE_CONTROL_TOOLS) return false
        val action = arguments.optString("action").trim().lowercase()
        return action.isEmpty() || action in TERMINAL_READ_ACTIONS
    }

    private fun workflowPausedAllows(
        toolName: String,
        arguments: JSONObject,
        localDescriptor: ToolDescriptor,
        effectiveDescriptor: ToolDescriptor,
    ): Boolean {
        if (localDescriptor.effectClass == EffectClass.READ &&
            effectiveDescriptor.effectClass == EffectClass.READ
        ) return true
        if (toolName !in PLAN_MODE_CONTROL_TOOLS) return false
        val action = arguments.optString("action").trim().lowercase()
        return when (toolName) {
            "goal_manage" -> when (action) {
                "status", "revision", "revise", "pause", "cancel", "increase_budget" -> true
                "transition" -> arguments.optString("transition").trim().lowercase() in setOf("resume", "fail", "cancel")
                else -> false
            }
            "plan_manage" -> action in setOf("status", "revision", "replace")
            "notepad_manage" -> action in setOf("read", "revision")
            else -> false
        }
    }

    private val PLAN_MODE_CONTROL_TOOLS = setOf("goal_manage", "plan_manage", "notepad_manage")
    private val TERMINAL_READ_ACTIONS = setOf("status", "read", "revision")
    private val ROOT_IDENTITY_TOOLS = setOf(
        "terminal", "terminal_job", "terminal_daemon",
        "read_file", "write_file", "edit_file", "copy_file", "move_file", "delete_file",
        "make_directory", "list_directory", "stat_file", "glob_files", "grep_files",
    )
    private val ETA_CONTROL_READ_ACTIONS = setOf("list", "search", "get", "audit")
    private val LEARNABLE_EXACT_FILE_TOOLS = setOf("delete_file", "write_file", "edit_file", "copy_file", "move_file", "make_directory")
    private fun verifiedLearningScope(tool: String, arguments: JSONObject): Boolean {
        if (tool !in LEARNABLE_EXACT_FILE_TOOLS || arguments.optString("environment") != "android" ||
            arguments.optString("identity") !in setOf("user", "root")) return false
        val paths = if (tool in setOf("copy_file", "move_file")) listOf("source", "destination") else listOf("path")
        return paths.all { key ->
            val path = arguments.optString(key)
            path.startsWith("/") && path != "/" && path.split('/').none { it == ".." }
        }
    }

}
