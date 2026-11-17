package io.github.mangi.eta.agent.runtime

import android.content.Context
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.ProviderClientFactory
import io.github.mangi.eta.data.repository.NexusExecutionStore

/** Runs the production admission and executor with a controlled transport boundary. */
internal class RuntimeModelTaskHarness(
    private val context: Context,
    val store: NexusExecutionStore,
    provider: (AgentModelClient.ModelConfig) -> AgentProviderClient = ProviderClientFactory::getClient,
) {
    private val registry = RuntimeRunRegistry()
    private val api = NexusRunApplicationApi(store, registry) { registry.get(it)?.session?.controller?.cancel() }
    val snapshots = mutableListOf<AgentRunTelemetrySnapshot>()
    val results = mutableListOf<AgentRuntimeWire.RunResult>()
    val requests = mutableListOf<AgentRuntimeWire.RunRequest>()
    private val executor = AgentRuntimeRunExecutor(context, { error("Model tasks must not read GUI permissions") }, { it },
        GuiControlCoordinator(), { _, _ -> }, { _, result, _ -> results += result }, modelTaskProvider = provider)

    fun run(request: AgentRuntimeWire.RunRequest, caller: AgentRunController): AgentRuntimeWire.RunResult {
        requests += request
        lateinit var session: AgentRuntimeSession
        val accepted = api.admit(request) { recorder ->
            val controller = AgentRunController(); recorder.bindController(controller)
            session = AgentRuntimeSession(request.runId, controller, executionRequest = recorder.request,
                operation = request.operation, eventRecorder = recorder::record, executionStartRecorder = recorder::start,
                checkpointRecorder = recorder::saveCheckpoint, inputRecorder = recorder::prepareInput,
                terminalRecorder = { recorder.finish(it, controller.isUserCancelled) },
                telemetry = AgentRunTelemetry(request, onSnapshot = { snapshots += it }),
                beforeTerminalPublish = { registry.finish(request.runId, session) })
            RuntimeRunRegistry.Run(request, session)
        } as RuntimeRunRegistry.Admission.Accepted
        accepted.run.prepared(request)
        val cancellation = caller.register { session.controller.cancel() }
        try { return executor.execute(session, request).result } finally { cancellation.close() }
    }
}
