package io.github.mangi.eta.agent.capability

import android.app.Application
import io.github.mangi.eta.agent.control.EtaAppControlConfirmation
import io.github.mangi.eta.agent.device.RootAccess
import io.github.mangi.eta.agent.device.RootAccessState
import io.github.mangi.eta.agent.device.RootAccessStatus
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.permission.AgentPermissionPolicy
import io.github.mangi.eta.agent.runtime.AgentRunPauseException
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentRuntimeWire
import io.github.mangi.eta.agent.runtime.AgentToolPolicy
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.model.AgentPermissionMode
import io.github.mangi.eta.fork.a2a.A2A_PROTOCOL_VERSION
import io.github.mangi.eta.fork.a2a.A2AAgentEntity
import io.github.mangi.eta.fork.a2a.A2ADatabase
import io.github.mangi.eta.fork.a2a.A2AAccessPolicyStore
import io.github.mangi.eta.fork.a2a.A2AInboundTaskEntity
import io.github.mangi.eta.fork.a2a.A2ATaskState
import io.github.mangi.eta.fork.a2a.A2AToolAccess
import io.github.mangi.eta.fork.a2a.A2A_JSON
import io.github.zhh_lgtm.nexus.application.api.CapabilityAuthorization
import io.github.zhh_lgtm.nexus.application.api.InMemoryCapabilityDirectory
import io.github.zhh_lgtm.nexus.kernel.api.EffectClass
import io.github.zhh_lgtm.nexus.kernel.api.HostId
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import io.github.zhh_lgtm.nexus.kernel.api.RunMode
import io.github.zhh_lgtm.nexus.kernel.api.RunRequest
import io.github.zhh_lgtm.nexus.kernel.api.RunSource
import io.github.zhh_lgtm.nexus.kernel.api.RunId
import io.github.zhh_lgtm.nexus.kernel.api.ReplayPolicy
import io.github.zhh_lgtm.nexus.kernel.api.ToolDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NexusExecutionGateTest {
    @Test fun forgedInvalidToolNamesProduceADenialInsteadOfCrashingTheRuntime() = runBlocking {
        for (name in listOf("_", "private tool", "x".repeat(129))) {
            val denied = NexusExecutionGate.authorize(context, request(), name, JSONObject(), AgentRunController(),
                System.currentTimeMillis() + 60_000) as CapabilityAuthorization.Denied
            assertEquals("INVALID_TOOL_ID", denied.code)
        }
        assertNull(EtaAppControlConfirmation.pending.value)
    }

    @Test fun unicodeLegacyRunIdsUseTheCanonicalGrantIdentity() = runBlocking {
        val result = NexusExecutionGate.authorize(context, request().copy(runId = "meeting-软件工程师-1"),
            "memory_get", JSONObject(), AgentRunController(), System.currentTimeMillis() + 60_000)
        assertEquals(CapabilityAuthorization.Allowed, result)
    }

    @Test fun planModeOnlyAllowsReadsAndPlanStateMutations() = runBlocking {
        val plan = planRequest()
        val denied = NexusExecutionGate.authorize(context, plan, "write_file",
            JSONObject().put("path", "report.txt").put("content", "body"),
            AgentRunController(), System.currentTimeMillis() + 60_000)
        assertEquals("PLAN_MODE_SIDE_EFFECT_DENIED", (denied as CapabilityAuthorization.Denied).code)
        assertNull(EtaAppControlConfirmation.pending.value)
        assertTrue(NexusExecutionGate.planModeAllows(RunMode.PLAN, "plan_manage", descriptor("plan_manage", EffectClass.REVERSIBLE_WRITE)))
        assertTrue(NexusExecutionGate.planModeAllows(RunMode.PLAN, "read_file", descriptor("read_file", EffectClass.READ)))
        assertFalse(NexusExecutionGate.planModeAllows(RunMode.PLAN, "terminal", descriptor("terminal", EffectClass.HIGH_RISK)))
        assertFalse(NexusExecutionGate.planModeAllows(RunMode.PLAN, "pkg_external_time", null))
        assertTrue(NexusExecutionGate.planModeAllows(RunMode.GOAL, "write_file", descriptor("write_file", EffectClass.REVERSIBLE_WRITE)))
    }

    @Test fun planningCanInspectButCannotStartAStoredGoal() {
        assertTrue(NexusToolEffectClassifier.isProvenReadOnlyCall("terminal", JSONObject().put("command", "/system/bin/id -u")))
        assertFalse(NexusToolEffectClassifier.isProvenReadOnlyCall("terminal", JSONObject().put("command", "/system/bin/id -u; touch project.txt")))
        assertFalse(NexusExecutionGate.planModeAllows(RunMode.PLAN, "goal_manage", descriptor("goal_manage", EffectClass.REVERSIBLE_WRITE),
            JSONObject().put("action", "transition").put("transition", "start_running")))
        assertFalse(NexusExecutionGate.planModeAllows(RunMode.PLAN, "goal_manage", descriptor("goal_manage", EffectClass.REVERSIBLE_WRITE),
            JSONObject().put("action", "increase_budget")))
    }

    @Test fun planModeKeepsTheBoundaryForHostClaimedReadsAcrossLinuxPackageAndA2A() = runBlocking {
        val plan = planRequest()
        val linuxHost = HostId("linux-fixture")
        val linuxRead = descriptor("read_file", EffectClass.READ).copy(hostKind = HostKind.LINUX)
        assertEquals(CapabilityAuthorization.Allowed, NexusExecutionGate.authorize(
            context, plan, "read_file",
            JSONObject().put("path", "/workspace/plan.md").put("environment", "linux"),
            AgentRunController(), System.currentTimeMillis() + 60_000,
            toolDescriptor = linuxRead, toolHostId = linuxHost,
        ))
        listOf(
            "terminal" to JSONObject().put("command", "rm -rf /workspace").put("environment", "linux"),
            "write_file" to JSONObject().put("path", "/workspace/report.md")
                .put("content", "body").put("environment", "linux"),
        ).forEach { (name, arguments) ->
            val forgedLocalHostRead = descriptor(name, EffectClass.READ).copy(hostKind = HostKind.LINUX)
            val denied = NexusExecutionGate.authorize(context, plan, name, arguments, AgentRunController(),
                System.currentTimeMillis() + 60_000, toolDescriptor = forgedLocalHostRead, toolHostId = linuxHost)
            assertEquals("PLAN_MODE_SIDE_EFFECT_DENIED", (denied as CapabilityAuthorization.Denied).code)
        }

        val packageName = "pkg_fixture_clock"
        val packageRead = ToolDescriptor(packageName, "package", "Clock", "Clock",
            "package://fixture/clock/input", effectClass = EffectClass.READ,
            replayPolicy = ReplayPolicy.AUTOMATIC, timeoutMs = 5_000, hostKind = HostKind.PLUGIN)
        val packageSchema = JSONArray().put(JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", packageName).put("parameters", JSONObject().put("type", "object"))))
        NexusRunHosts(InMemoryCapabilityDirectory(emptyList()), listOf(RunHostBinding(HostId("package-fixture"),
            listOf(packageRead), packageSchema, noopProvider()))).use { hosts ->
            val descriptor = hosts.describe(packageName)!!
            assertEquals(EffectClass.READ, descriptor.effectClass)
            assertEquals(HostKind.PLUGIN, descriptor.hostKind)
            val denied = NexusExecutionGate.authorize(context, plan, packageName, JSONObject(), AgentRunController(),
                System.currentTimeMillis() + 60_000, toolDescriptor = descriptor, toolHostId = hosts.hostId(packageName))
            assertEquals("PLAN_MODE_SIDE_EFFECT_DENIED", (denied as CapabilityAuthorization.Denied).code)
        }

        val peer = A2AAgentEntity("plan-peer", "Remote reports", "http://127.0.0.1:9/card/plan-peer",
            "http://127.0.0.1:9/a2a", null, A2A_PROTOCOL_VERSION, null, "MANUAL_ALLOWLIST",
            """{"description":"Remote reports"}""", true, 1, 1)
        try {
            NexusRunHosts(InMemoryCapabilityDirectory(emptyList()),
                A2ARunSnapshot(listOf(peer)).bindings(context)).use { hosts ->
                val status = A2ARunSnapshot.toolName(peer.id, "status")
                val descriptor = hosts.describe(status)!!
                assertEquals(EffectClass.READ, descriptor.effectClass)
                assertEquals(HostKind.A2A, descriptor.hostKind)
                val denied = NexusExecutionGate.authorize(context, plan, status,
                    JSONObject().put("local_task_id", "remote-task"), AgentRunController(),
                    System.currentTimeMillis() + 60_000, toolDescriptor = descriptor, toolHostId = hosts.hostId(status))
                assertEquals("PLAN_MODE_SIDE_EFFECT_DENIED", (denied as CapabilityAuthorization.Denied).code)
            }
        } finally {
            A2ADatabase.closeForTests()
        }
        assertNull(EtaAppControlConfirmation.pending.value)
    }

    @Test fun planModeAdmitsOnlyTheThreePlanManagementDraftsThroughTheGate() = runBlocking {
        val plan = planRequest()
        listOf(
            "plan_manage" to JSONObject().put("action", "status"),
            "goal_manage" to JSONObject().put("action", "status"),
            "notepad_manage" to JSONObject().put("action", "read"),
        ).forEach { (name, arguments) ->
            assertEquals(name, CapabilityAuthorization.Allowed, NexusExecutionGate.authorize(
                context, plan, name, arguments, AgentRunController(), System.currentTimeMillis() + 60_000,
            ))
        }
        assertNull(EtaAppControlConfirmation.pending.value)
    }

    @Test fun aChildRunBoundToPlanModeCannotWriteEvenWhenTheWireHandoffDoesNotSayPlan() = runBlocking {
        val childController = AgentRunController()
        childController.bindExecutionIdentity(RunRequest(
            runId = RunId("child-run"),
            source = RunSource.SUBAGENT,
            mode = RunMode.PLAN,
            hostId = HostId("native-android"),
            sessionId = null,
        ))
        val denied = NexusExecutionGate.authorize(context, request(), "write_file",
            JSONObject().put("path", "child.txt").put("content", "body"),
            childController, System.currentTimeMillis() + 60_000)
        assertEquals("PLAN_MODE_SIDE_EFFECT_DENIED", (denied as CapabilityAuthorization.Denied).code)
        assertEquals(CapabilityAuthorization.Allowed, NexusExecutionGate.authorize(
            context, request(), "read_file", JSONObject().put("path", "child.txt"),
            childController, System.currentTimeMillis() + 60_000,
        ))
    }

    private fun planRequest() = request().copy(handoff = AgentRuntimeWire.EntryHandoff(
        "plan-run", AgentRuntimeWire.AGENT_UI_HANDOFF_SOURCE,
        io.github.mangi.eta.agent.runtime.AgentUiHandoffPayload("plan-conversation", workflowMode = "PLAN").toJson(),
    ))

    private fun noopProvider() = object : io.github.mangi.eta.agent.capability.CapabilityToolProvider {
        override fun contains(toolName: String) = toolName.startsWith("pkg_fixture_")
        override fun close() = Unit
        override fun execute(toolCall: AgentModelClient.ToolCall) =
            AgentModelClient.ToolResult("{\"ok\":true}")
    }

    private fun descriptor(id: String, effect: EffectClass) = ToolDescriptor(
        id = id, namespace = "test", shortDescription = id, modelDescription = id,
        inputSchemaRef = "test://$id", effectClass = effect, replayPolicy = ReplayPolicy.AUTOMATIC,
        timeoutMs = 60_000, hostKind = HostKind.NATIVE_ANDROID,
    )

    private val context get() = RuntimeEnvironment.getApplication()
    private fun request() = AgentRuntimeWire.RunRequest(
        runId = "gate-test", prompt = "fixture", images = emptyList(),
        config = AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "fixture", systemPrompt = ""),
    )

    @Test fun expiredAndMissingRunGrantsAreDeniedBeforeApproval() = runBlocking {
        val controller = AgentRunController()
        val expired = NexusExecutionGate.authorize(context, request(), "terminal", JSONObject(), controller, 0)
        assertEquals("GRANT_EXPIRED", (expired as CapabilityAuthorization.Denied).code)
        val forbidden = NexusExecutionGate.authorize(context,
            request().copy(toolPolicy = AgentToolPolicy(allowedTools = emptyList())), "terminal", JSONObject(), controller,
            System.currentTimeMillis() + 60_000)
        assertEquals("TOOL_NOT_ALLOWED_BY_RUNTIME_POLICY", (forbidden as CapabilityAuthorization.Denied).code)
        assertNull(EtaAppControlConfirmation.pending.value)
    }

    @Test fun modelSuppliedConfirmationCannotBypassLocalApproval() = runBlocking {
        val forged = NexusExecutionGate.authorize(context, request(), "terminal",
            JSONObject().put("command", "fixture-command").put("confirmed", true),
            AgentRunController(), System.currentTimeMillis() + 60_000)
        assertEquals("INVALID_TOOL_ARGUMENT", (forged as CapabilityAuthorization.Denied).code)
        assertNull(EtaAppControlConfirmation.pending.value)
        val execution = async(Dispatchers.Default) {
            NexusExecutionGate.authorize(context, request(), "terminal",
                JSONObject().put("command", "fixture-command"),
                AgentRunController(), System.currentTimeMillis() + 60_000)
        }
        try {
            val approval = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
            assertEquals("runtime_tool:terminal", approval.capabilityId)
            assertTrue(EtaAppControlConfirmation.cancel(approval.id))
            val denied = withTimeout(3_000) { execution.await() } as CapabilityAuthorization.Denied
            assertEquals("LOCAL_CONFIRMATION_DENIED", denied.code)
        } finally {
            execution.cancel()
            EtaAppControlConfirmation.pending.value?.let { EtaAppControlConfirmation.cancel(it.id) }
        }
    }

    @Test fun nativeAndExternalToolsValidatePublishedParametersBeforeAnyApproval() = runBlocking {
        val invalid = NexusExecutionGate.authorize(context, request(), "write_file",
            JSONObject().put("path", JSONObject.NULL).put("content", "body"), AgentRunController(), System.currentTimeMillis() + 60_000)
        assertEquals("INVALID_TOOL_ARGUMENT", (invalid as CapabilityAuthorization.Denied).code)
        val schema = JSONObject().put("type", "object").put("required", org.json.JSONArray().put("count"))
            .put("properties", JSONObject().put("count", JSONObject().put("type", "integer").put("maximum", 10)))
        val external = NexusExecutionGate.authorize(context, request(), "mcp_fixture",
            JSONObject().put("count", 11), AgentRunController(), System.currentTimeMillis() + 60_000, inputSchema = schema)
        assertEquals("INVALID_TOOL_ARGUMENT", (external as CapabilityAuthorization.Denied).code)
        assertNull(EtaAppControlConfirmation.pending.value)
    }

    @Test fun cancellingRunAlsoClosesItsPendingApproval() = runBlocking {
        val controller = AgentRunController()
        val execution = async(Dispatchers.Default) {
            runCatching { NexusExecutionGate.authorize(context, request(), "terminal", JSONObject(), controller,
                System.currentTimeMillis() + 60_000) }
        }
        try {
            withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
            controller.cancel()
            val outcome = runCatching { withTimeout(3_000) { execution.await() } }
            assertTrue(outcome.isFailure || outcome.getOrNull()?.isFailure == true)
            assertNull(EtaAppControlConfirmation.pending.value)
        } finally {
            execution.cancel()
            EtaAppControlConfirmation.pending.value?.let { EtaAppControlConfirmation.cancel(it.id) }
        }
    }

    @Test fun a2aCannotBorrowTheLocalHighRiskApproval() = runBlocking {
        val remote = request().copy(handoff = AgentRuntimeWire.EntryHandoff("peer", "a2a", "context"))
        val denied = NexusExecutionGate.authorize(context, remote, "terminal", JSONObject().put("confirmed", true),
            AgentRunController(), System.currentTimeMillis() + 60_000) as CapabilityAuthorization.Denied
        assertEquals("REMOTE_HIGH_RISK_DENIED", denied.code)
        assertNull(EtaAppControlConfirmation.pending.value)
    }

    @Test fun etaControlReadActionsStayLowRiskAndWritesDelegateToInnerControlPlane() = runBlocking {
        val controller = AgentRunController()
        val descriptor = descriptor("eta_control", EffectClass.HIGH_RISK)
        val read = NexusExecutionGate.authorize(
            context, request(), "eta_control", JSONObject().put("action", "list"), controller,
            System.currentTimeMillis() + 60_000, toolDescriptor = descriptor,
        )
        assertEquals(CapabilityAuthorization.Allowed, read)
        assertNull(EtaAppControlConfirmation.pending.value)

        val execute = NexusExecutionGate.authorize(
            context, request(), "eta_control",
            JSONObject().put("action", "execute").put("id", "navigation.capabilities")
                .put("parameters", JSONObject()),
            controller, System.currentTimeMillis() + 60_000, toolDescriptor = descriptor,
        )
        assertEquals(CapabilityAuthorization.Allowed, execute)
        assertNull(EtaAppControlConfirmation.pending.value)
    }

    @Test fun rootIdentityFileWritesWaitForLocalApprovalInSmartAndManualModesOnly() = runBlocking {
        SettingsDataStore.init(context)
        val selectedMode = AgentPermissionPolicy.mode()
        val rootState = ReflectionHelpers.getStaticField<MutableStateFlow<RootAccessState>>(
            RootAccess::class.java, "mutableState",
        )
        val originalRoot = rootState.value
        try {
            rootState.value = RootAccessState(RootAccessStatus.GRANTED, suPresent = true)
            listOf(AgentPermissionMode.SMART_APPROVAL, AgentPermissionMode.MANUAL_APPROVAL).forEach { mode ->
                AgentPermissionPolicy.setMode(mode)
                val controller = AgentRunController()
                val execution = async(Dispatchers.Default) { authorizeFixtureWrite(controller) }
                try {
                    val approval = withTimeout(3_000) { EtaAppControlConfirmation.pending.filterNotNull().first() }
                    assertEquals("runtime_tool:write_file", approval.capabilityId)
                    assertTrue(EtaAppControlConfirmation.cancel(approval.id))
                    val denied = withTimeout(3_000) { execution.await() } as CapabilityAuthorization.Denied
                    assertEquals("LOCAL_CONFIRMATION_DENIED", denied.code)
                } finally {
                    execution.cancel()
                    EtaAppControlConfirmation.pending.value?.let { EtaAppControlConfirmation.cancel(it.id) }
                }
            }

            AgentPermissionPolicy.setMode(AgentPermissionMode.SMART_APPROVAL)
            rootState.value = RootAccessState()
            assertEquals(CapabilityAuthorization.Allowed, authorizeFixtureWrite(AgentRunController()))
            assertNull(EtaAppControlConfirmation.pending.value)

            rootState.value = RootAccessState(RootAccessStatus.GRANTED, suPresent = true)
            AgentPermissionPolicy.setMode(AgentPermissionMode.FULL_ACCESS)
            assertEquals(CapabilityAuthorization.Allowed, authorizeFixtureWrite(AgentRunController()))
            assertNull(EtaAppControlConfirmation.pending.value)
        } finally {
            rootState.value = originalRoot
            AgentPermissionPolicy.setMode(selectedMode)
            EtaAppControlConfirmation.pending.value?.let { EtaAppControlConfirmation.cancel(it.id) }
        }
    }

    private suspend fun authorizeFixtureWrite(controller: AgentRunController) = NexusExecutionGate.authorize(
        context, request(), "write_file",
        JSONObject().put("path", "/data/local/tmp/fixture.txt").put("content", "fixture"),
        controller, System.currentTimeMillis() + 60_000,
    )

    @Test fun scopedA2AGrantIsConsumedThroughTheRealGateApprovalPath() = runBlocking {
        SettingsDataStore.init(context)
        val selectedMode = AgentPermissionPolicy.mode()
        val rootState = ReflectionHelpers.getStaticField<MutableStateFlow<RootAccessState>>(
            RootAccess::class.java, "mutableState",
        )
        val originalRoot = rootState.value
        val peerId = A2AAccessPolicyStore.tokenPeerId("gate-fixture-token")
        val accessStore = A2AAccessPolicyStore(context)
        val originalAccess = A2AToolAccess.custom(peerId, setOf("write_file", "read_file"))
        val dao = A2ADatabase.get(context).dao()
        val acceptedTask = A2AInboundTaskEntity(
            taskId = A2A_TASK_ID,
            requestMessageId = "gate-fixture-message",
            contextId = "a2a-context",
            prompt = "fixture",
            state = A2ATaskState.WORKING.name,
            statusMessage = null,
            answer = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            continuationCount = 1,
            accessJson = A2A_JSON.encodeToString(originalAccess),
        )
        // Production inbound execution always persists the accepted task and token-bound grant
        // before entering Runtime. A forged handoff alone cannot reach the approval path.
        accessStore.setCustomTools(peerId, originalAccess.allowedTools)
        dao.upsertInboundTask(acceptedTask)
        try {
            rootState.value = RootAccessState(RootAccessStatus.GRANTED, suPresent = true)
            AgentPermissionPolicy.setMode(AgentPermissionMode.SMART_APPROVAL)
            val arguments = JSONObject().put("path", "/data/local/tmp/fixture.txt").put("content", "fixture")

            // Proves this fixture really reaches the approval gate before the grant is consumed.
            val withoutGrant = runCatching {
                NexusExecutionGate.authorize(
                    context, a2aRequest(), "write_file", arguments, a2aController(), System.currentTimeMillis() + 60_000,
                )
            }.exceptionOrNull()
            assertTrue(withoutGrant is AgentRunPauseException)
            assertNull(EtaAppControlConfirmation.pending.value)

            val approved = a2aController().apply {
                bindLocalA2AApproval(a2aGrant(arguments), expectedTaskId = A2A_TASK_ID, expectedContinuationCount = 1)
            }
            assertEquals(CapabilityAuthorization.Allowed, NexusExecutionGate.authorize(
                context, a2aRequest(), "write_file", arguments, approved, System.currentTimeMillis() + 60_000,
            ))
            assertNull(EtaAppControlConfirmation.pending.value)

            // The grant is one-shot: replaying the same gated tool must pause for local approval.
            val replay = runCatching {
                NexusExecutionGate.authorize(
                    context, a2aRequest(), "write_file", arguments, approved, System.currentTimeMillis() + 60_000,
                )
            }.exceptionOrNull()
            assertTrue(replay is AgentRunPauseException)
            assertNull(EtaAppControlConfirmation.pending.value)

            val mismatchedEffect = a2aController().apply {
                bindLocalA2AApproval(
                    a2aGrant(arguments, allowedEffects = setOf("write_file=GUI_ACTION", "read_file=READ")),
                    expectedTaskId = A2A_TASK_ID,
                    expectedContinuationCount = 1,
                )
            }
            val denied = runCatching {
                NexusExecutionGate.authorize(
                    context, a2aRequest(), "write_file", arguments, mismatchedEffect, System.currentTimeMillis() + 60_000,
                )
            }.exceptionOrNull()
            assertTrue(denied is AgentRunPauseException)
            assertNull(EtaAppControlConfirmation.pending.value)

            // A valid local one-shot approval never overrides revocation of the current token
            // grant, nor does it allow a task that has stopped to issue another effectful call.
            accessStore.setCustomTools(peerId, setOf("read_file"))
            val revokedApproval = a2aController().apply {
                bindLocalA2AApproval(a2aGrant(arguments), expectedTaskId = A2A_TASK_ID, expectedContinuationCount = 1)
            }
            val revoked = NexusExecutionGate.authorize(
                context, a2aRequest(), "write_file", arguments, revokedApproval, System.currentTimeMillis() + 60_000,
            ) as CapabilityAuthorization.Denied
            assertEquals("A2A_CURRENT_GRANT_DENIED", revoked.code)
            accessStore.setCustomTools(peerId, originalAccess.allowedTools)
            dao.upsertInboundTask(acceptedTask.copy(state = A2ATaskState.CANCELED.name))
            val stopped = NexusExecutionGate.authorize(
                context, a2aRequest(), "write_file", arguments, revokedApproval, System.currentTimeMillis() + 60_000,
            ) as CapabilityAuthorization.Denied
            assertEquals("A2A_CURRENT_GRANT_DENIED", stopped.code)
        } finally {
            accessStore.setCustomTools(peerId, emptySet())
            dao.upsertInboundTask(acceptedTask.copy(state = A2ATaskState.CANCELED.name))
            rootState.value = originalRoot
            AgentPermissionPolicy.setMode(selectedMode)
            EtaAppControlConfirmation.pending.value?.let { EtaAppControlConfirmation.cancel(it.id) }
        }
    }

    private fun a2aController() = AgentRunController().apply {
        bindRecoverySurface(RunSource.A2A_INBOUND)
        bindExecutionIdentity(RunRequest(RunId(A2A_TASK_ID), RunSource.A2A_INBOUND, RunMode.A2A, HostId("native-android")))
    }

    private fun a2aRequest() = request().copy(
        runId = A2A_TASK_ID,
        toolPolicy = AgentToolPolicy(allowedTools = listOf("write_file", "read_file")),
        handoff = AgentRuntimeWire.EntryHandoff(A2A_TASK_ID, "a2a", "a2a-context"),
        a2aContinuationCount = 1,
    )

    private fun a2aGrant(
        arguments: JSONObject,
        allowedEffects: Set<String> = setOf("write_file=REVERSIBLE_WRITE", "read_file=READ"),
    ) = AgentRuntimeWire.A2ALocalApprovalGrant(
        taskId = A2A_TASK_ID,
        messageId = "local-approval-slot",
        continuationCount = 1,
        allowedTools = setOf("write_file", "read_file"),
        allowedToolEffects = allowedEffects,
        originalGrantFingerprint = "original",
        currentGrantFingerprint = "current",
        // Match the exact semantic call identity produced by the real approval pause. A bare
        // tool/effect allowlist is deliberately insufficient to mint a one-shot approval.
        pendingTool = "write_file",
        pendingEffect = EffectClass.REVERSIBLE_WRITE.name,
        pendingArgumentsDigest = A2AToolCallIdentity.canonicalArgumentsDigest(arguments),
    )

    private companion object {
        const val A2A_TASK_ID = "a2a-grant-run"
    }
}
