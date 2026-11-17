package io.github.mangi.eta.agent.runtime

import io.github.zhh_lgtm.nexus.kernel.api.UserInputOption
import io.github.zhh_lgtm.nexus.kernel.api.UserInputQuestion
import org.json.JSONArray
import org.json.JSONObject

/** One bounded form contract shared by native requests and protocol adapters. */
internal object RunInputForms {
    fun parse(arguments: JSONObject): List<UserInputQuestion> {
        val rows = arguments.optJSONArray("questions") ?: return emptyList()
        if (rows.length() == 0) return emptyList()
        require(rows.length() in 1..6) { "INPUT_QUESTIONS_INVALID" }
        val ids = hashSetOf<String>()
        return (0 until rows.length()).map { index ->
            val row = rows.getJSONObject(index)
            val id = row.getString("id").trim().also { require(it.length in 1..100 && ids.add(it)) }
            val title = row.optString("question", row.optString("title")).trim().also { require(it.length in 1..2000) }
            val kind = row.optString("kind", "single").also { require(it in setOf("single", "multiple", "text")) }
            val options = row.optJSONArray("options") ?: JSONArray()
            require(options.length() <= 12)
            val optionIds = hashSetOf<String>()
            val choices = (0 until options.length()).map { optionIndex ->
                val option = options.getJSONObject(optionIndex)
                UserInputOption(
                    option.getString("id").also { require(it.length in 1..100 && optionIds.add(it)) },
                    option.getString("label").trim().also { require(it.length in 1..200) },
                    option.optString("description").trim().also { require(it.length <= 1000) },
                )
            }
            val other = row.optBoolean("allow_other", kind == "text")
            require(kind == "text" || choices.isNotEmpty() || other)
            val minimum = row.optInt("min_selections", 0).also { require(it in 0..choices.size) }
            val maximum = if (kind == "single") 1 else row.optInt("max_selections", choices.size.coerceAtLeast(1))
            require(maximum in minimum.coerceAtLeast(1)..12)
            UserInputQuestion(id, title, kind, choices, row.optBoolean("required", true), other,
                minimum, maximum, row.optInt("max_length", 4000).also { require(it in 1..4000) })
        }
    }

    fun encode(questions: List<UserInputQuestion>): JSONArray = JSONArray().also { rows ->
        questions.forEach { q -> rows.put(JSONObject().put("id", q.id).put("question", q.title).put("kind", q.kind)
            .put("required", q.required).put("allow_other", q.allowOther)
            .put("min_selections", q.minSelections).put("max_selections", q.maxSelections).put("max_length", q.maxLength)
            .put("options", JSONArray().also { options -> q.options.forEach { option ->
                options.put(JSONObject().put("id", option.id).put("label", option.label).put("description", option.description))
            } })) }
    }

    fun answer(questions: List<UserInputQuestion>, raw: String): JSONArray {
        require(raw.length <= 32_000)
        val answers = JSONObject(raw).getJSONArray("answers")
        require(answers.length() == questions.size)
        val rows = (0 until answers.length()).map { answers.getJSONObject(it) }
        require(rows.map { it.getString("id") }.toSet() == questions.map { it.id }.toSet())
        require(rows.map { it.getString("id") }.distinct().size == rows.size)
        val result = JSONArray()
        questions.forEach { q ->
            val row = rows.single { it.getString("id") == q.id }
            val choices = row.optJSONArray("selected_ids") ?: JSONArray()
            val selected = (0 until choices.length()).map { choices.getString(it) }
            val text = row.optString("text").trim()
            val other = row.optString("other_text").trim()
            require(selected.distinct().size == selected.size && selected.all { id -> q.options.any { it.id == id } })
            require(text.length <= q.maxLength && other.length <= q.maxLength)
            require(q.allowOther || other.isEmpty())
            if (q.kind == "text") require(selected.isEmpty()) else require(text.isEmpty())
            if (q.kind == "single") require(selected.size <= 1)
            require(selected.size <= q.maxSelections)
            if (selected.isNotEmpty()) require(selected.size >= q.minSelections)
            if (q.required) require(selected.isNotEmpty() || text.isNotEmpty() || other.isNotEmpty())
            result.put(JSONObject().put("id", q.id).put("selected_ids", JSONArray(selected)).put("text", text).put("other_text", other))
        }
        return result
    }

    fun summary(questions: List<UserInputQuestion>, answers: JSONArray): String =
        questions.mapIndexed { index, question ->
            val answer = answers.getJSONObject(index)
            val ids = answer.optJSONArray("selected_ids") ?: JSONArray()
            val selected = (0 until ids.length()).map { ids.getString(it) }
            val labels = question.options.filter { it.id in selected }.map { it.label }
            question.title + ": " + (labels + listOf(answer.optString("text"), answer.optString("other_text"))).filter(String::isNotBlank).joinToString("; ")
        }.joinToString("\n").take(16_000)
}

