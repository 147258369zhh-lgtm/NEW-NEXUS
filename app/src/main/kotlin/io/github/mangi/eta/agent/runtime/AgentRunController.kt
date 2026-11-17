package io.github.mangi.eta.agent.runtime

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal class AgentRunController {
    @Volatile var approvalPolicy: io.github.mangi.eta.agent.approval.RunPolicySnapshot? = null
        private set
    @Volatile var approvalReviewer: ((org.json.JSONObject) -> io.github.mangi.eta.agent.approval.ApprovalReview)? = null
        private set
    @Volatile var approvalSession: io.github.mangi.eta.agent.approval.RunApprovalSession? = null
        private set

    @Synchronized fun bindApprovalPolicy(policy: io.github.mangi.eta.agent.approval.RunPolicySnapshot,
        reviewer: ((org.json.JSONObject) -> io.github.mangi.eta.agent.approval.ApprovalReview)? = null,
        session: io.github.mangi.eta.agent.approval.RunApprovalSession? = null) {
        check(approvalPolicy == null || approvalPolicy === policy) { "RUN_APPROVAL_POLICY_ALREADY_BOUND" }
        approvalPolicy = policy
        if (reviewer != null) approvalReviewer = reviewer
        if (session != null) approvalSession = session
    }
    @Volatile var conversationId: String? = null
        private set

    @Volatile var executionTargetId: String? = null
        private set

    @Volatile var recoverySurface: io.github.zhh_lgtm.nexus.kernel.api.RunSource? = null
        private set

    @Volatile var recoveryActorId: String? = null
        private set

    fun bindConversationId(value: String) {
        require(value.isNotBlank()) { "CONVERSATION_ID_REQUIRED" }
        check(conversationId == null || conversationId == value) { "CONVERSATION_ID_CONFLICT" }
        conversationId = value
    }

    fun bindExecutionTargetId(value: String?) {
        val normalized = value?.trim()?.takeIf(String::isNotBlank)
        check(executionTargetId == null || executionTargetId == normalized) { "EXECUTION_TARGET_ID_CONFLICT" }
        executionTargetId = normalized
    }

    fun bindRecoverySurface(value: io.github.zhh_lgtm.nexus.kernel.api.RunSource) {
        check(recoverySurface == null || recoverySurface == value) { "RECOVERY_SURFACE_CONFLICT" }
        recoverySurface = value
    }

    fun bindRecoveryActorId(value: String?) {
        val normalized = value?.trim()?.takeIf(String::isNotBlank)
        check(recoveryActorId == null || recoveryActorId == normalized) { "RECOVERY_ACTOR_CONFLICT" }
        recoveryActorId = normalized
    }

    @Volatile private var hostSnapshot: (() -> org.json.JSONArray)? = null
    fun bindHostSnapshot(snapshot: () -> org.json.JSONArray) { check(hostSnapshot == null); hostSnapshot = snapshot }
    fun hostsJson(): String = hostSnapshot?.invoke()?.toString() ?: "[]"
    @Volatile var executionIdentity: io.github.zhh_lgtm.nexus.kernel.api.RunRequest? = null
        private set

    /**
     * True for runs started on behalf of an inbound A2A peer. Nobody on this device is waiting to
     * answer them, so local prompts and approval gates must become explicit waiting states instead
     * of blocking UI flows or silently approving work.
     */
    val isRemoteA2AInbound: Boolean
        get() = (executionIdentity?.source ?: recoverySurface) ==
            io.github.zhh_lgtm.nexus.kernel.api.RunSource.A2A_INBOUND

    fun bindExecutionIdentity(request: io.github.zhh_lgtm.nexus.kernel.api.RunRequest) {
        check(executionIdentity == null) { "RUN_IDENTITY_ALREADY_BOUND" }
        executionIdentity = request
    }
    private data class LocalA2AApprovalBinding(
        val grant: AgentRuntimeWire.A2ALocalApprovalGrant,
        val expectedTaskId: String,
        val expectedContinuationCount: Int?,
    )

    @Volatile private var localA2AApproval: LocalA2AApprovalBinding? = null
    fun bindLocalA2AApproval(
        grant: AgentRuntimeWire.A2ALocalApprovalGrant?,
        expectedTaskId: String,
        expectedContinuationCount: Int?,
    ) {
        localA2AApproval = grant?.let { LocalA2AApprovalBinding(it, expectedTaskId, expectedContinuationCount) }
    }
    private val consumedLocalA2AApprovals = ConcurrentHashMap.newKeySet<String>()
    private fun consumeLocalA2AApproval(
        toolName: String,
        effect: io.github.zhh_lgtm.nexus.kernel.api.EffectClass?,
        argumentsDigest: String,
    ): Boolean {
        val binding = localA2AApproval ?: return false
        val grant = binding.grant
        val toolEffect = effect ?: return false
        if (toolEffect == io.github.zhh_lgtm.nexus.kernel.api.EffectClass.HIGH_RISK) return false
        if (!isRemoteA2AInbound || binding.expectedTaskId != grant.taskId ||
            binding.expectedContinuationCount != grant.continuationCount ||
            grant.allowedTools.size == 0 || toolName !in grant.allowedTools ||
            grant.taskId.isBlank() || grant.messageId.isBlank() || grant.continuationCount <= 0 ||
            grant.allowedToolEffects.size != grant.allowedTools.size ||
            "${toolName}=${toolEffect.name}" !in grant.allowedToolEffects ||
            grant.originalGrantFingerprint.isBlank() || grant.currentGrantFingerprint.isBlank()
        ) return false
        // The owner approved one exact call. Any other tool, effect or argument set stays gated.
        if (grant.pendingTool.isBlank() || grant.pendingEffect.isBlank() ||
            grant.pendingArgumentsDigest.length != 64 ||
            toolName != grant.pendingTool || toolEffect.name != grant.pendingEffect ||
            argumentsDigest != grant.pendingArgumentsDigest
        ) return false
        return consumedLocalA2AApprovals.add(
            "${grant.taskId}:${grant.messageId}:${grant.pendingTool}:${grant.pendingEffect}:${grant.pendingArgumentsDigest}",
        )
    }

    /**
     * Digests of inbound A2A calls that already settled before an owner approval. A matching call
     * is refused by the execution side instead of relying on the model to remember it.
     */
    private val a2aSettledCalls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun bindA2ASettledCalls(keys: List<String>) {
        a2aSettledCalls.clear()
        keys.filter { it.length in 1..512 }.take(64).forEach(a2aSettledCalls::add)
    }

    fun isSettledA2ACall(toolName: String, effect: io.github.zhh_lgtm.nexus.kernel.api.EffectClass?, argumentsDigest: String): Boolean {
        if (a2aSettledCalls.isEmpty()) return false
        val name = effect?.name ?: return false
        if (argumentsDigest.length != 64) return false
        return "$toolName|$name|$argumentsDigest" in a2aSettledCalls
    }

    /** Ledger of calls this inbound run already finished; survives one approval resume. */
    fun recordSettledA2AToolCall(
        toolName: String,
        effect: io.github.zhh_lgtm.nexus.kernel.api.EffectClass,
        argumentsDigest: String,
    ) {
        if (toolName.isBlank() || argumentsDigest.length != 64) return
        val key = "$toolName|${effect.name}|$argumentsDigest"
        if (a2aSettledCalls.size >= 64 && key !in a2aSettledCalls) return
        a2aSettledCalls.add(key)
    }

    fun settledA2ACallsSnapshot(): List<String> = a2aSettledCalls.toList()

    fun hasSettledA2AEffectfulCall(): Boolean =
        a2aSettledCalls.any { it.split('|').getOrNull(1).let { effect -> effect != null && effect != "READ" } }
    @Volatile var modelBudget: RunModelBudgetPort? = null
        private set

    @Synchronized fun bindModelBudget(port: RunModelBudgetPort) {
        check(modelBudget == null) { "MODEL_BUDGET_ALREADY_BOUND" }
        modelBudget = port
    }
    private val resources = CopyOnWriteArraySet<CancellableResource>()
    private val approvalObservers = CopyOnWriteArraySet<(RunApprovalChange) -> Unit>()
    private val approvals = ConcurrentHashMap.newKeySet<String>()
    private val userInputs = ConcurrentHashMap.newKeySet<String>()
    private val pauseObservers = CopyOnWriteArraySet<(Boolean) -> Unit>()
    private val workflowObservers = CopyOnWriteArraySet<(RunWorkflowChange) -> Unit>()
    private val inputObservers = CopyOnWriteArraySet<(RunInputChange) -> Unit>()
    private val budgetObservers = CopyOnWriteArraySet<(io.github.zhh_lgtm.nexus.kernel.api.RunBudget) -> Unit>()
    private val budgetIncreaseObservers = CopyOnWriteArraySet<(io.github.zhh_lgtm.nexus.kernel.api.RunBudget) -> Unit>()
    private var budgetIncreaseOwner: ((io.github.mangi.eta.agent.workflow.ApprovedGoalBudgetIncrease) -> Unit)? = null
    private var resourceBudgetOwner: ((ApprovedRunBudgetExtension) -> Unit)? = null

    fun bindResourceBudgetOwner(owner: (ApprovedRunBudgetExtension) -> Unit) {
        check(resourceBudgetOwner == null); resourceBudgetOwner = owner
    }
    fun extendResourceBudget(approval: ApprovedRunBudgetExtension) {
        throwIfStopped()
        checkNotNull(resourceBudgetOwner) { "BUDGET_EXTENSION_OWNER_UNAVAILABLE" }(approval)
    }

    fun bindBudgetIncreaseOwner(owner: (io.github.mangi.eta.agent.workflow.ApprovedGoalBudgetIncrease) -> Unit) {
        check(budgetIncreaseOwner == null); budgetIncreaseOwner = owner
    }
    fun increaseApprovedBudget(approval: io.github.mangi.eta.agent.workflow.ApprovedGoalBudgetIncrease) {
        throwIfStopped()
        checkNotNull(budgetIncreaseOwner) { "BUDGET_INCREASE_OWNER_UNAVAILABLE" }(approval)
    }
    fun observeApprovedBudgetIncreases(observer: (io.github.zhh_lgtm.nexus.kernel.api.RunBudget) -> Unit): AutoCloseable {
        budgetIncreaseObservers.add(observer)
        return AutoCloseable { budgetIncreaseObservers.remove(observer) }
    }
    fun applyCommittedBudgetIncrease(budget: io.github.zhh_lgtm.nexus.kernel.api.RunBudget) {
        budgetIncreaseObservers.forEach { it(budget) }
    }

    fun observeBudgetLimits(observer: (io.github.zhh_lgtm.nexus.kernel.api.RunBudget) -> Unit): AutoCloseable {
        budgetObservers.add(observer)
        return AutoCloseable { budgetObservers.remove(observer) }
    }

    /** Called only after the owning recorder committed the effective limit. */
    fun applyCommittedBudgetLimit(budget: io.github.zhh_lgtm.nexus.kernel.api.RunBudget) {
        budgetObservers.forEach { it(budget) }
    }

    fun observeWorkflow(observer: (RunWorkflowChange) -> Unit): AutoCloseable {
        workflowObservers.add(observer)
        return AutoCloseable { workflowObservers.remove(observer) }
    }

    fun observeUserInput(observer: (RunInputChange) -> Unit): AutoCloseable {
        inputObservers.add(observer)
        return AutoCloseable { inputObservers.remove(observer) }
    }

    fun recordUserInput(change: RunInputChange) {
        check(executionIdentity != null && inputObservers.isNotEmpty()) { "INPUT_LEDGER_UNAVAILABLE" }
        inputObservers.forEach { it(change) }
        if (change.phase == RunInputPhase.REQUESTED) userInputs.add(change.id) else userInputs.remove(change.id)
    }
    val waitingForUser: Boolean get() = userInputs.isNotEmpty()

    fun requireWorkflowRecorder() {
        throwIfCancelled()
        check(workflowObservers.isNotEmpty()) { "WORKFLOW_LEDGER_UNAVAILABLE" }
    }

    fun recordWorkflowChange(change: RunWorkflowChange) {
        requireWorkflowRecorder()
        workflowObservers.forEach { it(change) }
        change.budgetLimit?.let(::applyCommittedBudgetLimit)
    }

    /**
     * Set once this run has committed a terminal goal state. Further effectful tools fail closed
     * before their side effect; the run itself keeps its natural completion path.
     */
    @Volatile private var workflowTerminated = false

    val isWorkflowTerminal: Boolean
        get() = workflowTerminated

    fun markWorkflowTerminal() {
        workflowTerminated = true
    }

    fun observePause(observer: (Boolean) -> Unit): AutoCloseable {
        pauseObservers.add(observer)
        return AutoCloseable { pauseObservers.remove(observer) }
    }

    val waitingForApproval: Boolean get() = approvals.isNotEmpty()

    fun observeApprovals(observer: (RunApprovalChange) -> Unit): AutoCloseable {
        approvalObservers.add(observer)
        return AutoCloseable { approvalObservers.remove(observer) }
    }

    /** The durable observer runs before the confirmation UI is allowed to become visible. */
    fun beginApproval(
        toolName: String,
        effect: io.github.zhh_lgtm.nexus.kernel.api.EffectClass? = null,
        argumentsDigest: String = "",
    ): ApprovalWait {
        throwIfCancelled()
        if (isRemoteA2AInbound) {
            if (consumeLocalA2AApproval(toolName, effect, argumentsDigest)) {
                return ApprovalWait("local-a2a:${executionIdentity?.runId?.value}", toolName, preapproved = true)
            }
            val pause = AgentRunPauseException(
                AgentRuntimeWire.RunPause(
                    kind = AgentRuntimeWire.RunPause.KIND_AUTH_REQUIRED,
                    reason = "The operation needs approval from the device owner before it can run.",
                    // Explicitly waiting for approval: this call has not started. A pause without
                    // this identity, or with pendingExecuted=true, can only be verified manually.
                    pendingCallId = UUID.randomUUID().toString(),
                    pendingTool = toolName.take(128),
                    pendingEffect = effect?.name.orEmpty().take(64),
                    pendingArgumentsDigest = argumentsDigest.take(64),
                    pendingExecuted = false,
                    settledCalls = settledA2ACallsSnapshot().take(32),
                    settledEffectful = hasSettledA2AEffectfulCall(),
                ),
            )
            abort(pause)
            throw pause
        }
        val id=UUID.randomUUID().toString()
        approvals.add(id)
        try { approvalObservers.forEach { it(RunApprovalChange(id,toolName,RunApprovalPhase.REQUESTED)) } }
        catch(failure: Throwable) { approvals.remove(id); throw failure }
        return ApprovalWait(id,toolName)
    }

    /** Policy receipt only; never called by a model tool and never emits local-user evidence. */
    fun recordPolicyApproval(toolName: String) {
        throwIfCancelled()
        val id = "policy-${UUID.randomUUID()}"
        approvals.add(id)
        try { approvalObservers.forEach { it(RunApprovalChange(id, toolName, RunApprovalPhase.REQUESTED)) } }
        catch (failure: Throwable) { approvals.remove(id); throw failure }
        ApprovalWait(id, toolName).use { it.resolve(RunApprovalPhase.APPROVED) }
    }

    inner class ApprovalWait internal constructor(val id: String, private val toolName: String, private val preapproved: Boolean = false) : AutoCloseable {
        val isPreapproved: Boolean get() = preapproved
        private val resolved=AtomicBoolean(false)
        fun resolve(phase: RunApprovalPhase) {
            if (preapproved) return
            require(phase != RunApprovalPhase.REQUESTED)
            if(!resolved.compareAndSet(false,true)) return
            try { approvalObservers.forEach { it(RunApprovalChange(id,toolName,phase)) } }
            finally { approvals.remove(id) }
        }
        override fun close() = resolve(RunApprovalPhase.CANCELLED)
    }

    @Volatile
    private var cancelled = false

    @Volatile
    private var aborted: RuntimeException? = null

    val isCancelled: Boolean
        get() = cancelled || aborted != null

    val isUserCancelled: Boolean
        get() = cancelled

    val abortFailure: RuntimeException?
        get() = aborted

    private val lock = ReentrantLock()
    private val pauseCondition = lock.newCondition()
    private val steeringMessages = ArrayDeque<String>()
    private var acceptingSteering = true
    @Volatile
    private var paused = false

    val isPaused: Boolean
        get() = paused

    fun cancel() {
        lock.withLock {
            if (aborted != null) return
            cancelled = true
            acceptingSteering = false
            steeringMessages.clear()
            paused = false
            pauseCondition.signalAll()
        }
        resources.forEach { resource ->
            runCatching { resource.cancel() }
        }
    }

    /** Stop active resources while preserving a non-user failure (for example a deadline). */
    fun abort(failure: RuntimeException) {
        lock.withLock {
            if (cancelled || aborted != null) return
            aborted = failure
            acceptingSteering = false
            steeringMessages.clear()
            paused = false
            pauseCondition.signalAll()
        }
        resources.forEach { resource -> runCatching { resource.cancel() } }
    }

    /**
     * 将补充指令排入下一个 turn。steering 不取消当前模型请求或工具批次。
     */
    fun steer(text: String): Boolean {
        val prompt = text.trim()
        if (prompt.isBlank()) return false
        lock.withLock {
            if (isCancelled || !acceptingSteering) return false
            steeringMessages.addLast(prompt)
        }
        return true
    }

    /** 默认逐条消费，避免后来的补充指令越过前一条的模型回合。 */
    fun pollSteeringMessage(): String? =
        lock.withLock { steeringMessages.pollFirst() }

    /**
     * 自然结束前原子地消费最后一条 steering；若队列为空则永久关闭本 run 的接收入口。
     * 这样 Service 不会在 loop 已返回后仍把补充指令误报为已接收。
     */
    fun pollSteeringOrSeal(): String? =
        lock.withLock {
            steeringMessages.pollFirst()?.let { return it }
            acceptingSteering = false
            null
        }

    val hasPendingSteering: Boolean
        get() = lock.withLock { steeringMessages.isNotEmpty() }

    /**
     * 暂停执行：后续 [throwIfCancelled] 调用会阻塞挂起，直到 [resume] 或 [cancel]。
     * 在工作线程的检查点调用，不会阻塞调用方线程。
     */
    fun pause() {
        lock.withLock {
            if (isCancelled || paused) return
            pauseObservers.forEach { it(true) }
            paused = true
        }
    }

    /**
     * 恢复执行：唤醒被 [throwIfCancelled] 阻塞的工作线程，从挂起点继续。
     */
    fun resume() {
        lock.withLock {
            if (isCancelled || !paused) return
            pauseObservers.forEach { it(false) }
            paused = false
            pauseCondition.signalAll()
        }
    }

    /**
     * 检查点：若已取消则抛异常；若已暂停则阻塞挂起直到恢复或取消。
     * 在 agent 循环的每轮/每步调用，实现暂停可恢复、取消即终止。
     */
    fun throwIfCancelled() {
        val scope = RuntimeInvocationScope.resourceScope
        scope?.checkActive()
        lock.withLock {
            while (paused && !isCancelled) {
                try {
                    if (scope == null) pauseCondition.await() else {
                        pauseCondition.await(250, TimeUnit.MILLISECONDS)
                        scope.checkActive()
                    }
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    if (aborted == null) cancelled = true
                }
            }
        }
        throwIfStopped()
    }

    /** Check termination without waiting for a user pause to be released. */
    fun throwIfStopped() {
        RuntimeInvocationScope.resourceScope?.checkActive()
        aborted?.let { throw it }
        if (cancelled) throw AgentRunCancelledException()
    }

    fun awaitRetryDelay(delayMs: Long) {
        throwIfCancelled()
        val cancelledLatch = CountDownLatch(1)
        val binding = register { cancelledLatch.countDown() }
        try {
            cancelledLatch.await(delayMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            aborted?.let { throw it }
            throw AgentRunCancelledException()
        } finally {
            binding.close()
        }
        throwIfCancelled()
    }

    fun register(cancel: () -> Unit): ResourceBinding {
        val resource = CancellableResource(cancel)
        resources.add(resource)
        val scoped = RuntimeInvocationScope.resourceScope?.register(resource::cancel)
        if (isCancelled) resource.cancel()
        return ResourceBinding { resources.remove(resource); scoped?.close() }
    }

    inner class ResourceBinding internal constructor(private val closeBlock: () -> Unit) {
        fun close() {
            closeBlock()
        }
    }

    private class CancellableResource(private val cancelBlock: () -> Unit) {
        private val cancelled = AtomicBoolean(false)

        fun cancel() {
            if (cancelled.compareAndSet(false, true)) cancelBlock()
        }
    }
}

internal class AgentRunCancelledException(
    val localRuntime: LocalRuntimeMetrics? = null,
) : RuntimeException("Agent run cancelled")

internal enum class RunApprovalPhase { REQUESTED, APPROVED, REJECTED, TIMED_OUT, CANCELLED, FAILED }
internal data class RunApprovalChange(val id: String, val toolName: String, val phase: RunApprovalPhase)

internal enum class RunInputPhase { REQUESTED, ANSWERED, DECLINED, CANCELLED, TIMED_OUT }
internal data class RunInputChange(val id: String, val phase: RunInputPhase, val payloadRef: String)

internal data class RunWorkflowChange(val id: String, val type: io.github.zhh_lgtm.nexus.kernel.api.RunEventType,
    val toolName: String, val payloadRef: String? = null,
    val budgetLimit: io.github.zhh_lgtm.nexus.kernel.api.RunBudget? = null,
    val goalId: io.github.zhh_lgtm.nexus.kernel.api.GoalId? = null) {
    init {
        require(toolName in setOf("goal_manage", "plan_manage", "notepad_manage"))
        require((budgetLimit == null) == (goalId == null))
        require(budgetLimit == null || type == io.github.zhh_lgtm.nexus.kernel.api.RunEventType.GOAL_UPDATED)
        require(type in setOf(io.github.zhh_lgtm.nexus.kernel.api.RunEventType.WORKFLOW_UPDATE_STARTED,
            io.github.zhh_lgtm.nexus.kernel.api.RunEventType.WORKFLOW_UPDATE_FAILED,
            io.github.zhh_lgtm.nexus.kernel.api.RunEventType.GOAL_UPDATED,
            io.github.zhh_lgtm.nexus.kernel.api.RunEventType.PLAN_UPDATED,
            io.github.zhh_lgtm.nexus.kernel.api.RunEventType.NOTEPAD_UPDATED))
    }
}
