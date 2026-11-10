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
    }

    fun schemas(): JSONArray = JSONArray().also(::appendTo)

    private fun goalSchema(): JSONObject = function(
        GOAL,
        "Create or manage the current conversation Goal. Goal state changes are explicit; completion requires confirmed=true after verification.",
        JSONObject()
            .put("action", enum("Action", listOf("status", "create", "transition")))
            .put("goal_id", string("Existing goal id for transition or status", required = false))
            .put("objective", string("Concrete goal objective", required = false, maxLength = 8_000))
            .put("constraints", stringArray("Constraints that must remain true", required = false))
            .put("completion_criteria", stringArray("Evidence-based completion criteria", required = false))
            .put("transition", enum(
                "Goal state transition",
                listOf("start_planning", "start_running", "start_verification", "pause_for_budget", "wait_for_user", "resume", "complete", "fail", "cancel"),
                required = false,
            ))
            .put("confirmed", boolean("User confirmed final completion", required = false))
            .put("soft_rounds", integer("Soft round budget", 1, 10_000, required = false))
            .put("hard_rounds", integer("Hard round budget", 1, 10_000, required = false))
            .put("soft_tool_calls", integer("Soft tool-call budget", 1, 100_000, required = false))
            .put("hard_tool_calls", integer("Hard tool-call budget", 1, 100_000, required = false)),
        required = arrayOf("action"),
    )

    private fun planSchema(): JSONObject = function(
        PLAN,
        "Read or replace the current versioned Plan. Replacement uses optimistic revision checking.",
        JSONObject()
            .put("action", enum("Action", listOf("status", "replace")))
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
            .put("action", enum("Action", listOf("read", "replace", "append")))
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
