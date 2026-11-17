package io.github.mangi.eta.agent.model

import org.json.JSONArray
import org.json.JSONObject

internal object AgentWorkflowToolCatalog {
    const val GOAL = "goal_manage"
    const val PLAN = "plan_manage"
    const val NOTEPAD = "notepad_manage"
    val names = setOf(GOAL, PLAN, NOTEPAD)

    fun appendTo(tools: JSONArray) {
        tools.put(goalSchema())
        tools.put(planSchema())
        tools.put(notepadSchema())
        tools.put(inputSchema())
    }

    fun schemas(): JSONArray = JSONArray().also(::appendTo)

    private fun inputSchema(): JSONObject {
        val optionProperties = JSONObject()
            .put("id", string("Stable option ID", maxLength = 100))
            .put("label", string("Display label", maxLength = 200))
            .put("description", string("Optional option description", required = false, maxLength = 1000))
        val optionSchema = JSONObject().put("type", "object")
            .put("properties", optionProperties)
            .put("required", JSONArray(listOf("id", "label")))
            .put("additionalProperties", false)
        val questionProperties = JSONObject()
            .put("id", string("Stable question ID", maxLength = 100))
            .put("question", string("Question title", maxLength = 2000))
            .put("kind", enum("Selection type", listOf("single", "multiple", "text")))
            .put("required", boolean("An answer is required", required = false))
            .put("allow_other", boolean("Allow a separate written supplement", required = false))
            .put("min_selections", integer("Minimum choices when selecting", 0, 12, required = false))
            .put("max_selections", integer("Maximum choices", 1, 12, required = false))
            .put("max_length", integer("Maximum text length", 1, 4000, required = false))
            .put("options", JSONObject().put("type", "array").put("maxItems", 12).put("items", optionSchema))
        val questionSchema = JSONObject().put("type", "object")
            .put("properties", questionProperties)
            .put("required", JSONArray(listOf("id", "question", "kind")))
            .put("additionalProperties", false)
        return function(
            "request_user_input",
            "Ask the user for a missing decision or information and wait for their answer, then continue the same task. Use concise questions and 2-3 options when useful. Do not request passwords or credentials. Cancellation and timeout return an explicit failure; do not assume an answer or approval.",
            JSONObject().put("question", string("Question for the user", maxLength = 2_000))
                .put("options", JSONObject().put("type", "array").put("maxItems", 8).put("uniqueItems", true)
                    .put("items", string("One suggested answer", maxLength = 200)))
                .put("allow_free_text", boolean("Allow a written answer", required = false).put("default", true))
                .put("questions", JSONObject().put("type", "array").put("maxItems", 6).put("items", questionSchema)),
            required = arrayOf("question"),
        )
    }

    private fun goalSchema(): JSONObject = function(
        GOAL,
        "Create or manage the persistent Goal independently of the current execution/planning mode. Pausing stops new writes and side effects; plan revisions stay available. Starting, resuming and increase_budget follow the Run approval policy: full access needs no app execution prompt, smart uses an independent review, manual asks the user; model arguments never approve or widen authority. Planning mode cannot start/resume execution or increase execution budgets. Revise only a paused goal with expected_revision; it creates a new immutable version and needs a fresh plan decision. After verifying the core criteria, submit completion with verified criteria, explicitly listed noncritical omissions, impact and evidence. Budget increases retain all spent usage and stricter child limits. Request before the hard limit is exhausted; a stopped Run is not replayed.",
        JSONObject()
            .put("action", enum("Action", listOf("status", "revision", "create", "revise", "transition", "increase_budget")))
            .put("revision_ref", string("Immutable revision reference returned by a prior write", required = false))
            .put("goal_id", string("Existing goal id for transition or status", required = false))
            .put("expected_revision", integer("Current goal revision expected by the caller when revising", 0, Int.MAX_VALUE, required = false))
            .put("objective", string("Concrete goal objective", required = false, maxLength = 8_000))
            .put("constraints", stringArray("Constraints that must remain true", required = false))
            .put("completion_criteria", stringArray("Evidence-based completion criteria", required = false))
            .put("completion", JSONObject()
                .put("type", "object")
                .put("description", "Required when completing from the verifying state: verified core criteria, explicitly listed noncritical omissions, impact and evidence")
                .put("properties", JSONObject()
                    .put("core_criteria_verified", boolean("All core completion criteria were verified"))
                    .put("missing_noncritical_items", stringArray("Noncritical items not delivered", required = false))
                    .put("impact", string("Impact of the omissions", required = false, maxLength = 4_000))
                    .put("evidence", stringArray("Evidence supporting completion", required = false)))
                .put("required", JSONArray(listOf("core_criteria_verified")))
                .put("additionalProperties", false))
            .put("transition", enum(
                "Goal state transition",
                listOf("start_planning", "start_running", "start_verification", "pause", "pause_for_budget", "wait_for_user", "resume", "complete", "fail", "cancel"),
                required = false,
            ))
            .put("confirmed", boolean("Legacy hint; never grants approval", required = false))
            .put("soft_rounds", integer("Soft round budget", 1, 10_000, required = false))
            .put("hard_rounds", integer("Hard round budget", 1, 10_000, required = false))
            .put("soft_tool_calls", integer("Soft tool-call budget", 1, 100_000, required = false))
            .put("hard_tool_calls", integer("Hard tool-call budget", 1, 100_000, required = false))
            .put("soft_duration_ms", integer("Soft duration in milliseconds", 1, Int.MAX_VALUE, required = false))
            .put("hard_duration_ms", integer("Hard duration in milliseconds", 1, Int.MAX_VALUE, required = false))
            .put("cost_limit_micros", integer("Cumulative reported cost ceiling in millionths of the selected currency", 1, Int.MAX_VALUE, required = false))
            .put("cost_currency", string("ISO currency matching configured model prices, default USD", required = false, maxLength = 3)),
        required = arrayOf("action"),
    )

    private fun planSchema(): JSONObject = function(
        PLAN,
        "Read or replace the current versioned Plan. Replacement uses optimistic revision checking.",
        JSONObject()
            .put("action", enum("Action", listOf("status", "revision", "replace")))
            .put("revision_ref", string("Immutable revision reference returned by a prior write", required = false))
            .put("goal_id", string("Goal linked to this plan", required = false))
            .put("plan_id", string("Existing plan id when replacing", required = false))
            .put("expected_revision", integer("Current revision expected by the caller", 0, Int.MAX_VALUE, required = false))
            .put("steps", typedArray(
                "Ordered plan steps",
                JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("id", string("Stable step id"))
                        .put("title", string("Short title"))
                        .put("description", string("What will be done", required = false))
                        .put("depends_on", stringArray("Existing step ids", required = false))
                        .put("required_capabilities", stringArray("Required capabilities", required = false))
                        .put("effect", enum("Effect class", EffectNames))
                        .put("estimated_cost", string("Estimated cost", required = false))
                        .put("verification", string("How success is verified"))
                        .put("reversible", boolean("Whether the step is reversible")))
                    .put("required", JSONArray(listOf("id", "title", "verification", "reversible")))
                    .put("additionalProperties", false),
                required = false,
            )),
        required = arrayOf("action"),
    )

    private fun notepadSchema(): JSONObject = function(
        NOTEPAD,
        "Read or edit the current conversation notepad. Every write creates a monotonic revision and requires expected_revision.",
        JSONObject()
            .put("action", enum("Action", listOf("read", "revision", "replace", "append")))
            .put("revision_ref", string("Immutable revision reference returned by a prior write", required = false))
            .put("content", string("New or appended content", required = false, maxLength = 1_000_000))
            .put("expected_revision", integer("Revision observed before writing", 0, Int.MAX_VALUE, required = false)),
        required = arrayOf("action"),
    )

    private fun function(
        name: String,
        description: String,
        properties: JSONObject,
        required: Array<String>,
    ) = JSONObject()
        .put("type", "function")
        .put("function", JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", JSONObject()
                .put("type", "object")
                .put("properties", properties)
                .put("required", JSONArray(required.toList()))
                .put("additionalProperties", false)))

    private fun string(description: String, required: Boolean = true, maxLength: Int = 8_000) = JSONObject()
        .put("type", "string")
        .put("description", description)
        .apply { if (required) put("minLength", 1) }
        .put("maxLength", maxLength)

    private fun boolean(description: String, required: Boolean = true) = JSONObject()
        .put("type", "boolean")
        .put("description", description)
        .apply { if (!required) put("default", false) }

    private fun integer(description: String, minimum: Int, maximum: Int, required: Boolean = true) = JSONObject()
        .put("type", "integer")
        .put("description", description)
        .put("minimum", minimum)
        .put("maximum", maximum)

    private fun enum(description: String, values: List<String>, required: Boolean = true) = JSONObject()
        .put("type", "string")
        .put("description", description)
        .put("enum", JSONArray(values))
        .apply { if (!required) put("default", values.first()) }

    private fun stringArray(description: String, required: Boolean = true) = JSONObject()
        .put("type", "array")
        .put("description", description)
        .put("items", JSONObject().put("type", "string").put("minLength", 1))
        .put("maxItems", 200)
        .apply { if (!required) put("default", JSONArray()) }

    private fun typedArray(description: String, item: JSONObject, required: Boolean = true) = JSONObject()
        .put("type", "array")
        .put("description", description)
        .put("items", item)
        .put("maxItems", 100)

    private val EffectNames = listOf(
        "READ", "REVERSIBLE_WRITE", "IDEMPOTENT_WRITE", "NON_IDEMPOTENT_WRITE",
        "DESTRUCTIVE", "EXTERNAL_SIDE_EFFECT", "GUI_ACTION", "HIGH_RISK",
    )
}
