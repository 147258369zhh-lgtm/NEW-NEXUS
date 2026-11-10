package io.github.mangi.eta.agent.model

import android.util.Log
import java.security.MessageDigest

/**
 * Token-free local engines still need a hard prompt budget: LITERT-LM counts the reserved output
 * window against the same context as the prompt, while the tokenizer used by imported models can
 * differ substantially from a simple character count. Keep a conservative estimate and leave
 * room for sampling and chat-template markers so an otherwise valid short question cannot end in
 * `ContextFull` after producing only a few tokens.
 */
internal object LocalRuntimePromptBudget {
    private const val SAFETY_TOKENS = 192
    private const val MAX_SYSTEM_SHARE = 0.55

    data class Fit<T>(
        val turns: List<T>,
        val omittedTurns: Int,
        val estimatedInputTokens: Int,
    )

    fun <T> fit(
        turns: List<T>,
        contextWindow: Int,
        maxOutputTokens: Int,
        additionalInputTokens: Int = 0,
        roleOf: (T) -> String,
        textOf: (T) -> String,
        engine: String,
        recordId: String,
    ): Fit<T> {
        if (turns.isEmpty()) return Fit(emptyList(), 0, 0)
        val usableInput = (contextWindow - maxOutputTokens - additionalInputTokens - SAFETY_TOKENS)
            .coerceAtLeast(256)
        val lastUserIndex = turns.indexOfLast { roleOf(it).equals("user", ignoreCase = true) }
        if (lastUserIndex < 0) return Fit(turns, 0, estimateInputTokens(turns, roleOf, textOf))

        // System instructions and the current user turn are mandatory. Keep every turn after the
        // latest user message as well (for example post-history prompt injection). Older history is
        // removed oldest-first until both the input and system shares fit.
        val tail = turns.subList(lastUserIndex, turns.size)
        val systems = turns.filter { roleOf(it).equals("system", ignoreCase = true) }
        val mandatory = systems + tail
        val mandatoryTokens = estimateInputTokens(mandatory, roleOf, textOf)
        val systemTokens = estimateInputTokens(systems, roleOf, textOf)
        if (systemTokens > (contextWindow * MAX_SYSTEM_SHARE).toInt()) {
            throw AgentModelFailure(
                code = "LOCAL_PROMPT_BUDGET",
                retryable = false,
                message = "本地模型的系统指令仍超过上下文预算，请减少记忆注入或提示词模板后重试。",
            )
        }
        if (mandatoryTokens > usableInput) {
            throw AgentModelFailure(
                code = "CONTEXT_OVERFLOW",
                retryable = false,
                message = "本次输入超过本地模型可用上下文。请缩短消息、减少记忆注入或调高模型上下文。",
            )
        }

        val optional = turns.subList(0, lastUserIndex)
            .filterNot { roleOf(it).equals("system", ignoreCase = true) }
        val fullEstimatedTokens = estimateInputTokens(turns, roleOf, textOf)
        // Once the full request crosses the ceiling, cut to the middle of the window. Keeping a
        // trimmed history exactly at the edge makes the next request trim again, rebuild the
        // native conversation and re-prefill the whole chat on every turn.
        // Keep a useful share of recent history after mandatory system/current messages. The old
        // formula subtracted the output reserve twice and could collapse every chat to only the
        // system preface plus the latest question.
        val historyHeadroom = (usableInput - mandatoryTokens).coerceAtLeast(0)
        val compactionTarget = mandatoryTokens + (historyHeadroom * 3) / 5
        val inputTarget = if (fullEstimatedTokens > usableInput) {
            minOf(usableInput, compactionTarget)
        } else {
            usableInput
        }
        val kept = ArrayDeque<T>()
        var totalTokens = mandatoryTokens
        for (turn in optional.asReversed()) {
            val next = estimateInputTokens(listOf(turn), roleOf, textOf)
            if (totalTokens + next > inputTarget) break
            kept.addFirst(turn)
            totalTokens += next
        }
        // A rebuilt conversation must start on a user turn. Cutting only the oldest assistant
        // reply leaves a response whose question is gone and degrades small-model coherence.
        while (kept.isNotEmpty() && !roleOf(kept.first()).equals("user", ignoreCase = true)) {
            kept.removeFirst()
        }
        val omitted = optional.size - kept.size

        val fitted = systems + kept + tail
        diagnostics(
            engine = engine,
            recordId = recordId,
            event = "prompt_budget",
            detail = "context=$contextWindow output=$maxOutputTokens input≈$totalTokens " +
                "reserved=$additionalInputTokens turns=${fitted.size} omitted=$omitted",
        )
        return Fit(fitted, omitted, totalTokens)
    }

    /** Removes no-op fenced code blocks that small chat templates sometimes emit on their own. */
    fun visibleOutput(raw: String): String {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val kept = mutableListOf<String>()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val fenceStart = FENCE_START.find(line)
            if (fenceStart == null) {
                kept.add(line)
                index += 1
                continue
            }
            if (FENCE_SAME_LINE_EMPTY.matches(line)) {
                index += 1
                continue
            }
            val closing = (index + 1 until lines.size).firstOrNull { FENCE_END.matches(lines[it]) }
            if (closing == null) {
                kept.add(line)
                index += 1
                continue
            }
            val body = lines.subList(index + 1, closing)
            if (body.all(String::isBlank)) {
                index = closing + 1
            } else {
                kept.addAll(lines.subList(index, closing + 1))
                index = closing + 1
            }
        }
        return kept.joinToString("\n")
            .replace(Regex("\\u0000+"), "")
            .replace(Regex("(?m)^[ \\t]+$"), "")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun failure(engine: String, recordId: String, failure: Throwable): Throwable {
        if (failure is AgentModelFailure) return failure
        val message = failure.message.orEmpty()
        val lower = message.lowercase()
        diagnostics(
            engine = engine,
            recordId = recordId,
            event = "failure",
            detail = "type=${failure.javaClass.simpleName} message=${safeMessage(message)}",
            warn = true,
        )
        return if (
            "max number of tokens reached" in lower ||
            "context window is full" in lower ||
            "contextfull" in lower ||
            "context full" in lower ||
            "too many tokens" in lower
        ) {
            AgentModelFailure(
                code = "CONTEXT_OVERFLOW",
                retryable = false,
                message = "本地模型上下文已满。系统已保留模型输出空间；请缩短输入，或调高该模型的上下文。",
                cause = failure,
            )
        } else {
            failure
        }
    }

    fun started(
        engine: String,
        recordId: String,
        messages: Int,
        inputChars: Int,
        contextWindow: Int,
        maxOutputTokens: Int,
        thinking: Boolean,
    ) = diagnostics(
        engine = engine,
        recordId = recordId,
        event = "generation_start",
        detail = "messages=$messages input_chars=$inputChars context=$contextWindow " +
            "max_output=$maxOutputTokens thinking=$thinking",
    )

    fun firstToken(engine: String, recordId: String, elapsedMs: Long) = diagnostics(
        engine = engine,
        recordId = recordId,
        event = "first_token",
        detail = "elapsed_ms=$elapsedMs",
    )

    fun finished(
        engine: String,
        recordId: String,
        outputChars: Int,
        tokens: Int?,
        elapsedMs: Long,
        stop: String,
    ) = diagnostics(
        engine = engine,
        recordId = recordId,
        event = "generation_finish",
        detail = "output_chars=$outputChars tokens=${tokens ?: "?"} elapsed_ms=$elapsedMs stop=$stop",
    )

    internal fun <T> estimateInputTokens(
        turns: List<T>,
        roleOf: (T) -> String,
        textOf: (T) -> String,
    ): Int =
        turns.sumOf { turn ->
            val bytes = textOf(turn).toByteArray(Charsets.UTF_8).size
            // UTF-8 Chinese is about one byte per token; ASCII is conservatively three bytes per
            // token. Add role/chat-template overhead rather than treating the estimate as exact.
            ((bytes + 2) / 3).coerceAtLeast(1) + 8 + roleOf(turn).length
        }

    private fun safeMessage(message: String): String = message
        .replace(Regex("\\s+"), " ")
        .take(240)

    private fun diagnostics(
        engine: String,
        recordId: String,
        event: String,
        detail: String,
        warn: Boolean = false,
    ) {
        val line = "engine=$engine model=${shortFingerprint(recordId)} event=$event $detail"
        runCatching { if (warn) Log.w(TAG, line) else Log.i(TAG, line) }
    }

    private fun shortFingerprint(value: String): String = runCatching {
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .take(6)
            .joinToString("") { "%02x".format(it) }
    }.getOrElse { value.take(8) }

    private const val TAG = "NexusLocalRuntime"
    private val FENCE_START = Regex("^[\\t ]*```.*$")
    private val FENCE_END = Regex("^[\\t ]*```[\\t ]*$")
    private val FENCE_SAME_LINE_EMPTY = Regex("^[\\t ]*```[\\t ]*```[\\t ]*$")
}
