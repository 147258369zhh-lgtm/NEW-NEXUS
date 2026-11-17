package io.github.mangi.eta.agent.voice

import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentProviderClient
import io.github.mangi.eta.agent.model.OpenAiChatCompletionsProvider
import io.github.mangi.eta.agent.model.ProviderEvent
import io.github.mangi.eta.agent.model.ProviderRequest
import io.github.mangi.eta.agent.model.ProviderRequestPurpose
import io.github.mangi.eta.agent.model.ProviderResponse
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SpeechTranscriptRefinerTest {
    private val requests = mutableListOf<io.github.mangi.eta.agent.runtime.AgentRuntimeWire.RunRequest>()

    private fun config(capabilities: ModelReasoningCapabilities? = null) = AgentModelClient.ModelConfig(
        baseUrl = "https://fixture.invalid/v1", apiKey = "fixture", model = "fixture",
        systemPrompt = "PROVIDER_IDENTITY", reasoningEffort = ReasoningEffort.HIGH,
        reasoningCapabilities = capabilities,
    )

    private fun refiner(
        config: AgentModelClient.ModelConfig? = config(),
        reply: (AgentRunController) -> ProviderResponse,
    ) = SpeechTranscriptRefiner(loadConfig = { config }, runTask = { request, controller ->
        requests += request
        val response = reply(controller)
        io.github.mangi.eta.agent.runtime.AgentRuntimeWire.RunResult(request.runId,
            response.stopReason == io.github.mangi.eta.agent.model.AssistantStopReason.END_TURN,
            response.assistantMessage.optString("content"))
    })

    private fun reply(text: String, finish: String = "stop") = ProviderResponse(JSONObject()
        .put("role", "assistant").put("content", text).put("finish_reason", finish))

    @Test fun correctedTextIsReturnedFromToolFreeRequest() = runBlocking {
        val result = refiner { reply("用 Python 解析 JSON") }.refine("用配森解析杰森")
        assertEquals("用 Python 解析 JSON", result)
        val request = requests.single()
        assertEquals(ProviderRequestPurpose.TRANSCRIPT_REFINE, io.github.mangi.eta.agent.runtime.RuntimeModelTasks.kind(request).purpose)
        assertEquals(emptyList<String>(), request.toolPolicy.allowedTools)
        // 识别文字只作为用户数据进入请求，系统提示不拼接用户内容。
        assertEquals(SpeechTranscriptRefiner.SYSTEM_PROMPT, request.config.systemPrompt)
        assertEquals("用配森解析杰森", request.prompt)
    }

    @Test fun suspiciousOrIncompleteOutputKeepsOriginal() = runBlocking {
        val original = "帮我打开设置"
        assertEquals(original, refiner { reply("好的，我已经帮你把这句话整理成了更通顺的表达：请帮我打开系统设置页面") }.refine(original))
        assertEquals(original, refiner { reply("") }.refine(original))
        assertEquals(original, refiner { reply("帮我打开设置", finish = "length") }.runCatching { refine(original) }.getOrDefault(original))
    }

    @Test fun lengthLimitsSkipModelCall() = runBlocking {
        assertEquals("嗯", refiner { error("不应请求模型") }.refine("嗯"))
        // 超过分段硬上限的口述整体回退原文，不发起任何模型调用。
        val long = "很长".repeat(SpeechTranscriptRefiner.MAX_SEGMENTS * SpeechTranscriptRefiner.MAX_CHARS / 2 + 1)
        assertEquals(long, refiner { error("不应请求模型") }.refine(long))
    }

    @Test fun longTranscriptIsSegmentedInOrderWithPerSegmentFallback() = runBlocking {
        val head = "甲".repeat(SpeechTranscriptRefiner.MAX_CHARS - 1) + "。"
        val tail = "乙".repeat(SpeechTranscriptRefiner.MAX_CHARS - 1)
        val original = head + tail
        assertTrue(original.length > SpeechTranscriptRefiner.MAX_CHARS)
        val corrected = "乙".repeat(tail.length - 1) + "丙"
        var call = 0
        val result = refiner {
            if (++call == 1) throw java.io.IOException("offline")
            reply(corrected)
        }.refine(original)

        // 段边界落在句末，按原文顺序逐段请求；首段失败只回退首段原文。
        assertEquals(listOf(head, tail), requests.map { it.prompt })
        assertEquals(head + corrected, result)

        // 用户取消向上传播，等待中的分段不会采用迟到结果。
        val started = CompletableDeferred<Unit>()
        var failure: Throwable? = null
        val job = launch {
            try {
                refiner { controller ->
                    val gate = CountDownLatch(1)
                    controller.register { gate.countDown() }
                    started.complete(Unit)
                    gate.await(30, TimeUnit.SECONDS)
                    reply(head)
                }.refine(original)
            } catch (error: Throwable) {
                failure = error
            }
        }
        withTimeout(5_000) { started.await() }
        job.cancel()
        job.join()
        assertTrue(failure is CancellationException)
        assertFalse(failure is TimeoutCancellationException)
    }

    @Test fun missingChatModelIsConfigurationFailure() = runBlocking {
        val failure = runCatching { refiner(config = null) { reply("x") }.refine("测试文本") }.exceptionOrNull()
        assertEquals(SpeechErrorCode.CONFIGURATION, (failure as SpeechFailure).code)
    }

    @Test fun reasoningUsesLowestSelectableEffort() = runBlocking {
        refiner(config(ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), canDisable = true,
        ))) { reply("测试文本") }.refine("测试文本")
        refiner(config(ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.LOW, ReasoningEffort.HIGH), mandatory = true,
        ))) { reply("测试文本") }.refine("测试文本")
        assertEquals(listOf(ReasoningEffort.OFF, ReasoningEffort.LOW), requests.map { it.config.reasoningEffort })
        // 未声明推理能力的模型保留用户配置，不发送它可能不支持的参数。
        refiner { reply("测试文本") }.refine("测试文本")
        assertEquals(ReasoningEffort.HIGH, requests.last().config.reasoningEffort)
    }

    @Test fun timeoutCancelsBlockedProviderCall() {
        val cancelled = CountDownLatch(1)
        val failure = runCatching {
            runBlocking {
                refiner { controller ->
                    val released = CountDownLatch(1)
                    controller.register { cancelled.countDown(); released.countDown() }
                    released.await(30, TimeUnit.SECONDS)
                    reply("迟到")
                }.refine("测试文本")
            }
        }.exceptionOrNull()
        assertTrue(failure is TimeoutCancellationException)
        assertTrue(cancelled.await(1, TimeUnit.SECONDS))
    }
}
