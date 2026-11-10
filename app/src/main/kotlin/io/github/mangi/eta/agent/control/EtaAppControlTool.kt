package io.github.mangi.eta.agent.control

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

internal object EtaAppControlTool {
    fun execute(context: Context, args: JSONObject): String = try {
        runBlocking { executeSuspend(context, args) }
    } catch (error: EtaControlException) {
        errorJson(error.code, error.message)
    } catch (error: org.json.JSONException) {
        errorJson("INVALID_ARGUMENT", error.message ?: "参数格式无效")
    } catch (error: IllegalArgumentException) {
        errorJson("INVALID_ARGUMENT", error.message ?: "参数无效")
    } catch (error: Throwable) {
        errorJson("CAPABILITY_FAILED", error.message ?: error.javaClass.simpleName)
    }

    private suspend fun executeSuspend(context: Context, args: JSONObject): String {
        val action = args.optString("action").trim().lowercase()
        return when (action) {
            "list" -> {
                val category = args.optString("category")
                EtaAppControlRegistry.list(category).toJson().toString()
            }
            "search" -> {
                val query = args.optString("query").trim()
                require(query.isNotEmpty()) { "search 需要 query" }
                EtaAppControlRegistry.search(query).toJson().toString()
            }
            "get" -> {
                val id = args.optString("id").trim()
                require(id.isNotEmpty()) { "get 需要 id" }
                EtaAppControlRegistry.get(context, id).toString()
            }
            "execute" -> {
                val id = args.optString("id").trim()
                require(id.isNotEmpty()) { "execute 需要 id" }
                EtaAppControlRegistry.execute(
                    context = context,
                    id = id,
                    parameters = args.optJSONObject("parameters") ?: JSONObject(),
                ).toString()
            }
            "audit" -> {
                EtaAppControlRegistry.audit(context, args.optInt("limit", 50)).toString()
            }
            else -> error("不支持的 eta_control 操作：${action.ifBlank { "(empty)" }}")
        }
    }

    private fun List<EtaControlCapabilityInfo>.toJson(): String = JSONObject()
        .put("ok", true)
        .put(
            "capabilities",
            JSONArray().also { array ->
                forEach { capability -> array.put(capability.toJson()) }
            },
        )
        .toString()

    private fun EtaControlCapabilityInfo.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("category", category)
        .put("summary", summary)
        .put("danger", danger.name)
        .put("parameters", parameters)
        .put("canRead", canRead)

    private fun errorJson(code: String, message: String): String = JSONObject()
        .put("ok", false)
        .put("code", code)
        .put("message", message)
        .toString()
}
