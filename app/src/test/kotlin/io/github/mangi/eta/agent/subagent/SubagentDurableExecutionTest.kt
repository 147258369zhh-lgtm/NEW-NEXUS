package io.github.mangi.eta.agent.subagent

import android.app.Application
import io.github.mangi.eta.agent.memory.AgentMemoryContext
import io.github.mangi.eta.agent.model.*
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.agent.skill.SkillContext
import io.github.mangi.eta.agent.control.EtaAppControlTool
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.agent.tool.AgentToolCapabilities
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.repository.*
import io.github.zhh_lgtm.nexus.kernel.api.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/** Controlled-provider integration: this executes the actual child loop and Android SQLite store. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[36],application=Application::class)
class SubagentDurableExecutionTest {
    @Test fun childExtensionsAreOwnedByTheChildAndCannotUseItsNativeExecutorToEscapeTheProfile() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        val parent = AgentRunController()
        var denied = ""; var closed = false; var intercepted = 0
        val executor = SubagentExecutor(conversationKey = "child-hooks", parentController = parent,
            configProvider = { _, _, _, _, _ -> ResolvedSubagentConfig(config, SubagentConfigSource.PRIMARY, SubagentConfigSource.PRIMARY) },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("{\"ok\":true}") },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(), executionContext = null,
            onProgress = {}, providerFactory = { provider() }, extensionsFactory = { child, native, _ ->
                assertNotSame(parent, child)
                val rejected = native.execute(AgentModelClient.ToolCall("hook-write", "write_file", "{\"path\":\"no\",\"content\":\"no\"}"))
                denied = JSONObject(rejected.content).getString("code")
                object : AgentRunExtensions {
                    override fun intercept(call: AgentModelClient.ToolCall): AgentModelClient.ToolResult? { intercepted++; return null }
                    override fun close() { closed = true }
                }
            })
        val result = executor.execute(1, AgentModelClient.ToolCall("spawn-hooks", "subagent", JSONObject().put("subagent_type", "explore")
            .put("prompt", "fixture").put("description", "fixture").toString()))
        assertTrue(result.content, JSONObject(result.content).getBoolean("ok"))
        assertEquals("SUBAGENT_TOOL_FORBIDDEN", denied); assertTrue(intercepted > 0); assertTrue(closed)
    }
    @Test fun aNestedPackageCannotUseTheParentExecutorToBypassTheActualChildProfile() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        var executions = 0
        var denied: String? = null
        val executor = SubagentExecutor(conversationKey = "nested-package-policy", parentController = AgentRunController(),
            configProvider = { _, _, _, _, _ -> ResolvedSubagentConfig(config, SubagentConfigSource.PRIMARY, SubagentConfigSource.PRIMARY) },
            capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
            baseToolExecutor = AgentModelClient.ToolExecutor {
                executions++
                val nested = requireNotNull(RuntimeInvocationScope.nestedToolExecutor).execute(AgentModelClient.ToolCall("nested-write", "write_file", "{\"path\":\"blocked.txt\",\"content\":\"no\"}"))
                denied = JSONObject(nested.content).optString("code")
                AgentModelClient.ToolResult("{\"ok\":true,\"text\":\"read fixture\"}")
            },
            skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(),
            executionContext = null, onProgress = {}, providerFactory = { provider() })
        val result = executor.execute(1, AgentModelClient.ToolCall("spawn-nested", "subagent", JSONObject().put("subagent_type", "explore")
            .put("prompt", "inspect fixture").put("description", "fixture").toString()))
        assertTrue(result.content, JSONObject(result.content).getBoolean("ok"))
        assertEquals("SUBAGENT_TOOL_FORBIDDEN", denied)
        assertEquals(1, executions)
    }

    @Test fun actualParentAndChildProvidersShareTheTotalRequestAllowance() {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        NexusExecutionStore(context).use { store ->
            val api = NexusRunApplicationApi(store, RuntimeRunRegistry()) {}
            val parentController = AgentRunController()
            val legacy = AgentRuntimeWire.RunRequest("shared-requests", "fixture", config, images = emptyList())
            lateinit var parent: NexusExecutionRecorder
            api.admit(legacy, resolveContext = { NexusRunContext(goalId = GoalId("shared-goal"), goalBudget = RunBudget(hardRounds = 3)) }) { recorder ->
                parent = recorder; recorder.bindController(parentController)
                RuntimeRunRegistry.Run(legacy, AgentRuntimeSession(legacy.runId, parentController, executionRequest = recorder.request))
            }
            parent.start()
            val request = ProviderRequest(config, JSONArray().put(JSONObject().put("role", "user").put("content", "fixture")), JSONArray())
            BudgetedAgentProvider.wrap(provider()).complete(request, parentController) {}
            val executor = SubagentExecutor(conversationKey = "shared-model-budget", parentController = parentController,
                configProvider = { _, _, _, _, _ -> ResolvedSubagentConfig(config, SubagentConfigSource.PRIMARY, SubagentConfigSource.PRIMARY) },
                capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
                baseToolExecutor = AgentModelClient.ToolExecutor { AgentModelClient.ToolResult("""{"ok":true,"text":"fixture result"}""") },
                skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED, mcpTools = JSONArray(),
                executionContext = null, onProgress = {},
                childExecutionFactory = { operation, session, budget, controller -> api.openChild(parent.request.runId, operation, session, budget, controller) },
                providerFactory = { provider() })
            val result = executor.execute(1, AgentModelClient.ToolCall("spawn", "subagent", JSONObject().put("subagent_type", "explore")
                .put("prompt", "inspect fixture").put("description", "fixture").toString()))
            assertTrue(result.content, JSONObject(result.content).getBoolean("ok"))
            val child = store.recent().single { it.request.parentRunId != null }
            assertEquals(3L, store.modelBudgetTotals(parent.request.runId).requests)
            assertEquals(1, store.modelBudgetRequests(parent.request.runId).size)
            assertEquals(2, store.modelBudgetRequests(child.request.runId).size)
            assertEquals(RunState.SUCCEEDED, child.state)
            assertEquals("RUN_TOTAL_MODEL_REQUESTS_EXCEEDED", assertThrows(AgentModelFailure::class.java) {
                BudgetedAgentProvider.wrap(provider()).complete(request, parentController) {}
            }.code)
            assertFalse(parentController.isUserCancelled)
            parent.finish(AgentRuntimeWire.RunResult(legacy.runId, false, "", "budget reached"), false)
        }
    }

    private val config=AgentModelClient.ModelConfig(baseUrl="https://fixture.invalid",apiKey="PRIVATE_CONFIG_KEY",
        model="fixture",systemPrompt="",terminalTools=true,browserTools=false,autoCompactionEnabled=false)

    @Test fun actualChildToolApprovalBelongsOnlyToTheChildRun() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        Prefs.initLocal(context)
        NexusExecutionStore(context).use { store ->
            val parent = RunRequest(RunId("approval-parent"), RunSource.CHAT, RunMode.CHAT, HostId("native-android"))
            val parentController = AgentRunController()
            val parentRecorder = NexusExecutionRecorder(store, parent)
            parentRecorder.bindController(parentController)
            parentRecorder.start()
            val api = NexusRunApplicationApi(store, RuntimeRunRegistry()) {}
            val executor = SubagentExecutor(
                conversationKey = "child-approval", parentController = parentController,
                configProvider = { _, _, _, _, _ -> ResolvedSubagentConfig(config, SubagentConfigSource.PRIMARY, SubagentConfigSource.PRIMARY) },
                capabilitiesProvider = { AgentToolCapabilities(rootAvailable = false) },
                baseToolExecutor = AgentModelClient.ToolExecutor {
                    AgentModelClient.ToolResult(EtaAppControlTool.execute(context, JSONObject()
                        .put("action", "execute").put("id", "settings.memory.clear")))
                },
                skillContext = SkillContext.EMPTY, memoryContext = AgentMemoryContext.DISABLED,
                mcpTools = JSONArray(), executionContext = null, onProgress = {},
                childExecutionFactory = { operation, session, budget, controller ->
                    api.openChild(parent.runId, operation, session, budget, controller)
                }, providerFactory = { provider() },
            )
            val execution = async(Dispatchers.Default) {
                RuntimeInvocationScope.withController(parentController) {
                    executor.execute(1, AgentModelClient.ToolCall("spawn-approval", "subagent", JSONObject()
                        .put("subagent_type", "explore").put("prompt", "fixture").put("description", "fixture").toString()))
                }
            }
            try {
                val approval = withTimeout(5_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                val child = store.recent().single { it.request.parentRunId == parent.runId }
                assertEquals(RunState.WAITING_AUTH, child.state)
                assertEquals(RunState.RUNNING, store.find(parent.runId)?.state)
                assertFalse(parentController.waitingForApproval)
                assertTrue(store.timeline(parent.runId).none { it.event.type == RunEventType.APPROVAL_REQUIRED })
                EtaAppControlConfirmation.cancel(approval.id)
                val result = withTimeout(5_000) { execution.await() }
                assertTrue(result.content, JSONObject(result.content).getBoolean("ok"))
                val approvalItem = store.items(child.request.runId).single { it.kind == ItemKind.APPROVAL }
                assertEquals(RunState.FAILED, approvalItem.state)
                assertNotNull(approvalItem.completedAt)
                assertEquals(RunState.SUCCEEDED, store.find(child.request.runId)?.state)
                assertEquals(RunState.RUNNING, store.find(parent.runId)?.state)
            } finally {
                parentController.cancel()
                execution.cancelAndJoin()
                EtaAppControlConfirmation.pending.value?.let { EtaAppControlConfirmation.cancel(it.id) }
                parentRecorder.finish(AgentRuntimeWire.RunResult("approval-parent", false, "", "fixture stopped"), true)
            }
        }
    }

    @Test fun actualChildLoopHasIndependentRunItemsCheckpointAndParentLink() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val parent=RunRequest(RunId("parent"),RunSource.CHAT,RunMode.CHAT,HostId("native-android"),
                sessionId=SessionId("session"),budget=RunBudget(hardRounds=8,hardToolCalls=5,hardDurationMs=60_000))
            val parentRecorder=NexusExecutionRecorder(store,parent)
            parentRecorder.start()
            val api=NexusRunApplicationApi(store,RuntimeRunRegistry()) {}
            var executed=0
            val executor=SubagentExecutor(
                conversationKey="durable-integration",parentController=AgentRunController(),
                configProvider={_,_,_,_,_->ResolvedSubagentConfig(config,SubagentConfigSource.PRIMARY,SubagentConfigSource.PRIMARY,maxToolCalls=3)},
                capabilitiesProvider={AgentToolCapabilities(rootAvailable=false)},
                baseToolExecutor=AgentModelClient.ToolExecutor { executed++;AgentModelClient.ToolResult("""{"ok":true,"text":"fixture file"}""") },
                skillContext=SkillContext.EMPTY,memoryContext=AgentMemoryContext.DISABLED,
                mcpTools=JSONArray(),executionContext=null,onProgress={},
                childExecutionFactory={operation,session,budget,controller->api.openChild(parent.runId,operation,session,budget,controller)},
                providerFactory={provider()},
            )
            val result=executor.execute(1,AgentModelClient.ToolCall("spawn-1","subagent",JSONObject()
                .put("subagent_type","explore").put("prompt","read a fixture").put("description","inspect fixture").toString()))
            assertTrue(result.content,JSONObject(result.content).getBoolean("ok"))
            assertEquals(1,executed)
            val child=store.recent().single { it.request.source==RunSource.SUBAGENT }
            assertEquals(parent.runId,child.request.parentRunId)
            assertEquals(RunState.SUCCEEDED,child.state)
            assertEquals(3,child.request.budget.hardToolCalls)
            assertTrue(child.request.budget.hardDurationMs!!<=60_000)
            assertNotEquals(parent.runId,child.request.runId)
            assertTrue(store.items(child.request.runId).any { it.kind==ItemKind.TOOL_CALL && it.state==RunState.SUCCEEDED })
            val cp=store.checkpoint(child.request.runId)!!
            assertTrue(cp.payload.contains("fixture file"))
            assertFalse(cp.payload.contains("PRIVATE_CONFIG_KEY"))
            assertTrue(store.timeline(parent.runId).none { it.fact.toolName=="read_file" })
            assertEquals(RunState.RUNNING,store.find(parent.runId)?.state)
        }
    }

    @Test fun canonicalChildCancellationReleasesItsControllerWithoutCancellingParent() {
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val parent=RunRequest(RunId("parent-cancel"),RunSource.CHAT,RunMode.CHAT,HostId("native-android"),budget=RunBudget(hardRounds=2))
            val recorder=NexusExecutionRecorder(store,parent);recorder.start()
            val api=NexusRunApplicationApi(store,RuntimeRunRegistry()) { error("must not cancel parent") }
            val controller=AgentRunController()
            val child=api.openChild(parent.runId,"operation","child-session",RunBudget(hardRounds=9),controller)
            assertEquals(2,child.request.budget.hardRounds)
            child.start()
            api.cancel(child.request.runId,"local user")
            assertTrue(controller.isUserCancelled)
            child.finish(AgentRuntimeWire.RunResult("operation",false,"","stopped"),cancelled=true)
            assertEquals(RunState.CANCELLED,store.find(child.request.runId)?.state)
            assertEquals(RunState.RUNNING,store.find(parent.runId)?.state)
        }
    }

    @Test fun childCannotExceedTheParentsSharedToolAllowanceAndPreservesBudgetFailure() {
        Prefs.initLocal(RuntimeEnvironment.getApplication())
        NexusExecutionStore(RuntimeEnvironment.getApplication()).use { store ->
            val budget=RunBudget(hardRounds=8,hardToolCalls=1,hardDurationMs=60_000)
            val parent=RunRequest(RunId("aggregate-parent"),RunSource.CHAT,RunMode.CHAT,HostId("native-android"),budget=budget)
            NexusExecutionRecorder(store,parent).start()
            val api=NexusRunApplicationApi(store,RuntimeRunRegistry()) {}
            val controller=AgentRunController()
            val guard=AgentRunBudgetGuard(budget)
            guard.beforeToolCall() // Parent already spent its allowance dispatching this child.
            var executed=false
            val executor=SubagentExecutor(
                conversationKey="aggregate-integration",parentController=controller,
                configProvider={_,_,_,_,_->ResolvedSubagentConfig(config,SubagentConfigSource.PRIMARY,SubagentConfigSource.PRIMARY)},
                capabilitiesProvider={AgentToolCapabilities(rootAvailable=false)},
                baseToolExecutor=AgentModelClient.ToolExecutor { executed=true;error("must not execute") },
                skillContext=SkillContext.EMPTY,memoryContext=AgentMemoryContext.DISABLED,mcpTools=JSONArray(),
                executionContext=null,onProgress={},authorizeChildTool={_,_->guard.beforeChildToolCall(controller);null},
                childExecutionFactory={operation,session,childBudget,child->api.openChild(parent.runId,operation,session,childBudget,child)},
                providerFactory={provider()},
            )
            val failure=assertThrows(AgentModelFailure::class.java) { executor.execute(1,
                AgentModelClient.ToolCall("spawn","subagent",JSONObject().put("subagent_type","explore")
                    .put("prompt","fixture").put("description","fixture").toString())) }
            assertEquals("RUN_BUDGET_EXCEEDED_TOOL_CALLS",failure.code)
            assertFalse(executed)
            assertFalse(controller.isUserCancelled)
            assertEquals(RunState.FAILED,store.recent().single { it.request.parentRunId==parent.runId }.state)
        }
    }

    private fun provider()=object:AgentProviderClient {
        override val id="controlled-fixture"
        override val capabilities=ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS,true,true,false,false,false,false)
        var round=0
        override fun complete(request: ProviderRequest,runController: AgentRunController,onEvent:(ProviderEvent)->Unit):ProviderResponse {
            round++
            onEvent(ProviderEvent.RequestStarted)
            val message=JSONObject().put("role","assistant").put("content",if(round==1) "" else "fixture analyzed")
                .put("finish_reason",if(round==1) "tool_calls" else "stop")
            if(round==1) message.put("tool_calls",JSONArray().put(JSONObject().put("id","read-1").put("type","function")
                .put("function",JSONObject().put("name","read_file").put("arguments","""{"path":"fixture.txt"}"""))))
            return ProviderResponse(message)
        }
    }
}
