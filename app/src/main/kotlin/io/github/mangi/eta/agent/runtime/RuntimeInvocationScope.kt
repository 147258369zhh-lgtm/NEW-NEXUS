package io.github.mangi.eta.agent.runtime

import kotlinx.coroutines.asContextElement
import kotlin.coroutines.CoroutineContext

/** Trusted synchronous tool boundary; never derived from model parameters. */
internal object RuntimeInvocationScope {
    private val owner=ThreadLocal<AgentRunController?>()
    private val resources = ThreadLocal<RunResourceScope?>()
    private val invoker = ThreadLocal<io.github.mangi.eta.agent.model.AgentModelClient.ToolExecutor?>()
    data class ToolInvocation(val callId: String, val toolName: String, val providerId: String, val model: String,
        val messagesJson: String)
    private val invocation = ThreadLocal<ToolInvocation?>()
    val toolInvocation: ToolInvocation? get() = invocation.get()
    val controller: AgentRunController? get()=owner.get()
    val resourceScope: RunResourceScope? get() = resources.get()
    val nestedToolExecutor: io.github.mangi.eta.agent.model.AgentModelClient.ToolExecutor? get() = invoker.get()

    /** Capture at the synchronous entry, then restore on every coroutine dispatcher hop. */
    fun coroutineContext(): CoroutineContext = owner.asContextElement(controller) + resources.asContextElement(resourceScope) + invoker.asContextElement(nestedToolExecutor) + invocation.asContextElement(toolInvocation)

    fun <T> withToolInvocation(value: ToolInvocation, action: () -> T): T {
        val before = invocation.get(); invocation.set(value)
        return try { action() } finally { if (before == null) invocation.remove() else invocation.set(before) }
    }

    fun <T> withResources(scope: RunResourceScope, action: () -> T): T {
        val before = resources.get(); resources.set(scope)
        return try { scope.checkActive(); action() } finally { if (before == null) resources.remove() else resources.set(before) }
    }

    fun <T> withNestedTools(executor: io.github.mangi.eta.agent.model.AgentModelClient.ToolExecutor, action: () -> T): T {
        val before = invoker.get(); invoker.set(executor)
        return try { action() } finally { if (before == null) invoker.remove() else invoker.set(before) }
    }

    fun <T> withController(controller: AgentRunController, action: () -> T): T {
        val before=owner.get()
        owner.set(controller)
        return try { action() } finally {
            if(before == null) owner.remove() else owner.set(before)
        }
    }
}
