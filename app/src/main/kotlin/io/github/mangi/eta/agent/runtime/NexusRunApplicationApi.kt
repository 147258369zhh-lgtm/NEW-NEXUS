package io.github.mangi.eta.agent.runtime

import io.github.zhh_lgtm.nexus.application.api.*
import io.github.zhh_lgtm.nexus.kernel.api.*
import java.security.MessageDigest

/**
 * Service-side admission boundary. The existing authenticated IPC transports the executable
 * envelope; the Application API owns its canonical validation and durable admission. Credentials
 * stay in that private envelope and never enter the public metadata contract or execution store.
 */
internal class NexusRunApplicationApi(
    private val store: ExecutionStore,
    private val runs: RuntimeRunRegistry,
    private val cancelRuntime: (String) -> Unit,
) : RunApplicationApi {
    private val submissionLock = Any()
    private data class Prepared(
        val legacy: AgentRuntimeWire.RunRequest,
        val canonical: RunRequest,
        val resolveContext: (AgentRuntimeWire.RunRequest) -> NexusRunContext,
        val create: (NexusExecutionRecorder) -> RuntimeRunRegistry.Run,
        var result: RuntimeRunRegistry.Admission? = null,
    )
    private val prepared = mutableMapOf<RunId, Prepared>()
    private val childControllers = mutableMapOf<RunId,AgentRunController>()
    private val recorders = mutableMapOf<RunId, NexusExecutionRecorder>()
    private val budgetPropagationLock = Any()
    @Volatile private var restorePending: () -> Boolean = { false }
    fun configureBackupGuard(context: android.content.Context) {
        val app = context.applicationContext
        restorePending = { io.github.mangi.eta.data.repository.EtaFullBackupArchive.hasPendingRestore(app) }
    }
    private var modelPriceResolver: (io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig) -> AgentTokenPrice? = { null }
    private val modelBudgets by lazy { (store as? ModelBudgetStore)?.let { ledger ->
        RunModelBudgetCoordinator(ledger, { modelPriceResolver(it) }) { runId, failure ->
            familyRecorders(runId).forEach { it.abort(failure) }
        }
    } }

    fun configureModelPrices(resolver: (io.github.mangi.eta.agent.model.AgentModelClient.ModelConfig) -> AgentTokenPrice?) = synchronized(submissionLock) {
        check(recorders.isEmpty()) { "RUNS_ALREADY_ACTIVE" }
        modelPriceResolver = resolver
    }

    private fun budgetRestricted(runId: RunId, changeId: String, limit: RunBudget, goalId: GoalId) =
        synchronized(budgetPropagationLock) { propagateBudgetRestriction(runId, changeId, limit, goalId) }

    private fun budgetIncreased(approval: io.github.mangi.eta.agent.workflow.ApprovedGoalBudgetIncrease) = synchronized(budgetPropagationLock) {
        val family = familyRecorders(approval.approvalRunId)
        try { family.forEach { it.increaseBudget(approval) } }
        catch (failure: Exception) {
            val abort = IllegalStateException("BUDGET_INCREASE_UNRECORDED", failure)
            family.forEach { it.abort(abort) }
            throw abort
        }
    }

    private fun resourceBudgetExtended(approval: ApprovedRunBudgetExtension) = synchronized(budgetPropagationLock) {
        val family = familyRecorders(approval.approvalRunId).sortedBy { it.request.runId != approval.targetRunId }
        try { family.forEach { it.extendResourceBudget(approval) } }
        catch (failure: Exception) {
            val abort = IllegalStateException("BUDGET_EXTENSION_UNRECORDED", failure)
            family.forEach { it.abort(abort) }
            throw abort
        }
    }

    private fun propagateBudgetRestriction(runId: RunId, changeId: String, limit: RunBudget, goalId: GoalId) {
        val family = familyRecorders(runId)
        try {
            family.forEach { it.restrictBudget("${runId.value}:$changeId", limit, goalId) }
        } catch (failure: Exception) {
            val abort = IllegalStateException("BUDGET_INHERITANCE_UNRECORDED", failure)
            family.forEach { it.abort(abort) }
            throw abort
        }
    }

    private fun familyRecorders(runId: RunId): List<NexusExecutionRecorder> {
        val active = synchronized(submissionLock) { recorders.toMap() }
        var root = active[runId] ?: return emptyList()
        val visited = mutableSetOf<RunId>()
        while (visited.add(root.request.runId)) {
            root = root.request.parentRunId?.let(active::get) ?: break
        }
        return active.values.filter { member ->
            var current: NexusExecutionRecorder? = member
            val seen = mutableSetOf<RunId>()
            var belongs = false
            while (current != null && seen.add(current.request.runId)) {
                if (current.request.runId == root.request.runId) { belongs = true; break }
                current = current.request.parentRunId?.let(active::get)
            }
            belongs
        }
    }

    /** Child loops use the same durable admission owner and cannot choose a different host/source. */
    fun openChild(parentId: RunId, operationIdentity: String, sessionIdentity: String,
        budget: RunBudget, controller: AgentRunController): NexusExecutionRecorder = synchronized(budgetPropagationLock) {
        synchronized(submissionLock) {
        controller.throwIfCancelled()
        val parent=store.find(parentId) ?: error("PARENT_RUN_NOT_FOUND")
        check(parent.state == RunState.RUNNING) { "PARENT_RUN_NOT_RUNNING" }
        val now=System.currentTimeMillis()
        val remaining=parent.request.budget.hardDurationMs?.let { it-(now-parent.createdAt).coerceAtLeast(0) }
        check(remaining == null || remaining > 0) { "PARENT_BUDGET_EXPIRED" }
        val parentLimits = parent.request.budget.intersect(RunBudget(hardDurationMs = remaining))
        val limited = budget.intersect(parentLimits)
        val id=RunId(identity("child:${parentId.value}:$operationIdentity"))
        check(store.find(id) == null && id !in childControllers) { "CHILD_RUN_ALREADY_EXISTS" }
        // Child tool grants resolve against the bound identity, so a plan-mode parent must hand
        // plan mode down instead of recording a weaker TASK run for the child.
        val childMode = if (parent.request.mode == RunMode.PLAN) RunMode.PLAN else RunMode.TASK
        val request=RunRequest(id,RunSource.SUBAGENT,childMode,parent.request.hostId,
            sessionId=SessionId(identity("child-session:${parent.request.sessionId?.value}:$sessionIdentity")),
            projectId=parent.request.projectId,parentRunId=parentId,taskId=parent.request.taskId,
            goalId=parent.request.goalId,budget=limited)
        val recorder=NexusExecutionRecorder(store,request,onTerminal={ synchronized(submissionLock) {
            childControllers.remove(id); recorders.remove(id); Unit
        } }, onBudgetRestricted = ::budgetRestricted, onBudgetIncreased = ::budgetIncreased,
            onResourceBudgetExtended = ::resourceBudgetExtended)
        recorder.bindController(controller)
        modelBudgets?.bind(controller, id)
        childControllers[id]=controller
        recorders[id]=recorder
        recorder
        }
    }

    /** Called only after the service has authenticated and decoded its IPC envelope. */
    fun admit(
        request: AgentRuntimeWire.RunRequest,
        listener: RunEventListener = RunEventListener {},
        resolveContext: (AgentRuntimeWire.RunRequest) -> NexusRunContext = { NexusRunContext() },
        create: (NexusExecutionRecorder) -> RuntimeRunRegistry.Run,
    ): RuntimeRunRegistry.Admission = synchronized(submissionLock) {
        val canonical = NexusRunMapper.fromLegacy(request).request
        check(canonical.runId !in prepared) { "REENTRANT_RUN_SUBMISSION" }
        val entry = Prepared(request, canonical, resolveContext, create)
        prepared[canonical.runId] = entry
        try {
            when (val admission = submit(canonical, listener)) {
                is RunAdmission.Accepted -> checkNotNull(entry.result)
                is RunAdmission.Rejected -> RuntimeRunRegistry.Admission.Rejected(admission.code, admission.message)
            }
        } finally { prepared.remove(canonical.runId) }
    }

    override fun submit(request: RunRequest, listener: RunEventListener): RunAdmission = synchronized(submissionLock) {
        val input = prepared[request.runId]
            ?: return RunAdmission.Rejected("RUN_INPUT_REQUIRED", "运行请求缺少经过验证的执行输入")
        if (io.github.mangi.eta.data.repository.EtaBackupOperations.dataOperationActive) {
            return RunAdmission.Rejected("BACKUP_IN_PROGRESS", "备份或恢复正在处理，请完成后再运行任务")
        }
        if (restorePending()) return RunAdmission.Rejected("DATA_RESTORE_PENDING", "恢复已暂存，请重启完成恢复后再运行任务")
        if (input.canonical != request || request.hostId != HostId("native-android")) {
            return RunAdmission.Rejected("RUN_METADATA_MISMATCH", "运行标识、来源或执行主机不匹配")
        }
        val persisted = store.find(request.runId)
        if (persisted?.state in terminalStates) {
            return RunAdmission.Rejected("RUN_ALREADY_TERMINAL", "任务已有完成记录，请读取已有结果，不能重复执行")
        }
        if (persisted != null && runs.get(input.legacy.runId) == null) {
            return RunAdmission.Rejected("RUN_RECOVERY_REQUIRED", "任务保留有未完成记录，请先核对检查点和已发生的操作")
        }
        val admission = runs.admit(input.legacy) {
            val resolved = NexusRunMapper.fromLegacy(input.legacy, context = input.resolveContext(input.legacy)).request
            val recorder = NexusExecutionRecorder(store, resolved, listener = listener,
                onTerminal = { synchronized(submissionLock) { recorders.remove(resolved.runId); Unit } },
                onBudgetRestricted = ::budgetRestricted, onBudgetIncreased = ::budgetIncreased,
                onResourceBudgetExtended = ::resourceBudgetExtended)
            input.create(recorder).also {
                recorders[resolved.runId] = recorder
                modelBudgets?.bind(it.session.controller, resolved.runId)
            }
        }
        input.result = admission
        when (admission) {
            is RuntimeRunRegistry.Admission.Rejected -> RunAdmission.Rejected(admission.code, admission.message)
            else -> RunAdmission.Accepted(checkNotNull(store.find(request.runId)).request)
        }
    }

    override fun cancel(runId: RunId, reason: String) {
        val child=synchronized(submissionLock) { childControllers[runId] }
        if(child != null) { child.cancel(); return }
        // The registry still owns live resources; durable events remain owned by ExecutionStore.
        val run = runs.all().firstOrNull { NexusRunMapper.fromLegacy(it.request).request.runId == runId } ?: return
        cancelRuntime(run.runId)
    }

    companion object {
        private val terminalStates = setOf(RunState.SUCCEEDED, RunState.FAILED, RunState.CANCELLED, RunState.INTERRUPTED)
        private fun identity(value: String)="run-"+MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
