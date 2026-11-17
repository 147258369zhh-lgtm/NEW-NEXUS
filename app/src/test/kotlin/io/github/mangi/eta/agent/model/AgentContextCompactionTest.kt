package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.runtime.AgentEvent
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.agent.runtime.AgentTokenUsage
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AgentContextCompactionTest {
    @Test fun cancelledIndependentSummaryDoesNotFallBackOrReplaceHistory() {
        val messages = jsonHistory()
        val original = messages.toString()
        var fallbackRequests = 0
        val session = AgentContextSession(config, messages, 1, "cancel-summary",
            provider { _, _ -> fallbackRequests++; response("不应生成摘要") },
            AgentRunController(), { emptySet() }, {}, { fail("不应提交快照") },
            compactionConfig = config.copy(model = "summary-model"),
            compactionProvider = provider { _, _ -> throw kotlinx.coroutines.CancellationException("cancelled") })

        assertThrows(kotlinx.coroutines.CancellationException::class.java) { session.compact(force = true, final = true) }
        assertEquals(0, fallbackRequests)
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test fun cancelledFinalCheckpointDoesNotPublishFallbackSnapshot() {
        val messages = jsonHistory()
        var checkpoints = 0
        val session = AgentContextSession(config, messages, 1, "cancel-checkpoint",
            provider { _, _ -> response("历史已完成") }, AgentRunController(), { emptySet() }, {},
            { if (++checkpoints > 1) throw kotlinx.coroutines.CancellationException("cancelled") })

        session.compact(force = true)
        val originalSnapshot = session.snapshot()
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { session.compact(final = true) }
        assertSame(originalSnapshot, session.snapshot())
    }

    @Test fun failedProviderEstimateFallsBackToLocalBudget() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "规则"))
            .put(AgentConversationCodec.userTextMessage("旧任务"))
            .put(JSONObject().put("role", "assistant").put("content", "历史".repeat(8_000)))
            .put(AgentConversationCodec.userTextMessage("继续"))
        var summaries = 0
        val fixture = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun estimateInputTokens(request: ProviderRequest, runController: AgentRunController): Int? =
                throw IllegalStateException("估算不可用")
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse {
                summaries++
                return response("旧任务已完成。")
            }
        }
        val session = AgentContextSession(config.copy(contextWindow = 16_000), messages, 1, "estimate",
            fixture, AgentRunController(), { emptySet() }, {}, {})
        session.compact()
        assertTrue(summaries > 0)
        assertNotNull(session.snapshot())
    }

    @Test fun cancelledProviderEstimateDoesNotStartAReplacementRequest() {
        val messages = JSONArray().put(AgentConversationCodec.userTextMessage("原始请求"))
        val original = messages.toString()
        val fixture = object : AgentProviderClient {
            override val id = "fixture"
            override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
            override fun estimateInputTokens(request: ProviderRequest, runController: AgentRunController): Int? =
                throw kotlinx.coroutines.CancellationException("cancelled")
            override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
                error("cancelled estimation must not start another request")
        }
        val session = AgentContextSession(config.copy(contextWindow = 16_000), messages, 0, "cancel",
            fixture, AgentRunController(), { emptySet() }, {}, {})
        assertThrows(kotlinx.coroutines.CancellationException::class.java) { session.compact() }
        assertEquals(original, messages.toString())
    }

    @Test fun shortMediumAndLongWindowsBoundEverySummaryRequestIncludingGiantEscapedMessages() {
        for (window in listOf(4_096, 16_384, 128_000)) {
            val original = JSONArray().put(JSONObject().put("role", "system").put("content", "规则"))
                .put(AgentConversationCodec.userTextMessage("旧任务"))
                .put(JSONObject().put("role", "assistant").put("content", ("证据\\\"\n".repeat(8_000))))
                .put(AgentConversationCodec.userTextMessage("最新任务"))
            var requests = 0
            val result = AgentContextCompactor(config.copy(contextWindow = window), provider { request, _ ->
                requests++
                val budget = ContextBudgetPolicy.forCompaction(window)
                assertTrue("window=$window", ContextBudgetPolicy.estimateMessages(request.messages) <= budget.usableInputTokens)
                assertEquals(budget.outputReserveTokens, request.contextOutputTokenCeiling)
                response("旧任务已执行并记录证据。")
            }, AgentRunController()).compact(original, 1, emptySet())
            assertTrue(requests > 0)
            assertTrue(result.toString().contains("最新任务"))
        }
    }

    @Test fun automaticCompactionIncludesSystemAndToolsWhenUsageUnderstatesTheRequest() {
        val original = JSONArray().put(JSONObject().put("role", "system").put("content", "规则".repeat(2_000)))
            .put(AgentConversationCodec.userTextMessage("旧任务"))
            .put(JSONObject().put("role", "assistant").put("content", "历史".repeat(4_000)))
            .put(AgentConversationCodec.userTextMessage("继续"))
        var requests = 0
        val session = AgentContextSession(config.copy(contextWindow = 16_000), original, 1, "budget",
            provider { _, _ -> requests++; response("旧任务已完成。") }, AgentRunController(), { emptySet() }, {}, {})
        session.observeInputTokens(1)
        session.compact(tools = JSONArray().put(JSONObject().put("description", "工具说明".repeat(1_000))))
        assertTrue(requests > 0)
        assertNotNull(session.snapshot())
    }

    @Test fun finalCompactionKeepsLatestCompletedRequestAndAnswerTogether() {
        val original = jsonHistory().put(AgentConversationCodec.userTextMessage("最新已执行任务"))
            .put(JSONObject().put("role", "assistant").put("content", "最新执行结果已经完成"))
        val result = AgentContextCompactor(config, provider { _, _ -> response("历史任务均已完成。") },
            AgentRunController()).compact(original, 1, emptySet())
        assertEquals("最新已执行任务", result.getJSONObject(result.length() - 2).getString("content"))
        assertEquals("最新执行结果已经完成", result.getJSONObject(result.length() - 1).getString("content"))
    }

    @Test
    fun firstAssistantScreenshotReachesChatWithoutTryingToCompressItsEncoding() {
        val events = mutableListOf<AgentEvent>()
        val reference = "data:image/png;base64," + "A".repeat(300_000)
        var requests = 0
        val result = AgentModelClient.complete(
            config.copy(contextWindow = 16_000), "这是什么？",
            AgentModelClient.ToolExecutor { error("不应执行工具") },
            images = listOf(AgentModelClient.ModelImage(reference, "image/png", 225_000)),
            provider = provider { request, _ ->
                requests++
                assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                assertTrue(request.messages.toString().contains(reference))
                response("这是当前屏幕。")
            }, onEvent = events::add,
        )
        assertEquals(1, requests)
        assertEquals("这是当前屏幕。", result.content)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompaction>().isEmpty())
    }

    @Test fun anOversizedFirstCompletedAnswerStillCompactsWhileKeepingItsRequestBeforeTheSummary() {
        val original = JSONArray().put(AgentConversationCodec.userTextMessage("已完成的首次任务"))
            .put(JSONObject().put("role", "assistant").put("content", "完整结果".repeat(5_000)))
        var summaries = 0
        val session = AgentContextSession(config.copy(contextWindow = 16_000), original, 0, "first-answer",
            provider { request, _ ->
                summaries++
                assertEquals(ProviderRequestPurpose.COMPACTION, request.purpose)
                response("首次任务已完成。")
            }, AgentRunController(), { emptySet() }, {}, {})
        session.compact(final = true)
        assertTrue(summaries > 0)
        val snapshot = checkNotNull(session.snapshot())
        assertEquals("已完成的首次任务", snapshot.messages.first().content)
        assertTrue(snapshot.messages.last().contextSummary)
    }

    @Test
    fun automaticCompactionSkipsAnOversizedFirstPromptButExplicitCompactionStillFails() {
        val events = mutableListOf<AgentEvent>()
        val context = AgentContextSession(
            config = config.copy(contextWindow = 16_000),
            messages = JSONArray().put(AgentConversationCodec.userTextMessage("中".repeat(24_000))),
            systemCount = 0, operationId = "first-prompt", provider = provider { _, _ ->
                error("没有完整历史时不应请求摘要")
            }, runController = AgentRunController(), sensitiveIds = { emptySet() },
            onEvent = events::add, onContextSnapshot = { error("未压缩，不应覆盖历史") },
        )
        context.compact()
        assertTrue(events.isEmpty())
        val failure = assertThrows(AgentModelFailure::class.java) { context.compact(force = true) }
        assertEquals("CONTEXT_NOT_COMPACTABLE", failure.code)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompaction>().any { it.phase == "failed" })
    }

    @Test
    fun independentCompactionModelFailureFallsBackToTheChatModel() {
        val messages = jsonHistory()
        var primaryRequests = 0
        var summaryRequests = 0
        val summaryConfig = config.copy(model = "summary-model")
        val compactor = AgentContextCompactor(
            config = config,
            provider = provider { request, _ ->
                primaryRequests++
                assertEquals(config.model, request.effectiveConfig.model)
                response("使用聊天模型完成的摘要。")
            },
            controller = AgentRunController(),
            summaryConfig = summaryConfig,
            summaryProvider = provider { request, _ ->
                summaryRequests++
                assertEquals(summaryConfig.model, request.effectiveConfig.model)
                throw AgentModelFailure("HTTP_400", false, "独立压缩模型不可用")
            },
        )

        compactor.compact(messages, 1, emptySet())

        assertTrue(summaryRequests >= 1)
        assertTrue(primaryRequests >= 1)
        assertTrue(compactor.summaryFallbackUsed)
    }

    @Test
    fun automaticScreenContentIsNotCopiedIntoCompactionInput() {
        val messages = jsonHistory()
        val user = AgentConversationCodec.userTextMessage("保留最新问题")
        AssistantScreenContextProjection.attach(user, "PRIVATE_AUTOMATIC_SCREEN_CONTENT")
        messages.put(user)
        val compacted = AgentContextCompactor(config, provider { request, _ ->
            assertFalse(request.messages.toString().contains("PRIVATE_AUTOMATIC_SCREEN_CONTENT"))
            response("已完成此前的任务。")
        }, AgentRunController()).compact(messages, 1, emptySet())
        assertFalse(AgentConversationCodec.encodeTranscriptForStorage(
            AgentConversationCodec.transcript(compacted, 1),
        ).contains("PRIVATE_AUTOMATIC_SCREEN_CONTENT"))
        assertTrue(AssistantScreenContextProjection.project(compacted).toString().contains("PRIVATE_AUTOMATIC_SCREEN_CONTENT"))
    }

    private val config = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid", apiKey = "fixture", model = "fixture", systemPrompt = "固定约束",
        contextWindow = 128_000,
    )

    @Test
    fun largeHistoryWithoutUsageUsesConservativeEstimateToCompact() {
        val original = "历史事实".repeat(180_000)
        var requests = 0
        val result = AgentModelClient.complete(config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = listOf(AgentModelClient.ConversationMessage("assistant", original)),
            provider = provider { request, _ ->
                requests++
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    response("此前历史已压缩。")
                } else {
                    assertEquals(ProviderRequestPurpose.CHAT, request.purpose)
                    assertFalse(request.messages.toString().contains(original))
                    response("完成")
                }
            })
        assertTrue(requests >= 2)
        assertEquals("完成", result.content)
        assertNotNull(result.contextSnapshot)
        assertTrue(result.contextSnapshot!!.estimatedInputTokens!! < ContextBudgetPolicy.of(config.contextWindow)!!.triggerInputTokens)
    }

    @Test
    fun interruptedToolBatchKeepsCompletedResultAndMarksOnlyUnknownResults() {
        val call = AgentModelClient.ConversationMessage("assistant", "", toolCallsJson = """[
            {"id":"a","type":"function","function":{"name":"fixture","arguments":"{}"}},
            {"id":"b","type":"function","function":{"name":"fixture","arguments":"{}"}}]""")
        val completed = AgentModelClient.ConversationMessage("tool", "真实完成结果", toolCallId = "a")
        val restored = AgentToolBatchRecovery.completeInterrupted(listOf(call, completed))
        assertEquals(completed, restored[1])
        assertEquals("b", restored.last().toolCallId)
        assertTrue(restored.last().content.contains("TOOL_INTERRUPTED"))
        assertEquals(restored, AgentToolBatchRecovery.completeInterrupted(restored))
    }

    @Test
    fun automaticCompactionPreservesIncrementalTranscriptAndLatestRequest() {
        val history = (1..8).flatMap { turn -> listOf(
            AgentModelClient.ConversationMessage("user", "第 $turn 个任务"),
            AgentModelClient.ConversationMessage("assistant", "事实 $turn ".repeat(2200)),
        ) }
        val events = mutableListOf<AgentEvent>()
        var summaries = 0
        val snapshots = mutableListOf<AgentContextSnapshot>()
        val provider = provider { request, emit ->
            if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                summaries++
                assertEquals(0, request.effectiveTools.length())
                response("已完成前面的任务，继续处理最新请求。")
            } else {
                assertTrue(request.messages.toString().contains("最新请求必须保留"))
                assertTrue(request.messages.toString().contains("固定约束"))
                emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 45_000)))
                response("完成最新请求")
            }
        }
        val result = AgentModelClient.complete(
            config.copy(contextWindow = 50_000), "最新请求必须保留", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history, provider = provider, onContextSnapshot = snapshots::add, onEvent = events::add,
        )
        assertTrue(summaries > 0)
        assertEquals(listOf("完成最新请求"), result.transcript.map { it.content })
        val snapshot = checkNotNull(result.contextSnapshot)
        assertTrue(snapshot.messages.any { it.contextSummary })
        assertEquals("user", snapshot.messages[snapshot.messages.lastIndex - 1].role)
        assertEquals("最新请求必须保留", snapshot.messages[snapshot.messages.lastIndex - 1].content)
        assertEquals("assistant", snapshot.messages.last().role)
        assertEquals("完成最新请求", snapshot.messages.last().content)
        assertEquals(9, snapshot.consumedUserTurns)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompaction>().any { it.phase == "completed" })
        assertTrue(snapshots.all { AgentContextSnapshot.decode(it.encode()) == it })
    }

    @Test
    fun manualCompactionDoesNotCreateUserOrAssistantTranscript() {
        val result = AgentModelClient.complete(
            config, "", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), compactOnly = true, provider = provider { request, _ ->
                assertEquals(ProviderRequestPurpose.COMPACTION, request.purpose)
                response("历史工作已完成，保留后续问题。")
            },
        )
        assertTrue(result.transcript.isEmpty())
        assertEquals("", result.content)
        assertNotNull(result.contextSnapshot)
        assertTrue(result.contextSnapshot!!.messages.none { it.role == "user" && it.content.isBlank() })
    }

    @Test
    fun truncatedSummaryNeverReplacesOriginalContext() {
        val original = jsonHistory()
        val before = original.toString()
        val session = AgentContextSession(config, original, 1, "operation", provider { _, _ ->
            response("半截摘要", "length")
        }, AgentRunController(), { emptySet() }, {}, { fail("不应提交快照") })
        assertThrows(AgentModelFailure::class.java) { session.compact(force = true) }
        assertEquals(before, original.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun cancellationAndPersistenceFailureKeepOriginalContext() {
        for (cancel in listOf(true, false)) {
            val original = jsonHistory()
            val before = original.toString()
            val controller = AgentRunController()
            val session = AgentContextSession(config, original, 1, "operation", provider { _, _ ->
                if (cancel) controller.cancel()
                response("有界摘要")
            }, controller, { emptySet() }, {}, { throw IllegalStateException("fixture storage failure") })
            assertThrows(Exception::class.java) { session.compact(force = true) }
            assertEquals(before, original.toString())
            assertNull(session.snapshot())
        }
    }

    @Test
    fun summaryInputRedactsSensitiveToolsAndOpaqueReasoningAndImages() {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", "规则"))
        messages.put(AgentConversationCodec.userTextMessage("旧任务"))
        val assistant = JSONObject().put("role", "assistant").put("content", "读取结果")
            .put("reasoning_content", "PRIVATE_THINKING")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "sensitive").put("type", "function")
                .put("function", JSONObject().put("name", "read_secret").put("arguments", "SECRET_ARGUMENT"))))
        ResponsesEphemeralState.attachOutputItems(assistant, JSONArray().put(JSONObject().put("encrypted", "OPAQUE_SECRET")))
        messages.put(assistant)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "sensitive").put("content", "SECRET_RESULT"))
        history().forEach { messages.put(AgentConversationCodec.toJsonObject(it)) }
        val pending = AgentConversationCodec.userMessage("瞬时观察", listOf(
            AgentModelClient.ModelImage("data:image/png;base64,PRIVATE_IMAGE", "image/png", 10),
        )).put("_eta_observation", true)
        messages.put(pending)
        val compacted = AgentContextCompactor(config, provider { request, _ ->
            val text = request.messages.toString()
            listOf("SECRET_ARGUMENT", "SECRET_RESULT", "PRIVATE_THINKING", "OPAQUE_SECRET", "PRIVATE_IMAGE").forEach {
                assertFalse("摘要包含敏感数据 $it", text.contains(it))
            }
            response("已读取过信息，原始敏感结果未保留。")
        }, AgentRunController()).compact(messages, 1, setOf("sensitive"))
        assertSame(pending, compacted.getJSONObject(compacted.length() - 1))
        assertFalse(compacted.toString().contains("OPAQUE_SECRET"))
    }

    @Test
    fun toolBatchCannotBeSplitUntilEveryResultIsPresent() {
        val call = JSONObject().put("role", "assistant").put("tool_calls", JSONArray().apply {
            listOf("a", "b").forEach { put(JSONObject().put("id", it).put("type", "function")
                .put("function", JSONObject().put("name", "fixture").put("arguments", "{}"))) }
        })
        val messages = listOf(call,
            JSONObject().put("role", "tool").put("tool_call_id", "a"),
            JSONObject().put("role", "tool").put("tool_call_id", "b"),
            AgentConversationCodec.userTextMessage("继续"))
        assertFalse(AgentContextCompactor.canSplit(messages, 1))
        assertFalse(AgentContextCompactor.canSplit(messages, 2))
        assertTrue(AgentContextCompactor.canSplit(messages, 3))
    }

    @Test
    fun signedAnthropicToolRoundCannotCompactBeforeReturningToolResults() {
        val messages = jsonHistory()
        messages.put(AgentConversationCodec.userTextMessage("继续查询"))
        val assistant = JSONObject().put("role", "assistant").put("content", "")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "toolu_current")
                .put("type", "function").put("function", JSONObject().put("name", "device_info")
                    .put("arguments", "{}"))))
        AnthropicEphemeralState.attachContentBlocks(assistant, JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "")
                .put("signature", "CURRENT_SIGNATURE"))
            .put(JSONObject().put("type", "tool_use").put("id", "toolu_current")
                .put("name", "device_info").put("input", JSONObject())))
        messages.put(assistant)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "toolu_current")
            .put("content", "工具结果"))
        val original = messages.toString()
        val session = AgentContextSession(config, messages, 1, "operation", provider { _, _ ->
            fail("签名未用前不能改写历史")
            response("不应执行")
        }, AgentRunController(), { emptySet() }, {}, { fail("不应提交快照") })

        val failure = assertThrows(AgentModelFailure::class.java) {
            session.compact(force = true)
        }
        assertEquals("ANTHROPIC_THINKING_CONTEXT_LOCKED", failure.code)
        assertEquals(original, messages.toString())
        assertNull(session.snapshot())
    }

    @Test
    fun compactionDropsCompletedAnthropicSignaturesAfterChangingPrefix() {
        val messages = jsonHistory()
        messages.put(AgentConversationCodec.userTextMessage("已完成的工具任务"))
        val assistant = JSONObject().put("role", "assistant").put("content", "读取中")
            .put("tool_calls", JSONArray().put(JSONObject().put("id", "toolu_old")
                .put("type", "function").put("function", JSONObject().put("name", "device_info")
                    .put("arguments", "{}"))))
        AnthropicEphemeralState.attachContentBlocks(assistant, JSONArray()
            .put(JSONObject().put("type", "thinking").put("thinking", "")
                .put("signature", "STALE_SIGNATURE"))
            .put(JSONObject().put("type", "tool_use").put("id", "toolu_old")
                .put("name", "device_info").put("input", JSONObject())))
        messages.put(assistant)
        messages.put(JSONObject().put("role", "tool").put("tool_call_id", "toolu_old")
            .put("content", "完成"))
        messages.put(JSONObject().put("role", "assistant").put("content", "查询完成"))
        messages.put(AgentConversationCodec.userTextMessage("新的问题"))

        val compacted = AgentContextCompactor(config, provider { _, _ ->
            response("此前任务已完成。")
        }, AgentRunController()).compact(messages, 1, emptySet())

        assertFalse(compacted.toString().contains("STALE_SIGNATURE"))
        assertTrue(compacted.toString().contains("新的问题"))
        assertFalse(compacted.toString().contains("toolu_old"))
    }

    @Test
    fun overflowClassificationDoesNotTreatEveryBadRequestAsCapacityFailure() {
        assertEquals("CONTEXT_OVERFLOW", AgentModelFailure.http(400,
            """{"error":{"code":"context_length_exceeded","message":"private fixture"}}""").code)
        assertEquals("CONTEXT_OVERFLOW", AgentModelFailure.http(400,
            """{"error":{"type":"invalid_request_error","message":"prompt is too long: fixture"}}""").code)
        assertEquals("HTTP_400", AgentModelFailure.http(400,
            """{"error":{"message":"invalid tool parameter"}}""").code)
    }

    @Test
    fun explicitOverflowFailsWithoutCompactionOrReplay() {
        var normalRequests = 0
        var summaryRequests = 0
        val failure = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) {
                    summaryRequests++
                    response("之前的任务已完成。")
                } else if (normalRequests++ == 0) {
                    emit(ProviderEvent.BlockDelta(AssistantBlockKind.THINKING, 0, "失败思考"))
                    throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")
                } else response("完成")
            })
        }
        assertEquals("CONTEXT_OVERFLOW", (failure.cause as AgentModelFailure).code)
        assertEquals(1, normalRequests)
        assertEquals(0, summaryRequests)
        assertTrue(failure.transcript.isEmpty())
    }

    @Test
    fun hostedToolOverflowDoesNotReplayOrStartCompaction() {
        var requests = 0
        val error = assertThrows(AgentModelExecutionException::class.java) {
            AgentModelClient.complete(config, "查询", AgentModelClient.ToolExecutor { error("不应执行工具") },
                history = history(), provider = provider { _, emit ->
                    requests++
                    emit(ProviderEvent.HostedToolStarted("hosted", "web_search"))
                    throw AgentModelFailure("CONTEXT_OVERFLOW", false, "fixture")
                })
        }
        assertEquals(1, requests)
        assertFalse((error.cause as AgentModelFailure).retryable)
    }

    @Test
    fun summaryChunksIncludeAllCompletedHistory() {
        var requests = 0
        val seen = mutableListOf<String>()
        val result = AgentContextCompactor(config, provider { request, _ ->
            requests++
            val historyText = request.messages.getJSONObject(1).getString("content")
            seen += historyText
            response("之前的工作已完成。")
        }, AgentRunController()).compact(jsonHistory(), 1, emptySet())
        assertTrue(requests >= 1)
        for (turn in 1..4) assertTrue(seen.any { it.contains("问题 $turn") })
        assertTrue(result.toString().contains("问题 6"))
    }

    @Test
    fun completedAnswerRemainsSuccessfulWhenFinalCompactionFails() {
        val events = mutableListOf<AgentEvent>()
        val finalText = "完整结果".repeat(17_000)
        val result = AgentModelClient.complete(config.copy(contextWindow = 80_000), "继续", AgentModelClient.ToolExecutor { error("不应执行工具") },
            history = history(), provider = provider { request, emit ->
                if (request.purpose == ProviderRequestPurpose.COMPACTION) response("不完整", "length")
                else {
                    emit(ProviderEvent.Usage(AgentTokenUsage(inputTokens = 70_000)))
                    response(finalText)
                }
            }, onEvent = events::add)
        assertEquals(finalText, result.content)
        assertTrue(events.filterIsInstance<AgentEvent.ContextCompaction>().any { it.phase == "failed" })
        assertNotNull(result.contextSnapshot)
        assertEquals(finalText, result.contextSnapshot!!.messages.last().content)
        assertEquals(finalText, result.transcript.last().content)
    }

    private fun history() = (1..6).flatMap { turn -> listOf(
        AgentModelClient.ConversationMessage("user", "问题 $turn"),
        AgentModelClient.ConversationMessage("assistant", "事实 ".repeat(1000)),
    ) }

    private fun jsonHistory() = JSONArray().put(JSONObject().put("role", "system").put("content", "固定规则")).also {
        history().forEach { message -> it.put(AgentConversationCodec.toJsonObject(message)) }
    }

    private fun response(text: String, stop: String = "stop") = ProviderResponse(
        JSONObject().put("role", "assistant").put("content", text).put("finish_reason", stop),
    )

    private fun provider(block: (ProviderRequest, (ProviderEvent) -> Unit) -> ProviderResponse) = object : AgentProviderClient {
        override val id = "fixture"
        override val capabilities = ProviderCapabilities(EndpointKind.CHAT_COMPLETIONS, true, true, true, true, false, false)
        override fun complete(request: ProviderRequest, runController: AgentRunController, onEvent: (ProviderEvent) -> Unit): ProviderResponse =
            block(request, onEvent)
    }
}
