package io.github.mangi.eta.agent.runtime

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.mangi.eta.agent.model.AgentConversationCodec
import io.github.mangi.eta.agent.model.AgentModelClient
import io.github.mangi.eta.agent.model.AgentToolBatchRecovery
import io.github.mangi.eta.agent.workflow.WorkflowRevisionStore
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/** Answer, consumed marker and continuation outbox share a single durable transaction. */
internal class RecoveredRunInputStore(context: Context) : SQLiteOpenHelper(context.applicationContext,
    "nexa_run_input_outbox.db", null, 1) {
    data class Prepared(val requestId: String, val runId: String, val originalRunId: String,
        val messages: List<AgentModelClient.ConversationMessage>, val providerId: String, val model: String,
        val uncertainTools: Set<String>, val exact: Boolean, val toolCallId: String = "", val toolName: String = "",
        val mcpContinuationId: String? = null, val mcpInputRequestId: String? = null, val mcpInputPage: Int = 0,
        val answerResult: JSONObject = JSONObject(), val mcpOriginRunId: String? = null)
    override fun onConfigure(db: SQLiteDatabase) { db.execSQL("PRAGMA synchronous=FULL") }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE input_outbox (request_id TEXT PRIMARY KEY NOT NULL, original_run_id TEXT NOT NULL, run_id TEXT NOT NULL UNIQUE, answer_digest TEXT NOT NULL, payload_ref TEXT NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun consumed(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM input_outbox WHERE request_id=?", arrayOf(id))
        .use { it.moveToFirst() }

    fun consumeTerminal(context: Context, request: RunInputRequest, phase: RunInputPhase) {
        require(phase != RunInputPhase.ANSWERED && phase != RunInputPhase.REQUESTED)
        val result = JSONObject().put("request_id", request.part.requestId).put("status", phase.name.lowercase()).put("ok", false)
        val runId = "input-resume-${UUID.nameUUIDFromBytes(request.part.requestId.toByteArray())}"
        val body = JSONObject().put("request_id", request.part.requestId).put("original_run_id", request.runId)
            .put("result", result).put("run_id", runId)
        val ref = WorkflowRevisionStore(context).save(runId, "input", body)
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(!consumed(request.part.requestId)) { "问题已提交或结束" }
            db.insertOrThrow("input_outbox", null, ContentValues().apply {
                put("request_id", request.part.requestId); put("original_run_id", request.runId); put("run_id", runId)
                put("answer_digest", hash(result.toString())); put("payload_ref", ref); put("state", phase.name.lowercase())
                put("created_at", System.currentTimeMillis())
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    fun prepare(context: Context, request: RunInputRequest, answer: String,
        fallbackHistory: List<AgentModelClient.ConversationMessage> = emptyList()): Prepared {
        require(request.recovered)
        require(request.part.deadlineAtMillis?.let { System.currentTimeMillis() <= it } != false) { "追问已过期，未启动恢复" }
        val answers = if (request.part.questions.isNotEmpty()) RunInputForms.answer(request.part.questions, answer) else null
        require(answers != null || (answer.isNotBlank() && answer.length <= 4000 &&
            (request.part.allowFreeText || answer in request.part.options))) { "回答无效" }
        val original = request.payloadRef?.let { WorkflowRevisionStore(context).readCommitted("input", it) }
        require(original == null || (original.optString("request_id") == request.part.requestId && original.optString("run_id") == request.runId)) { "恢复归属不匹配" }
        val callId = original?.optString("tool_call_id").orEmpty()
        val stored = original?.optString("continuation_messages").orEmpty()
        val base = if (stored.isNotBlank()) AgentConversationCodec.decodeTranscript(stored) else emptyList()
        val toolName = original?.optString("tool_name").orEmpty()
        val mcpId = original?.optString("mcp_continuation_id")?.takeIf(String::isNotBlank)
        val mcpOrigin = original?.optString("mcp_origin_run_id")?.takeIf(String::isNotBlank) ?: request.runId
        val mcpRecoverable = mcpId != null && io.github.mangi.eta.agent.mcp.McpContinuationRecovery.isRecoverable(context, mcpId, mcpOrigin, callId)
        val exact = callId.isNotBlank() && (toolName == RunUserInput.TOOL || mcpRecoverable) && base.any { message ->
            message.toolCallsJson.takeIf(String::isNotBlank)?.let { raw ->
                val calls = JSONArray(raw)
                (0 until calls.length()).any { index -> calls.getJSONObject(index).optString("id") == callId &&
                    calls.getJSONObject(index).optJSONObject("function")?.optString("name") == toolName }
            } == true
        } && base.none { it.role == "tool" && it.toolCallId == callId }
        val result = JSONObject().put("ok", true).put("request_id", request.part.requestId).put("status", "answered")
        if (answers == null) result.put("answer", answer) else result.put("answers", answers)
        val uncertain = linkedSetOf<String>()
        val messages = if (exact) {
            val outstanding = linkedMapOf<String, String>()
            base.forEach { message ->
                if (message.toolCallsJson.isNotBlank()) {
                    val calls = JSONArray(message.toolCallsJson)
                    for (i in 0 until calls.length()) calls.getJSONObject(i).let { call ->
                        outstanding[call.getString("id")] = call.optJSONObject("function")?.optString("name").orEmpty()
                    }
                }
                if (message.role == "tool") outstanding.remove(message.toolCallId)
            }
            outstanding.filterKeys { it != callId }.values.forEach { name ->
                val verdict = io.github.mangi.eta.agent.capability.NexusToolDescriptors.replayVerdict(name)
                if (verdict.decision != io.github.zhh_lgtm.nexus.application.api.ReplayDecision.RESUME_AUTOMATIC) uncertain.add(name)
            }
            if (mcpRecoverable) base else
                AgentToolBatchRecovery.completeInterrupted(base + AgentModelClient.ConversationMessage("tool", result.toString(), toolCallId = callId))
        } else {
            // Legacy or nested protocol state cannot be fabricated into a native tool result.
            uncertain.add("*")
            AgentToolBatchRecovery.completeInterrupted(fallbackHistory) + AgentModelClient.ConversationMessage("user",
                "[明确降级恢复：旧记录缺少原始工具调用或协议续答状态，本消息不是原 tool_result。不得重放先前产生副作用的操作，只能依据现有记录整理答复与待核对项。]\n" +
                    "原问题：${request.part.question}\n用户本次补充：${if (answers == null) answer else answers.toString()}")
        }
        if (io.github.mangi.eta.agent.approval.ApprovalLearningStore(context).rememberedPolicy(request.runId) == null)
            uncertain.add("*")
        val runId = "input-resume-${UUID.nameUUIDFromBytes(request.part.requestId.toByteArray())}"
        val body = JSONObject().put("request_id", request.part.requestId).put("original_run_id", request.runId)
            .put("run_id", runId).put("tool_call_id", callId).put("tool_name", toolName).put("exact", exact)
            .put("provider_id", if (exact) original?.optString("provider_id") else "")
            .put("model", if (exact) original?.optString("model") else "")
            .put("uncertain_tools", JSONArray(uncertain.toList())).put("result", result)
            .put("messages", AgentConversationCodec.encodeTranscriptForStorage(messages))
        if (exact && mcpRecoverable) body.put("mcp_continuation_id", mcpId)
            .put("mcp_origin_run_id", mcpOrigin)
            .put("mcp_input_request_id", original?.optString("mcp_input_request_id"))
            .put("mcp_input_page", original?.optInt("mcp_input_page", 0))
        // Immutable body is written first. Only its hash and committed consumption are in SQLite.
        val ref = WorkflowRevisionStore(context).save(runId, "input", body)
        val db = writableDatabase
        db.beginTransaction()
        try {
            check(!consumed(request.part.requestId)) { "回答已经提交；恢复执行状态需要核对，未重复启动" }
            db.insertOrThrow("input_outbox", null, ContentValues().apply {
                put("request_id", request.part.requestId); put("original_run_id", request.runId); put("run_id", runId)
                put("answer_digest", hash(result.toString())); put("payload_ref", ref); put("state", "claimed")
                put("created_at", System.currentTimeMillis())
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return decode(body)
    }

    /** A process restart cannot reclaim a dispatched continuation whose effects are unknown. */
    fun claimExecution(context: Context, requestId: String, runId: String): Prepared {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ref = db.rawQuery("SELECT payload_ref,state,run_id FROM input_outbox WHERE request_id=?", arrayOf(requestId)).use { rows ->
                check(rows.moveToFirst() && rows.getString(1) == "claimed" && rows.getString(2) == runId) { "恢复已领取或身份不匹配，未重复执行" }
                rows.getString(0)
            }
            val body = WorkflowRevisionStore(context).readCommitted("input", ref)
            check(body.getString("run_id") == runId && body.getString("request_id") == requestId)
            db.update("input_outbox", ContentValues().apply { put("state", "running") }, "request_id=? AND state='claimed'", arrayOf(requestId))
            db.setTransactionSuccessful()
            return decode(body)
        } finally { db.endTransaction() }
    }

    fun finish(runId: String, succeeded: Boolean) {
        writableDatabase.update("input_outbox", ContentValues().apply { put("state", if (succeeded) "completed" else "interrupted") },
            "run_id=? AND state='running'", arrayOf(runId))
    }

    private fun decode(body: JSONObject): Prepared {
        val names = body.getJSONArray("uncertain_tools")
        return Prepared(body.getString("request_id"), body.getString("run_id"), body.getString("original_run_id"),
            AgentConversationCodec.decodeTranscript(body.getString("messages")), body.optString("provider_id"), body.optString("model"),
            (0 until names.length()).map(names::getString).toSet(), body.getBoolean("exact"),
            body.optString("tool_call_id"), body.optString("tool_name"), body.optString("mcp_continuation_id").takeIf(String::isNotBlank),
            body.optString("mcp_input_request_id").takeIf(String::isNotBlank), body.optInt("mcp_input_page", 0), body.getJSONObject("result"),
            body.optString("mcp_origin_run_id").takeIf(String::isNotBlank))
    }
    private fun hash(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
