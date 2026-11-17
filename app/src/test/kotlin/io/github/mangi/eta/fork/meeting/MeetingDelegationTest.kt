package io.github.mangi.eta.fork.meeting

import android.app.Application
import io.github.mangi.eta.agent.model.AgentFileReference
import io.github.mangi.eta.agent.model.AgentFileReferenceKind
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.runtime.*
import io.github.mangi.eta.config.Prefs
import io.github.mangi.eta.data.datastore.SettingsDataStore
import io.github.mangi.eta.data.db.EtaDatabase
import io.github.mangi.eta.data.model.Model
import io.github.mangi.eta.data.model.OpenAiCompatibleProviderSetting
import io.github.mangi.eta.data.repository.ConversationAttachments
import io.github.mangi.eta.data.repository.ProviderRepository
import io.github.mangi.eta.data.repository.ResolvedSubagentConfig
import io.github.mangi.eta.data.repository.SubagentConfigSource
import io.github.mangi.eta.fork.tasks.*
import io.github.mangi.eta.ui.model.PendingFileReferenceUi
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Executes the actual planner -> durable task -> participant -> summary orchestration. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MeetingDelegationTest {
    @Test fun queueFailureRollsBackOnlyNewRecruitAndItsAudit() = runBlocking(Dispatchers.IO) {
        val room = room()
        val db = MeetingDatabase.get(context)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_planned_queue BEFORE INSERT ON meeting_events
            WHEN NEW.type = 'task_queued' BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        try {
            val recruit = PlannedMeetingParticipant("expert-a", "expert", "role", "p", "m", "identity", "reason")
            try { MeetingRepository.commitPlannedDispatch(context, room, listOf(recruit),
                listOf(PlannedMeetingTask("expert-a", "work", emptyList()))); fail("expected failure") }
            catch (_: Exception) { }
            val snapshot = MeetingRepository.snapshot(context, room).first()!!
            assertEquals(setOf("general", "explore", "coder"), snapshot.participants.map { it.profileName }.toSet())
            assertTrue(snapshot.tasks.isEmpty())
            assertNull(db.meetingDao().latestEvent(room, "dynamic_agent_created"))
            assertNull(db.meetingDao().latestEvent(room, "participant_model_selected"))
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_planned_queue") }
    }

    @Test fun singleRecruitAndQueueCommitTogether() = runBlocking(Dispatchers.IO) {
        val room = room(withWorkers = false)
        val recruit = PlannedMeetingParticipant("expert-a", "expert", "role", "p", "m", "identity", "reason")
        val result = MeetingRepository.commitPlannedDispatch(context, room, listOf(recruit),
            listOf(PlannedMeetingTask("expert-a", "work", emptyList())))
        assertEquals("expert-a", result.participants.single().profileName)
        assertEquals("expert-a", result.tasks.single().participantProfile)
        assertEquals(1, MeetingRepository.snapshot(context, room).first()!!.tasks.size)
    }
    @Test fun recruitmentRollsBackAllMembersWhenSecondAuditWriteFails() = runBlocking(Dispatchers.IO) {
        val room = room(withWorkers = false)
        val db = MeetingDatabase.get(context)
        db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER fail_second_recruit BEFORE INSERT ON meeting_events
            WHEN NEW.type = 'participant_model_selected' AND NEW.payload_json LIKE '%expert-b%'
            BEGIN SELECT RAISE(ABORT, 'fixture failure'); END""")
        val drafts = listOf("expert-a", "expert-b").map { PlannedMeetingParticipant(it, it, "role", "p", "m", "identity", "reason") }
        try {
            try { MeetingRepository.createPlannedParticipants(context, room, drafts); fail("expected failure") }
            catch (_: Exception) { }
            val snapshot = MeetingRepository.snapshot(context, room).first()!!
            assertTrue(snapshot.participants.none { it.profileName.startsWith("expert-") })
            assertNull(db.meetingDao().latestEvent(room, "dynamic_agent_created"))
        } finally { db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_second_recruit") }
    }

    @Test fun invalidTaskGraphCannotPartiallyQueueTasks() = runBlocking(Dispatchers.IO) {
        val room = room()
        val plans = listOf(PlannedMeetingTask("explore", "first", listOf("coder")),
            PlannedMeetingTask("coder", "second", listOf("explore")))
        try { MeetingRepository.createPlannedTasks(context, room, plans); fail("expected invalid dependency graph") }
        catch (_: IllegalArgumentException) { }
        assertTrue(MeetingRepository.snapshot(context, room).first()!!.tasks.isEmpty())
    }

    @Test fun dependencyReceivesOnlyDeclaredPriorResultAndFailureBlocksExecution() = runBlocking(Dispatchers.IO) {
        val room = room()
        val plan = """{"tasks":[{"participant":"explore","instruction":"investigate"},
            {"participant":"coder","instruction":"implement","depends_on":["explore"]}]}"""
        val backend = Backend(planning = { plan })
        assertEquals(MeetingTurnOutcome.SUCCEEDED, MeetingRuntime.runTurnOutcome(context, room, "goal", runtime = backend))
        val coder = backend.requests.single { it.promptScope.meetingParticipant == "coder" }
        assertTrue(coder.prompt.contains("explore completed assigned work"))
        val secondRoom = room()
        val failed = Backend(planning = { plan }, failingParticipant = "explore")
        assertEquals(MeetingTurnOutcome.HANDLED, MeetingRuntime.runTurnOutcome(context, secondRoom, "goal", runtime = failed))
        assertTrue(failed.requests.none { it.promptScope.meetingParticipant == "coder" })
        assertEquals(MeetingTaskStatus.CANCELLED, MeetingRepository.snapshot(context, secondRoom).first()!!.tasks.single { it.participantProfile == "coder" }.status)
    }
    @Test fun hostOnlyWorkflowRecruitsBeforeStagesAndDoesNotExecuteTheRecruitmentAssignments() = runBlocking(Dispatchers.IO) {
        ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(id = "workflow-provider", name = "Fixture",
            baseUrl = "https://fixture.invalid/v1", apiKey = "fixture", models = listOf(
                Model(id = "workflow-model", modelId = "workflow-model", displayName = "Fixture", toolCall = true))))
        val room = MeetingRepository.createRoom(context, "Host only workflow",
            MeetingParticipantDraft("general", "主持人", "主持"), emptyList(), templateId = "M01")
        val backend = Backend(planning = { """{"create_agents":[
            {"profile_name":"idea-a","display_name":"方案专家甲","summary":"比较方案"},
            {"profile_name":"idea-b","display_name":"方案专家乙","summary":"独立评估"}],
            "tasks":[{"participant":"idea-a","instruction":"RECRUITMENT_ONLY_DO_NOT_EXECUTE"},
            {"participant":"idea-b","instruction":"RECRUITMENT_ONLY_DO_NOT_EXECUTE"}]}""" })
        assertEquals(MeetingTurnOutcome.SUCCEEDED, MeetingRuntime.runTurnOutcome(context, room, "比较两种方案", runtime = backend))
        val saved = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(2, saved.participants.count { !it.isHost && it.enabled })
        assertEquals(1, backend.plans.get())
        assertTrue(saved.tasks.isNotEmpty())
        assertTrue(saved.tasks.none { it.instruction == "RECRUITMENT_ONLY_DO_NOT_EXECUTE" })
        assertTrue(backend.requests.any { it.promptScope.meetingParticipant == "idea-a" })
        assertTrue(backend.requests.any { it.promptScope.meetingParticipant == "idea-b" })
    }

    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun setup() {
        Prefs.initLocal(context)
        EtaDatabase.closeForTests()
        context.deleteDatabase("eta.db")
        SettingsDataStore.init(context)
        ProviderRepository.init(context)
        MeetingDatabase.closeForTests()
        context.deleteDatabase("eta_meeting.db")
        MeetingSettingsStore.get(context).update { MeetingSettings() }
    }

    @After fun close() { MeetingDatabase.closeForTests(); EtaDatabase.closeForTests() }

    private suspend fun room(withWorkers: Boolean = true, hostOverride: MeetingHostOverride? = null): String = MeetingRepository.createRoom(context,
        "Delegation", MeetingParticipantDraft("general", "主持人", "负责分工和汇总"),
        if (withWorkers) listOf(MeetingParticipantDraft("explore", "研究员", "查证资料"),
            MeetingParticipantDraft("coder", "工程师", "实现方案")) else emptyList(), hostOverride = hostOverride)

    private open class Backend(val planning: (Int) -> String, private val failingParticipant: String? = null) : MeetingParticipantRuntime {
        val requests = ConcurrentLinkedQueue<AgentRuntimeWire.RunRequest>()
        val plans = AtomicInteger()
        val estimates = ConcurrentLinkedQueue<Int>()
        override suspend fun configuration(
            participant: MeetingParticipantEntity,
            candidateRoutingEnabled: Boolean,
            requiresTools: Boolean,
            requiresVision: Boolean,
            estimatedInputTokens: Int,
        ): ResolvedSubagentConfig {
            estimates.add(estimatedInputTokens)
            return ResolvedSubagentConfig(
                AgentModelClient.ModelConfig(baseUrl = "https://fixture.invalid", apiKey = "fixture", model = "fixture",
                    systemPrompt = "Complete everything yourself", supportsTools = true, terminalTools = true,
                    browserTools = true, deviceDirectTools = true, subagentTools = true, hostedWebSearchEnabled = true,
                    extraBodyJson = """{"tools":[{"type":"web_search"}]}"""),
                SubagentConfigSource.PRIMARY, SubagentConfigSource.PRIMARY,
                toolsOverride = listOf("read_file", "web_search"),
            )
        }

        override fun execute(context: android.content.Context, request: AgentRuntimeWire.RunRequest,
            onEvent: (AgentEvent) -> Unit): AgentRuntimeWire.RunResult {
            requests.add(request)
            if (request.promptScope.meetingParticipant == failingParticipant) {
                throw IllegalStateException("fixture worker failure")
            }
            val result = when {
                request.config.systemPrompt.contains("分工主持人") -> planning(plans.incrementAndGet())
                request.config.systemPrompt.contains("汇总主持人") -> "已汇总参与者结果"
                else -> {
                    onEvent(AgentEvent.ToolStarted(1, "read", "read_file", "{}"))
                    onEvent(AgentEvent.ToolFinished(1, "read", "read_file", "fixture", 0, 0, true))
                    "${request.promptScope.meetingParticipant.ifBlank { "host" }} completed assigned work"
                }
            }
            return AgentRuntimeWire.RunResult(request.runId, true, result)
        }
    }

    @Test fun explicitlyAddressedHostCanChooseToWorkAlone() = runBlocking(Dispatchers.IO) {
        val room = room(withWorkers = false)
        val backend = Backend(planning = { """{"mode":"solo","tasks":[],"create_agents":[]}""" })
        assertEquals(MeetingTurnOutcome.SUCCEEDED,
            MeetingRuntime.runTurnOutcome(context, room, "@general 回答这个问题", runtime = backend))
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(MeetingTaskStatus.COMPLETED, snapshot.tasks.single().status)
        assertEquals("general", snapshot.tasks.single().participantProfile)
        assertEquals(1, snapshot.participants.size)
        assertTrue(snapshot.messages.any { it.authorType == MeetingAuthorType.HOST && it.content.contains("completed assigned work") })
        assertEquals(2, backend.requests.size)
        val work = backend.requests.single { it.promptScope.stage == io.github.mangi.eta.agent.prompt.PromptStage.MEETING_WORK }
        assertTrue(work.config.systemPrompt.contains("直接回答用户"))
        assertFalse(work.config.systemPrompt.contains("输出将交给主持人汇总"))
        assertTrue(work.prompt.contains("直接向用户输出"))
    }

    @Test fun soloHostFailureTerminatesItsTask() = runBlocking(Dispatchers.IO) {
        val room = room(withWorkers = false)
        val backend = object : Backend(planning = { """{"mode":"solo","tasks":[]}""" }) {
            override fun execute(context: android.content.Context, request: AgentRuntimeWire.RunRequest,
                onEvent: (AgentEvent) -> Unit): AgentRuntimeWire.RunResult {
                if (!request.config.systemPrompt.contains("分工主持人")) throw IllegalStateException("host failure")
                return super.execute(context, request, onEvent)
            }
        }
        assertEquals(MeetingTurnOutcome.PARTIAL, MeetingRuntime.runTurnOutcome(context, room, "问题", runtime = backend))
        assertEquals(MeetingTaskStatus.FAILED, MeetingRepository.snapshot(context, room).first()!!.tasks.single().status)
    }

    @Test fun completionIsAcknowledgedOnlyAfterItsMeetingResultIsDurable() = runBlocking(Dispatchers.IO) {
        val room = room()
        val acknowledgements = ConcurrentLinkedQueue<String>()
        val backend = object : Backend(planning = { """{"tasks":[{"participant":"explore","instruction":"查证资料"}]}""" }) {
            override fun acknowledge(context: android.content.Context, runId: String): Boolean {
                val request = requests.single { it.runId == runId }
                runBlocking {
                    val dao = MeetingDatabase.get(context).meetingDao()
                    val current = MeetingRepository.snapshot(context, room).first()!!
                    if (request.config.systemPrompt.contains("分工主持人")) {
                        assertEquals("general", JSONObject(dao.latestEvent(room, "planning_result:$runId")!!.payloadJson).getString("profile"))
                    } else {
                        val committed = dao.latestEvent(room, "stream_result:$runId")!!
                        val messageId = JSONObject(committed.payloadJson).getString("message_id")
                        assertNotNull(dao.message(messageId))
                        if (!request.promptScope.meetingHost) {
                            assertEquals(MeetingTaskStatus.COMPLETED, current.tasks.single { it.resultMessageId == messageId }.status)
                        }
                    }
                }
                acknowledgements.add(runId)
                return true
            }
        }
        assertTrue(MeetingRuntime.runTurn(context, room, "核实并汇总", runtime = backend))
        assertEquals(3, backend.requests.size)
        assertEquals(backend.requests.map { it.runId }.toSet(), acknowledgements.toSet())
    }

    @Test fun roomLocalHostResponsibilitiesParticipateWithoutBypassingStageRestrictions() = runBlocking(Dispatchers.IO) {
        val room = room(hostOverride = MeetingHostOverride("临时审查主持", "本会议按独立证据比较分歧，明确列出未决项。"))
        val backend = Backend(planning = { """{"tasks":[{"participant":"explore","instruction":"核实资料"}]}""" })
        assertTrue(MeetingRuntime.runTurn(context, room, "核实并汇总", runtime = backend))
        val hostRequests = backend.requests.filter { it.promptScope.meetingHost }
        assertEquals(2, hostRequests.size)
        hostRequests.forEach {
            assertEquals("general", it.promptScope.meetingParticipant)
            assertTrue(it.config.systemPrompt.contains("本会议按独立证据比较分歧"))
            assertTrue(it.config.systemPrompt.contains("meeting_stage_contract"))
            assertFalse(it.toolPolicy.isAllowed("read_file"))
            assertFalse(it.toolPolicy.isAllowed("subagent"))
        }
        assertEquals(setOf(io.github.mangi.eta.agent.prompt.PromptStage.MEETING_PLANNING,
            io.github.mangi.eta.agent.prompt.PromptStage.MEETING_SUMMARY), hostRequests.map { it.promptScope.stage }.toSet())
        assertFalse(backend.requests.first { !it.promptScope.meetingHost }.config.systemPrompt.contains("本会议按独立证据比较分歧"))
    }

    @Test fun failedMeetingResultWriteNeverAcknowledgesTheRuntimeResult() = runBlocking(Dispatchers.IO) {
        val room = room()
        val acknowledgements = ConcurrentLinkedQueue<String>()
        val backend = object : Backend(planning = { error("direct mention must not plan") }) {
            override fun acknowledge(context: android.content.Context, runId: String): Boolean {
                acknowledgements.add(runId)
                return true
            }
        }
        MeetingDatabase.get(context).openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_member_result BEFORE INSERT ON meeting_messages
            WHEN NEW.author_type = 'agent' BEGIN SELECT RAISE(ABORT, 'fixture result save failed'); END
        """.trimIndent())
        assertTrue(MeetingRuntime.runTurn(context, room, "@explore 核实资料", runtime = backend))
        assertEquals(1, backend.requests.size)
        assertTrue(acknowledgements.isEmpty())
        assertEquals(MeetingTaskStatus.FAILED, MeetingRepository.snapshot(context, room).first()!!.tasks.single().status)
    }

    @Test fun hostCannotPerformWorkWhilePlanningOrSummarizingAndActuallyDispatchesBothParticipants() = runBlocking(Dispatchers.IO) {
        val room = room()
        val backend = Backend(planning = { """说明 {"reasoning":"not a plan"}
            ```json
            {"tasks":[{"participant":"EXPLORE","instruction":"查证三项事实，输出来源与结论"},
                      {"participant":"coder","instruction":"设计实现步骤，输出清单和风险"}]}
            ```""" })
        val files = ConversationAttachments(files = listOf(PendingFileReferenceUi("brief",
            AgentFileReference("Brief", "/storage/emulated/0/brief.txt", AgentFileReferenceKind.File))))
        assertTrue(MeetingRuntime.runTurn(context, room, "分析需求并实现方案", attachments = files,
            toolPolicy = AgentToolPolicy(allowedTools = listOf("read_file")), runtime = backend))
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(setOf("explore", "coder"), snapshot.tasks.map { it.participantProfile }.toSet())
        assertTrue(snapshot.tasks.all { it.status == MeetingTaskStatus.COMPLETED })
        assertEquals(1, backend.plans.get())
        val hostCalls = backend.requests.filter { it.promptScope.meetingHost }
        assertEquals(2, hostCalls.size)
        hostCalls.forEach {
            assertFalse(it.config.supportsTools)
            assertFalse(it.config.hostedWebSearchEnabled)
            assertFalse(it.toolPolicy.isAllowed("read_file"))
            assertFalse(it.toolPolicy.isAllowed("subagent"))
            assertEquals("", it.config.extraBodyJson)
            assertEquals(io.github.zhh_lgtm.nexus.kernel.api.RunSource.MEETING_HOST, NexusRunMapper.source(it))
            assertEquals("general", it.promptScope.meetingParticipant)
            assertTrue(it.config.systemPrompt.contains("负责分工和汇总"))
        }
        val workCalls = backend.requests.filter { !it.promptScope.meetingHost }
        assertEquals(2, workCalls.size)
        workCalls.forEach {
            assertTrue(it.toolPolicy.isAllowed("read_file"))
            assertFalse(it.toolPolicy.isAllowed("web_search"))
            assertTrue(it.prompt.contains("/storage/emulated/0/brief.txt"))
            assertFalse(it.prompt.contains("分析需求并实现方案"))
        }
        assertTrue(snapshot.messages.any { it.authorType == MeetingAuthorType.HOST && it.content.contains("已汇总") })
        assertEquals(backend.requests.size, backend.estimates.size)
        assertTrue(backend.estimates.all { it > 0 })
    }

    @Test fun malformedPlanGetsOneRepairAndNeverFallsBackToTheHostDoingTheTask() = runBlocking(Dispatchers.IO) {
        val room = room()
        val backend = Backend(planning = { attempt -> if (attempt == 1) "我会自己完成全部工作" else
            """{"tasks":[{"participant":"explore","instruction":"核实资料，输出三项结论"}]}""" })
        assertTrue(MeetingRuntime.runTurn(context, room, "核实资料", runtime = backend))
        assertEquals(2, backend.plans.get())
        val tasks = MeetingRepository.snapshot(context, room).first()!!.tasks
        assertEquals(listOf("explore"), tasks.map { it.participantProfile })
        assertEquals(4, backend.requests.size) // Two plans, one worker, one summary.
        val repair = backend.requests.filter { it.config.systemPrompt.contains("分工主持人") }[1]
        assertTrue(repair.prompt.contains("未返回包含有效 participant 和 instruction"))
        assertTrue(repair.prompt.contains("已启用的非主持 profile_name：explore, coder"))
    }

    @Test fun hostAuthenticationFailureIsActionableAndNeverExposesProviderRequestDetails() = runBlocking(Dispatchers.IO) {
        val room = room()
        val secret = "sk-fixture-secret"
        val backend = object : Backend(planning = { error("must not plan") }) {
            override fun execute(context: android.content.Context, request: AgentRuntimeWire.RunRequest,
                onEvent: (AgentEvent) -> Unit): AgentRuntimeWire.RunResult {
                requests.add(request)
                return AgentRuntimeWire.RunResult(request.runId, false, "",
                    error = "HTTP 401 Authorization: Bearer $secret https://fixture.invalid/private request_body=PRIVATE_PROMPT")
            }
        }
        assertEquals(MeetingTurnOutcome.REJECTED,
            MeetingRuntime.runTurnOutcome(context, room, "查证资料", runtime = backend))
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(1, backend.requests.size)
        assertTrue(snapshot.tasks.isEmpty())
        assertEquals(MeetingRoomStatus.FAILED, snapshot.room.status)
        val failure = snapshot.messages.last().content
        assertTrue(failure.contains("鉴权或访问权限"))
        assertTrue(failure.contains("API 密钥"))
        assertFalse(failure.contains("未生成有效分工"))
        val diagnostic = MeetingDatabase.get(context).meetingDao().latestEvent(room, "planning_failed")!!.payloadJson
        assertEquals("authentication_failed", JSONObject(diagnostic).getString("code"))
        listOf(failure, diagnostic).forEach {
            assertFalse(it.contains(secret))
            assertFalse(it.contains("PRIVATE_PROMPT"))
            assertFalse(it.contains("fixture.invalid"))
        }
        assertTrue(backend.requests.all { !it.config.supportsTools && !it.toolPolicy.isAllowed("read_file") })
    }

    @Test fun hostConfigurationAndRuntimeExceptionsAreFailuresInsteadOfEmptyPlans() = runBlocking(Dispatchers.IO) {
        for (configurationFails in listOf(true, false)) {
            val room = room()
            val backend = object : Backend(planning = { error("must not plan") }) {
                override suspend fun configuration(participant: MeetingParticipantEntity, candidateRoutingEnabled: Boolean,
                    requiresTools: Boolean, requiresVision: Boolean, estimatedInputTokens: Int): ResolvedSubagentConfig {
                    if (configurationFails) error("尚未配置可用的模型提供商 PRIVATE_CONFIGURATION")
                    return super.configuration(participant, candidateRoutingEnabled, requiresTools, requiresVision, estimatedInputTokens)
                }
                override fun execute(context: android.content.Context, request: AgentRuntimeWire.RunRequest,
                    onEvent: (AgentEvent) -> Unit): AgentRuntimeWire.RunResult {
                    requests.add(request)
                    error("fixture runtime crash PRIVATE_REQUEST")
                }
            }
            assertEquals(MeetingTurnOutcome.REJECTED,
                MeetingRuntime.runTurnOutcome(context, room, "查证资料", runtime = backend))
            val snapshot = MeetingRepository.snapshot(context, room).first()!!
            assertTrue(snapshot.tasks.isEmpty())
            assertEquals(MeetingRoomStatus.FAILED, snapshot.room.status)
            assertEquals(if (configurationFails) 0 else 1, backend.requests.size)
            assertEquals(if (configurationFails) 0 else 1, backend.estimates.size)
            val failure = snapshot.messages.last().content
            assertTrue(failure.contains(if (configurationFails) "配置不可用" else "请求或运行失败"))
            assertFalse(failure.contains("PRIVATE_"))
            assertFalse(failure.contains("未生成有效分工"))
        }
    }

    @Test fun planningResultSaveFailureDoesNotAcknowledgeOrBecomeAnInvalidPlan() = runBlocking(Dispatchers.IO) {
        val room = room()
        val acknowledgements = ConcurrentLinkedQueue<String>()
        val backend = object : Backend(planning = { """{"tasks":[{"participant":"explore","instruction":"查证资料"}]}""" }) {
            override fun acknowledge(context: android.content.Context, runId: String): Boolean {
                acknowledgements.add(runId)
                return true
            }
        }
        MeetingDatabase.get(context).openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER reject_plan_result BEFORE INSERT ON meeting_events
            WHEN NEW.type LIKE 'planning_result:%' BEGIN SELECT RAISE(ABORT, 'PRIVATE_STORAGE_ERROR'); END
        """.trimIndent())
        assertEquals(MeetingTurnOutcome.REJECTED,
            MeetingRuntime.runTurnOutcome(context, room, "查证资料", runtime = backend))
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertTrue(snapshot.tasks.isEmpty())
        assertTrue(acknowledgements.isEmpty())
        assertEquals(1, backend.plans.get())
        assertTrue(snapshot.messages.last().content.contains("分工结果保存失败"))
        assertFalse(snapshot.messages.last().content.contains("PRIVATE_STORAGE_ERROR"))
    }

    @Test fun plannedProfileWhitespaceAndMentionSpellingResolveToTheActualWorkers() = runBlocking(Dispatchers.IO) {
        val room = room()
        val backend = Backend(planning = { """{"tasks":[{"participant":"  @EXPLORE  ","instruction":"查证资料"},
            {"participant":" coder ","instruction":"设计方案"}]}""" })
        assertEquals(MeetingTurnOutcome.SUCCEEDED,
            MeetingRuntime.runTurnOutcome(context, room, "查证和设计", runtime = backend))
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(setOf("explore", "coder"), snapshot.tasks.map { it.participantProfile }.toSet())
        assertEquals(1, backend.plans.get())
    }

    @Test fun disabledDynamicProfileCannotBeReenabledOrUsedByThePlanner() = runBlocking(Dispatchers.IO) {
        val room = room(withWorkers = false)
        val participant = MeetingRepository.createDynamicParticipant(context, room, "file-expert", "文件专家", "文件处理")
        MeetingDatabase.get(context).meetingDao().insertParticipants(listOf(participant.copy(enabled = false)))
        val backend = Backend(planning = { """{"create_agents":[{"profile_name":"file-expert","display_name":"文件专家","summary":"文件处理"}],
            "tasks":[{"participant":"file-expert","instruction":"处理文件"}]}""" })
        assertEquals(MeetingTurnOutcome.HANDLED,
            MeetingRuntime.runTurnOutcome(context, room, "处理文件", runtime = backend))
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(2, backend.plans.get())
        assertTrue(snapshot.tasks.isEmpty())
        assertFalse(snapshot.participants.single { it.profileName == "file-expert" }.enabled)
        assertTrue(snapshot.messages.last().content.contains("名称与已有成员冲突"))
        assertTrue(backend.requests.all { !it.config.supportsTools })
    }

    @Test fun summaryReceivesExplicitFailureWhenAnotherParticipantSucceeds() = runBlocking(Dispatchers.IO) {
        val room = room()
        val backend = Backend(planning = { """{"tasks":[{"participant":"explore","instruction":"查证资料"},
            {"participant":"coder","instruction":"设计方案"}]}""" }, failingParticipant = "coder")

        assertEquals(MeetingTurnOutcome.PARTIAL, MeetingRuntime.runTurnOutcome(context, room, "调研并设计", runtime = backend))

        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(MeetingTaskStatus.COMPLETED, snapshot.tasks.single { it.participantProfile == "explore" }.status)
        assertEquals(MeetingTaskStatus.FAILED, snapshot.tasks.single { it.participantProfile == "coder" }.status)
        val summaryRequest = backend.requests.single { it.config.systemPrompt.contains("汇总主持人") }
        assertTrue(summaryRequest.prompt.contains("coder"))
        assertTrue(summaryRequest.prompt.contains("fixture worker failure"))
        assertTrue(snapshot.messages.any { it.authorType == MeetingAuthorType.HOST && it.content.contains("已汇总") })
    }

    @Test fun failedSummaryKeepsMemberResultsAndDoesNotReportFullSuccess() = runBlocking(Dispatchers.IO) {
        val room = room()
        val backend = object : Backend(planning = { error("direct mention must not plan") }) {
            override fun execute(context: android.content.Context, request: AgentRuntimeWire.RunRequest,
                onEvent: (AgentEvent) -> Unit): AgentRuntimeWire.RunResult {
                if (request.config.systemPrompt.contains("汇总主持人")) error("fixture summary unavailable")
                return super.execute(context, request, onEvent)
            }
        }
        assertEquals(MeetingTurnOutcome.PARTIAL, MeetingRuntime.runTurnOutcome(context, room, "@explore 查证资料", runtime = backend))
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(MeetingTaskStatus.COMPLETED, snapshot.tasks.single().status)
        assertTrue(snapshot.messages.any { it.authorType == MeetingAuthorType.HOST && it.content.contains("汇总失败") })
    }

    @Test fun scheduledRoomRejectsManualTurnAndRecoveryUntilStopped() = runBlocking(Dispatchers.IO) {
        val room = room()
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val backend = object : Backend(planning = { error("direct mention must not plan") }) {
            override suspend fun configuration(participant: MeetingParticipantEntity, candidateRoutingEnabled: Boolean,
                requiresTools: Boolean, requiresVision: Boolean, estimatedInputTokens: Int): ResolvedSubagentConfig {
                if (requiresTools) { entered.complete(Unit); kotlinx.coroutines.awaitCancellation() }
                return super.configuration(participant, candidateRoutingEnabled, requiresTools, requiresVision, estimatedInputTokens)
            }
        }
        AgentTaskDatabase.closeForTests()
        context.deleteDatabase("eta_task.db")
        val taskDao = AgentTaskDatabase.get(context).agentTaskDao()
        val now = System.currentTimeMillis()
        taskDao.insertDefinition(AgentTaskDefinitionEntity("scheduled-meeting", "Meeting", AgentTaskRunKind.MEETING,
            room, "@explore 查证资料", createdAt = now, updatedAt = now))
        taskDao.insertRunIfAbsent(AgentTaskRunEntity("scheduled-meeting-run", "scheduled-meeting", null, now, 1,
            "scheduled-meeting-intent", AgentTaskState.QUEUED, createdAt = now, updatedAt = now))
        val coordinator = AgentTaskCoordinator(taskDao,
            DefaultAgentTaskRunner(context, stateProvider = { AutomationState(true, true, true, 80) }, meetingRuntime = backend))
        val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.currentCoroutineContext()).launch { coordinator.tick() }
        try {
            kotlinx.coroutines.withTimeout(3_000) { entered.await() }
            assertTrue(MeetingRunCoordinator.isRunning(room))
            assertFalse(MeetingRunCoordinator.start(context, room, "第二轮"))
            assertEquals(0, MeetingRepository.recoverInterruptedRuns(context))
            assertTrue(MeetingRunCoordinator.stop(room))
            kotlinx.coroutines.withTimeout(3_000) { job.join() }
            assertFalse(MeetingRunCoordinator.isRunning(room))
            val taskRun = taskDao.run("scheduled-meeting-run")!!
            assertEquals(AgentTaskState.FAILED, taskRun.state)
            assertEquals("MEETING_STOPPED", taskRun.errorCode)
        } finally { job.cancel(); job.join(); AgentTaskDatabase.closeForTests() }
    }

    @Test fun repeatedlyInvalidOrSelfAssignedPlansDoNotRunHostToolsOrPretendToDelegate() = runBlocking(Dispatchers.IO) {
        val room = room()
        val backend = Backend(planning = { """{"tasks":[{"participant":"general","instruction":"自己完成全部任务"}]}""" })
        assertTrue(MeetingRuntime.runTurn(context, room, "处理文件", runtime = backend))
        assertEquals(2, backend.plans.get())
        assertTrue(backend.requests.all { !it.config.supportsTools })
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertTrue(snapshot.tasks.isEmpty())
        assertEquals(MeetingRoomStatus.WAITING_FOR_USER, snapshot.room.status)
        assertTrue(snapshot.messages.any { it.content.contains("未生成有效分工") })
    }

    @Test fun missingRoleBecomesARealDynamicParticipantAndAddressingHostStillDelegates() = runBlocking(Dispatchers.IO) {
        // Dynamic specialists freeze a real, usable model binding even when execution uses a fake backend.
        ProviderRepository.addProvider(OpenAiCompatibleProviderSetting(
            id = "fixture-dynamic-provider", name = "Fixture", baseUrl = "https://fixture.invalid/v1", apiKey = "fixture",
            models = listOf(Model(id = "fixture-dynamic-model", modelId = "fixture-dynamic-model", displayName = "Fixture", toolCall = true))))
        assertTrue(MeetingRoomCreationBridge.loadOptions(context).models.any {
            it.providerId == "fixture-dynamic-provider" && it.modelId == "fixture-dynamic-model" })
        val room = room(withWorkers = false)
        val backend = Backend(planning = { """{"create_agents":[{"profile_name":"file-expert","display_name":"文件专家","summary":"整理文件"}],
            "tasks":[{"participant":"file-expert","instruction":"列出文件整理方案和检查项"}]}""" })
        assertEquals(MeetingTurnOutcome.SUCCEEDED, MeetingRuntime.runTurnOutcome(context, room, "整理资料", runtime = backend))
        val first = MeetingRepository.snapshot(context, room).first()!!
        assertEquals(listOf("file-expert"), first.tasks.map { it.participantProfile })
        val specialist = first.participants.single { it.profileName == "file-expert" }
        assertEquals("fixture-dynamic-provider", specialist.providerId)
        assertEquals("fixture-dynamic-model", specialist.modelId)
        assertTrue(specialist.enabled)
        assertFalse(specialist.isHost)
        MeetingDatabase.closeForTests()
        assertEquals(specialist, MeetingRepository.snapshot(context, room).first()!!.participants.single { it.profileName == "file-expert" })
        MeetingSettingsStore.get(context).update { it.copy(autoDispatchWithoutMention = false) }
        val direct = Backend(planning = { """{"tasks":[{"participant":"file-expert","instruction":"回答这个具体问题，输出检查结果"}]}""" })
        assertTrue(MeetingRuntime.runTurn(context, room, "@general 回答这个具体问题", runtime = direct))
        assertEquals(1, direct.plans.get())
        assertTrue(direct.requests.filter { it.promptScope.meetingHost }.all { !it.config.supportsTools })
        assertTrue(direct.requests.any { it.promptScope.meetingParticipant == "file-expert" && it.config.supportsTools })
    }

    @Test fun dynamicSpecialistWithoutAnyUsableModelIsNotPersistedOrDispatched() = runBlocking(Dispatchers.IO) {
        assertTrue(MeetingRoomCreationBridge.loadOptions(context).models.isEmpty())
        val room = room(withWorkers = false)
        val backend = Backend(planning = { """{"create_agents":[{"profile_name":"file-expert","display_name":"文件专家","summary":"整理文件"}],
            "tasks":[{"participant":"file-expert","instruction":"列出文件整理方案"}]}""" })
        assertEquals(MeetingTurnOutcome.HANDLED, MeetingRuntime.runTurnOutcome(context, room, "整理资料", runtime = backend))
        val snapshot = MeetingRepository.snapshot(context, room).first()!!
        assertTrue(snapshot.tasks.isEmpty())
        assertFalse(snapshot.participants.any { it.profileName == "file-expert" })
        assertTrue(snapshot.messages.last().content.contains("没有可用模型"))
        assertTrue(backend.requests.all { it.promptScope.meetingHost && !it.config.supportsTools })
    }
}


