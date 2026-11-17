package io.github.mangi.eta.agent.control

import java.util.UUID
import java.util.ArrayDeque
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRunCancelledException
import io.github.mangi.eta.agent.runtime.RunApprovalPhase
import io.github.mangi.eta.agent.runtime.RuntimeInvocationScope
import org.json.JSONObject

internal data class EtaAppControlConfirmationRequest(
    val id: String,
    val capabilityId: String,
    val title: String,
    val summary: String,
    val parameters: List<Pair<String, String>>,
)

/**
 * Process-local approval gate for app-shell mutations. The model can request an operation, but only
 * the visible confirmation host can approve it. The legacy `confirmed` tool argument is ignored.
 */
internal object EtaAppControlConfirmation {
    private const val APPROVAL_TIMEOUT_MILLIS = 120_000L
    private const val MAX_QUEUED_APPROVALS = 32
    private const val RUNTIME_TOOL_CAPABILITY_PREFIX = "runtime_tool:"
    private val lock = Any()
    private var active: Pending? = null
    private val queue = ArrayDeque<Pending>()
    private val mutablePending = MutableStateFlow<EtaAppControlConfirmationRequest?>(null)
    private val mutablePendingCount = MutableStateFlow(0)

    val pending: StateFlow<EtaAppControlConfirmationRequest?> = mutablePending.asStateFlow()
    val pendingCount: StateFlow<Int> = mutablePendingCount.asStateFlow()

    suspend fun awaitApproval(
        info: EtaControlCapabilityInfo,
        parameters: JSONObject,
        requestId: String? = null,
        timeoutMillis: Long = APPROVAL_TIMEOUT_MILLIS,
        effect: io.github.zhh_lgtm.nexus.kernel.api.EffectClass? = null,
        /**
         * Digest of the real tool arguments. Callers with wrapper envelopes (host, run id,
         * expiry) must pass this so the approval is bound to stable call arguments only.
         */
        argumentsDigest: String? = null,
        controller: AgentRunController? = RuntimeInvocationScope.controller,
        actionScope: io.github.mangi.eta.agent.approval.ActionScope? = null,
    ): Boolean {
        require(requestId == null || requestId.matches(Regex("[A-Za-z0-9._:-]{1,128}")))
        require(timeoutMillis in 1..APPROVAL_TIMEOUT_MILLIS)
        controller?.throwIfStopped()
        val mode = io.github.mangi.eta.agent.permission.AgentPermissionPolicy.effectiveMode(controller)
        // Full access changes only the app's execution approval. Android/Root/account checks
        // still run in their real executors, and permission-setting writes are separately local-only.
        val session = controller?.approvalSession
        val scope = session?.scope(info, parameters, effect, argumentsDigest, actionScope)
        val runId = controller?.executionIdentity?.runId?.value ?: controller?.approvalPolicy?.runId.orEmpty()
        if (mode == io.github.mangi.eta.data.model.AgentPermissionMode.FULL_ACCESS) {
            if (session != null && scope != null) session.store.auditDecision(scope, runId, "allow", "run_full_access")
            controller?.recordPolicyApproval(runToolId(info.id))
            return true
        }
        if (session != null && scope != null && controller != null) {
            val review = session.review(mode, controller, scope, parameters)
            session.store.auditDecision(scope, runId, review.decision, review.reason, parameters)
            val latestMode = io.github.mangi.eta.agent.permission.AgentPermissionPolicy.effectiveMode(controller)
            if (review.decision == "deny" && latestMode == io.github.mangi.eta.data.model.AgentPermissionMode.SMART_APPROVAL) return false
            val latestPreference = session.store.matching(scope)
            val preferenceBlocksReuse = latestPreference?.revoked == true || (latestPreference?.refusals ?: 0) > 0
            if (review.decision == "allow" &&
                !preferenceBlocksReuse &&
                (review.evidenceExpiresAt == null || System.currentTimeMillis() < review.evidenceExpiresAt) &&
                (!review.usesLearnedPreference || session.store.learningEnabled()) &&
                latestMode == io.github.mangi.eta.data.model.AgentPermissionMode.SMART_APPROVAL) {
                session.store.consumeSingleActionGrant(scope, runId, "independent_review", System.currentTimeMillis() + timeoutMillis)
                controller.recordPolicyApproval(runToolId(info.id))
                return true
            }
        }
        val wait = controller?.beginApproval(
            runToolId(info.id),
            effect,
            argumentsDigest?.takeIf { it.length == io.github.mangi.eta.agent.capability.A2AToolCallIdentity.DIGEST_CHARS }
                ?: io.github.mangi.eta.agent.capability.A2AToolCallIdentity.canonicalArgumentsDigest(parameters),
        )
        if (wait?.isPreapproved == true) return true
        val completion = CompletableDeferred<Boolean>()
        val cancellation = controller?.register { completion.cancel() }
        var pending: Pending? = null
        return try {
            controller?.throwIfStopped()
            val request = EtaAppControlConfirmationRequest(
                id = wait?.id ?: requestId ?: UUID.randomUUID().toString(),
                capabilityId = info.id,
                title = info.title,
                summary = info.summary,
                parameters = parameters.confirmationDetails(),
            )
            pending = Pending(request, completion, System.currentTimeMillis() + timeoutMillis) { approved ->
                if (session != null && scope != null) {
                    session.store.recordLocalUserDecision(scope, approved, runId, request.id)
                }
            }
            synchronized(lock) {
                if (active == null) {
                    active = pending
                } else {
                    if (queue.size >= MAX_QUEUED_APPROVALS) {
                        throw EtaControlException("CONFIRMATION_QUEUE_FULL", "待审批操作过多，请先处理现有请求")
                    }
                    queue.addLast(pending)
                }
                publishLocked()
            }
            val approved = withTimeoutOrNull(timeoutMillis) { completion.await() }
            if (approved == null) {
                wait?.resolve(RunApprovalPhase.TIMED_OUT)
                throw EtaControlException(
                    "CONFIRMATION_TIMEOUT",
                    "操作确认已超时，未修改任何数据",
                )
            }
            controller?.throwIfStopped()
            wait?.resolve(if (approved) RunApprovalPhase.APPROVED else RunApprovalPhase.REJECTED)
            controller?.throwIfCancelled()
            if (approved && session != null && scope != null) session.store.consumeSingleActionGrant(
                scope, runId, "local_user", System.currentTimeMillis() + timeoutMillis)
            approved
        } catch (failure: CancellationException) {
            wait?.resolve(RunApprovalPhase.CANCELLED)
            throw failure
        } catch (failure: AgentRunCancelledException) {
            wait?.resolve(RunApprovalPhase.CANCELLED)
            throw failure
        } catch (failure: Exception) {
            wait?.resolve(RunApprovalPhase.FAILED)
            throw failure
        } finally {
            synchronized(lock) {
                if (active === pending) {
                    active = null
                } else {
                    queue.remove(pending)
                }
                if (active == null && queue.isNotEmpty()) active = queue.removeFirst()
                publishLocked()
            }
            cancellation?.close()
            wait?.close()
        }
    }

    fun approve(requestId: String): Boolean = resolve(requestId, approved = true, recordEvidence = true)

    /** Closing a dialog is cancellation, not a negative preference. */
    fun cancel(requestId: String): Boolean = resolve(requestId, approved = false, recordEvidence = false)

    fun reject(requestId: String): Boolean = resolve(requestId, approved = false, recordEvidence = true)

    /**
     * This gate owns both identities: [EtaControlCapabilityInfo.id] is the UI-facing capability id,
     * while the run controller and scoped A2A grants address tools by their stable bare id.
     * Translate once here so every approval path reaches the controller with the same identity.
     */
    private fun runToolId(capabilityId: String): String =
        if (capabilityId.startsWith(RUNTIME_TOOL_CAPABILITY_PREFIX)) {
            capabilityId.removePrefix(RUNTIME_TOOL_CAPABILITY_PREFIX)
        } else {
            capabilityId
        }

    private fun resolve(requestId: String, approved: Boolean, recordEvidence: Boolean): Boolean {
        val pending = synchronized(lock) {
            active?.takeIf { it.request.id == requestId }?.also {
                active = null
                if (queue.isNotEmpty()) active = queue.removeFirst()
                publishLocked()
            }
        } ?: return false
        if (!pending.completion.isActive || System.currentTimeMillis() >= pending.expiresAt) return false
        return try {
            if (recordEvidence) pending.onLocalUserDecision(approved)
            pending.completion.complete(approved)
        } catch (failure: Exception) {
            pending.completion.completeExceptionally(failure)
            false
        }
    }

    private fun publishLocked() {
        mutablePending.value = active?.request
        mutablePendingCount.value = (if (active == null) 0 else 1) + queue.size
    }

    private fun JSONObject.confirmationDetails(): List<Pair<String, String>> {
        if (length() == 0) return emptyList()
        val details = linkedMapOf<String, String>()
        keys().asSequence().sorted().forEach { key ->
            if (key == "parameters") {
                optJSONObject("parameters")?.let { nested ->
                    nested.keys().asSequence().sorted().take(12).forEach { nestedKey ->
                        details[nestedKey] = detailValue(nestedKey, nested.opt(nestedKey))
                    }
                } ?: run { details[key] = detailValue(key, opt(key)) }
            } else {
                details[key] = detailValue(key, opt(key))
            }
        }
        return details.entries.take(16).map { it.key to it.value }
    }

    private fun detailValue(key: String, value: Any?): String {
        val sensitive = listOf("password", "token", "secret", "api_key", "authorization", "credential")
            .any { key.contains(it, ignoreCase = true) }
        return if (sensitive) {
            "已设置"
        } else {
            value?.toString()?.replace(Regex("\\s+"), " ")?.trim()?.take(240).orEmpty()
        }
    }

    private data class Pending(
        val request: EtaAppControlConfirmationRequest,
        val completion: CompletableDeferred<Boolean>,
        val expiresAt: Long,
        val onLocalUserDecision: (Boolean) -> Unit,
    )
}
