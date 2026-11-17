package io.github.mangi.eta.fork.a2a

import android.content.Context

/** The local task record, never a caller-supplied peer name, owns the current token grant. */
internal object A2AAccessRuntimeGuard {
    suspend fun isAllowed(context: Context, taskId: String, tool: String): Boolean {
        val task = A2ADatabase.get(context).dao().inboundTask(taskId) ?: return false
        if (task.state != A2ATaskState.WORKING.name) return false
        val original = task.accessJson?.let {
            runCatching { A2A_JSON.decodeFromString<A2AToolAccess>(it) }.getOrNull()
        } ?: return false
        // This tool only asks the remote caller for input; it grants no device capability.
        if (tool == A2AInboundRuntimeOutcome.INPUT_TOOL) return true
        val current = A2AAccessPolicyStore(context).accessFor(original.peerId)
        return tool in original.allowedTools && tool in current.allowedTools &&
            tool !in A2AToolAccess.HIGH_RISK_TOOLS
    }
}
