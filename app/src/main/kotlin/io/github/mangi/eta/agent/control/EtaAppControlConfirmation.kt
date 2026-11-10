package io.github.mangi.eta.agent.control

import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeout
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
    private val lock = Any()
    private var active: Pending? = null
    private val mutablePending = MutableStateFlow<EtaAppControlConfirmationRequest?>(null)

    val pending: StateFlow<EtaAppControlConfirmationRequest?> = mutablePending.asStateFlow()

    suspend fun awaitApproval(
        info: EtaControlCapabilityInfo,
        parameters: JSONObject,
    ): Boolean {
        val completion = CompletableDeferred<Boolean>()
        val request = EtaAppControlConfirmationRequest(
            id = UUID.randomUUID().toString(),
            capabilityId = info.id,
            title = info.title,
            summary = info.summary,
            parameters = parameters.confirmationDetails(),
        )
        val pending = Pending(request, completion)
        synchronized(lock) {
            if (active != null) {
                throw EtaControlException(
                    "CONFIRMATION_BUSY",
                    "已有操作等待确认，请先处理当前确认窗口",
                )
            }
            active = pending
            mutablePending.value = request
        }
        return try {
            withTimeout(APPROVAL_TIMEOUT_MILLIS) { completion.await() }
        } catch (_: TimeoutCancellationException) {
            throw EtaControlException(
                "CONFIRMATION_TIMEOUT",
                "操作确认已超时，未修改任何数据",
            )
        } finally {
            synchronized(lock) {
                if (active === pending) {
                    active = null
                    mutablePending.value = null
                }
            }
        }
    }

    fun approve(requestId: String): Boolean = resolve(requestId, approved = true)

    fun cancel(requestId: String): Boolean = resolve(requestId, approved = false)

    private fun resolve(requestId: String, approved: Boolean): Boolean {
        val pending = synchronized(lock) {
            active?.takeIf { it.request.id == requestId }?.also {
                active = null
                mutablePending.value = null
            }
        } ?: return false
        return pending.completion.complete(approved)
    }

    private fun JSONObject.confirmationDetails(): List<Pair<String, String>> {
        if (length() == 0) return emptyList()
        return keys().asSequence()
            .sorted()
            .take(8)
            .map { key ->
                val sensitive = key.contains("password", ignoreCase = true) ||
                    key.contains("token", ignoreCase = true) ||
                    key.contains("secret", ignoreCase = true) ||
                    key.contains("key", ignoreCase = true)
                val value = if (sensitive) {
                    "已设置"
                } else {
                    opt(key)?.toString()
                        ?.replace(Regex("\\s+"), " ")
                        ?.trim()
                        ?.take(240)
                        .orEmpty()
                }
                key to value
            }
            .toList()
    }

    private data class Pending(
        val request: EtaAppControlConfirmationRequest,
        val completion: CompletableDeferred<Boolean>,
    )
}
