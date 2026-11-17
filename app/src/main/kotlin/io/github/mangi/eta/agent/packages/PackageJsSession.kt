package io.github.mangi.eta.agent.packages

import com.ai.assistance.operit.core.tools.javascript.QuickJsNativeRuntime
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/** One invocation and one heap. The caller owns the isolated process and its hard deadline. */
internal class PackageJsSession(
    memoryBytes: Long,
    wasmModules: JSONArray = JSONArray(),
    private val providerMode: Boolean = false,
    private val intermediateMode: Boolean = false,
    private val host: (String, JSONObject) -> JSONObject,
) : Closeable {
    private data class Timer(val id: Int, var at: Long, val period: Long)
    private val cancelled = AtomicBoolean(false)
    private var hostCalls = 0
    private var providerCalls = 0
    private var intermediateCalls = 0
    private val timers = linkedMapOf<Int, Timer>()
    private val wasmMemory = if (wasmModules.length() > 0) (memoryBytes / 2).also { require(it >= 8L * 1024 * 1024) { "PACKAGE_WASM_REQUIRES_16_MB" } } else 0
    private val wasm = if (wasmMemory > 0) PackageWasmSession(wasmModules, wasmMemory) else null
    private val runtime = try { QuickJsNativeRuntime.create(object : QuickJsNativeRuntime.HostBridge {
        override fun onCall(method: String, argsJson: String?): String? {
            check(!cancelled.get()) { "PACKAGE_CANCELLED" }
            val args = JSONArray(argsJson ?: "[]")
            return when {
                method.startsWith("console.") -> null
                method == "scheduleTimer" -> {
                    require(timers.size < 128) { "PACKAGE_TIMER_LIMIT" }
                    val delay = args.optLong(1).coerceIn(0, 120_000)
                    val id = args.getInt(0)
                    timers[id] = Timer(id, android.os.SystemClock.elapsedRealtime() + delay,
                        if (args.optBoolean(2)) maxOf(10, delay) else 0)
                    null
                }
                method == "cancelTimer" -> { timers.remove(args.getInt(0)); null }
                method == "nexusInvoke" -> {
                    val request = JSONObject(args.getString(0))
                    require(request.toString().toByteArray().size <= 64 * 1024) { "PACKAGE_BRIDGE_INPUT_LIMIT" }
                    val name = request.getString("tool")
                    if (providerMode && name in setOf("package:provider-http-next", "package:provider-output"))
                        require(++providerCalls <= 1024) { "PACKAGE_PROVIDER_FRAME_LIMIT" }
                    else if (intermediateMode && name in setOf("package:message-output", "package:ui-frame"))
                        require(++intermediateCalls <= 1024) { "PACKAGE_INTERMEDIATE_FRAME_LIMIT" }
                    else require(++hostCalls <= 64) { "PACKAGE_HOST_CALL_LIMIT" }
                    host(name, request.optJSONObject("arguments") ?: JSONObject()).toString()
                }
                method == "nexusWasm" -> {
                    val raw = args.getString(0)
                    require(raw.toByteArray().size <= 64 * 1024) { "PACKAGE_WASM_ARGUMENT_LIMIT" }
                    requireNotNull(wasm) { "PACKAGE_WASM_MODULE_UNDECLARED" }.call(JSONObject(raw))
                }
                else -> error("PACKAGE_HOST_METHOD_UNAVAILABLE:$method")
            }
        }
    }, memoryBytes - wasmMemory) } catch (failure: Throwable) { wasm?.close(); throw failure }

    fun interrupt() { cancelled.set(true); runtime.interrupt(); wasm?.interrupt() }

    fun invoke(bootstrap: String, httpSdk: String, scripts: JSONObject, entry: String, function: String, arguments: JSONObject, deadline: Long): JSONObject {
        runtime.installCompatLayerOrThrow()
        evaluate(bootstrap, "nexus-package-sdk.js")
        evaluate(com.ai.assistance.operit.core.tools.javascript.OperitSdkScripts.versioning(), "operit-api-versions.js")
        evaluate(com.ai.assistance.operit.core.tools.javascript.OperitSdkScripts.tools(), "operit-tools.js")
        evaluate(httpSdk, "operit-http.js")
        if (function == "__nexus_ui__") evaluate(com.ai.assistance.operit.core.tools.javascript.OperitSdkScripts.compose(), "operit-compose-context.js")
        evaluate("Tools.call = async function(name,args) { return globalThis.__nexusNativeCall(name,args); };", "nexus-native-tools.js")
        evaluate("globalThis.__nexusScripts = $scripts; globalThis.__nexusInvoke(${JSONObject.quote(entry)}, ${JSONObject.quote(function)}, $arguments);", entry)
        while (true) {
            check(!cancelled.get()) { "PACKAGE_CANCELLED" }
            check(android.os.SystemClock.elapsedRealtime() < deadline) { "PACKAGE_TIMEOUT" }
            runtime.executePendingJobs(128)
            val now = android.os.SystemClock.elapsedRealtime()
            timers.values.filter { it.at <= now }.toList().forEach { timer ->
                if (timer.period == 0L) timers.remove(timer.id) else timer.at = now + timer.period
                val result = runtime.dispatchTimer(timer.id)
                check(result.success) { result.errorMessage ?: "PACKAGE_TIMER_FAILED" }
            }
            val state = runtime.eval("globalThis.__nexusResult", "package-result")
            check(state.success) { state.errorMessage ?: "PACKAGE_RESULT_FAILED" }
            val raw = state.valueJson ?: "{}"
            require(raw.toByteArray().size <= 64 * 1024) { "PACKAGE_OUTPUT_LIMIT" }
            val result = JSONObject(raw)
            if (result.optBoolean("done")) return result.apply {
                val memory = runtime.getMemoryUsage()
                put("runtime", JSONObject().put("engine", "quickjs").put("memory_bytes", memory.memoryUsedBytes)
                    .put("peak_bytes", memory.peakMallocSizeBytes).put("host_calls", hostCalls).put("provider_frames", providerCalls)
                    .put("intermediate_frames", intermediateCalls)
                    .put("wasm_pool_bytes", wasmMemory).put("wasm_calls", wasm?.calls ?: 0))
            }
            Thread.sleep(5)
        }
    }

    private fun evaluate(script: String, name: String) {
        check(!cancelled.get()) { "PACKAGE_CANCELLED" }
        val result = runtime.eval(script, name)
        check(result.success) { result.errorMessage ?: "PACKAGE_SCRIPT_FAILED" }
    }

    override fun close() { timers.clear(); try { wasm?.close() } finally { runtime.close() } }
}
