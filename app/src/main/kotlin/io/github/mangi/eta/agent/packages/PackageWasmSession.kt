package io.github.mangi.eta.agent.packages

import android.util.Base64
import com.ai.assistance.operit.util.ToolPkgWasmNative
import org.json.JSONArray
import org.json.JSONObject

internal interface PackageWasmBridge {
    fun start(memoryBytes: Long)
    fun load(bytes: ByteArray): Long
    fun call(handle: Long, name: String, types: IntArray, bits: LongArray): String
    fun interrupt()
    fun finish()
}

private object NativePackageWasmBridge : PackageWasmBridge {
    override fun start(memoryBytes: Long) = ToolPkgWasmNative.start(memoryBytes)
    override fun load(bytes: ByteArray) = ToolPkgWasmNative.load(bytes)
    override fun call(handle: Long, name: String, types: IntArray, bits: LongArray) = ToolPkgWasmNative.call(handle, name, types, bits)
    override fun interrupt() = ToolPkgWasmNative.interrupt()
    override fun finish() = ToolPkgWasmNative.finish()
}

internal class PackageWasmSession(modules: JSONArray, val memoryBytes: Long,
    private val bridge: PackageWasmBridge = NativePackageWasmBridge) : AutoCloseable {
    private data class Module(val bytes: ByteArray, val exports: Set<String>, var handle: Long = 0)
    private val lock = Any()
    private val modules = linkedMapOf<String, Module>()
    @Volatile private var started = false
    @Volatile private var closed = false
    var calls = 0
        private set

    init {
        require(memoryBytes in 8L * 1024 * 1024..128L * 1024 * 1024 && modules.length() in 1..4)
        var bytes = 0L
        for (index in 0 until modules.length()) {
            val row = modules.getJSONObject(index)
            val id = row.getString("id")
            require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) && this.modules.keys.none { it.equals(id, true) })
            val encoded = row.getString("bytes"); require(encoded.length <= 6 * 1024 * 1024)
            val binary = Base64.decode(encoded, Base64.NO_WRAP)
            bytes += binary.size
            require(binary.size >= 8 && bytes <= 4L * 1024 * 1024 && binary.take(8) == listOf<Byte>(0, 97, 115, 109, 1, 0, 0, 0)) { "PACKAGE_WASM_BINARY_INVALID" }
            val names = row.optJSONArray("exports") ?: JSONArray()
            require(names.length() <= 128)
            val exports = (0 until names.length()).map { names.getString(it) }
            require(exports.all { it.length in 1..128 && it.none(Char::isISOControl) } && exports.distinct().size == exports.size)
            this.modules[id] = Module(binary, exports.toSet())
        }
    }

    fun call(request: JSONObject): String = synchronized(lock) {
        check(!closed) { "PACKAGE_WASM_CLOSED" }
        require(++calls <= 256) { "PACKAGE_WASM_CALL_LIMIT" }
        val module = requireNotNull(modules[request.getString("module")]) { "PACKAGE_WASM_MODULE_UNDECLARED" }
        val name = request.getString("export")
        require(name.length in 1..128 && name.none(Char::isISOControl) && (module.exports.isEmpty() || name in module.exports)) { "PACKAGE_WASM_EXPORT_UNDECLARED" }
        val args = arguments(request.optJSONArray("args") ?: JSONArray())
        if (!started) { bridge.start(memoryBytes); started = true }
        if (module.handle == 0L) module.handle = bridge.load(module.bytes).also { require(it != 0L) }
        bridge.call(module.handle, name, args.first, args.second).also {
            require(it.toByteArray().size <= 16 * 1024) { "PACKAGE_WASM_RESULT_LIMIT" }
        }
    }

    fun interrupt() { if (started && !closed) bridge.interrupt() }
    override fun close() = synchronized(lock) {
        if (!closed) { closed = true; try { if (started) bridge.finish() } finally { modules.clear(); started = false } }
    }

    companion object {
        internal fun arguments(values: JSONArray): Pair<IntArray, LongArray> {
            require(values.length() <= 32) { "PACKAGE_WASM_ARGUMENT_LIMIT" }
            val types = IntArray(values.length()); val bits = LongArray(values.length())
            fun finite(raw: Any): Double = when (raw) { is Number -> raw.toDouble(); is String -> raw.toDoubleOrNull(); else -> null }
                ?.takeIf(Double::isFinite) ?: error("PACKAGE_WASM_FINITE_NUMBER_REQUIRED")
            for (index in 0 until values.length()) {
                val item = values.getJSONObject(index); val value = item.get("value")
                when (item.getString("type")) {
                    "i32" -> { val number = finite(value); require(number % 1.0 == 0.0 && number >= Int.MIN_VALUE && number <= Int.MAX_VALUE)
                        types[index] = 1; bits[index] = number.toLong() }
                    "i64" -> {
                        val integer = if (value is String) value.takeIf { it.length <= 20 && it.matches(Regex("-?[0-9]+")) }?.toLongOrNull()
                        else finite(value).takeIf { it % 1.0 == 0.0 && kotlin.math.abs(it) <= 9007199254740991.0 }?.toLong()
                        requireNotNull(integer) { "PACKAGE_WASM_I64_RANGE" }; types[index] = 2; bits[index] = integer
                    }
                    "f32" -> { val number = finite(value).toFloat(); require(number.isFinite()); types[index] = 3; bits[index] = number.toRawBits().toLong() }
                    "f64" -> { types[index] = 4; bits[index] = finite(value).toRawBits() }
                    else -> error("PACKAGE_WASM_ARGUMENT_TYPE")
                }
            }
            return types to bits
        }
    }
}
