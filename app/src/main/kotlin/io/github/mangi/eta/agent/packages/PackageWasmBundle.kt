package io.github.mangi.eta.agent.packages

import android.util.Base64
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Manifest validation does not instantiate modules or execute their initialization functions. */
internal object PackageWasmBundle {
    fun modules(directory: File, manifest: JSONObject?, includeBytes: Boolean): JSONArray {
        val rows = manifest?.optJSONArray("wasm_modules") ?: return JSONArray()
        require(rows.length() <= 4) { "WASM 模块数量超过限制" }
        val ids = hashSetOf<String>()
        var total = 0L
        return JSONArray().apply {
            for (index in 0 until rows.length()) {
                val row = rows.getJSONObject(index)
                val id = row.getString("id").trim()
                require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,95}")) && ids.add(id.lowercase(java.util.Locale.ROOT))) { "WASM 模块名称无效或重复" }
                val path = row.getString("path")
                require(path.endsWith(".wasm", true) && !path.startsWith('/') && ':' !in path && '\\' !in path
                    && path.split('/').all { it.isNotBlank() && it !in setOf(".", "..") }) { "WASM 模块路径无效" }
                val file = File(directory, path).canonicalFile
                require(file.toPath().startsWith(directory.canonicalFile.toPath()) && file.isFile) { "WASM 模块不存在" }
                total += file.length()
                require(file.length() in 8..4L * 1024 * 1024 && total <= 4L * 1024 * 1024) { "WASM 模块合计超过 4 MiB" }
                val header = file.inputStream().use { it.readNBytes(8) }
                require(header.contentEquals(byteArrayOf(0, 97, 115, 109, 1, 0, 0, 0))) { "WASM 文件格式有误" }
                val exports = row.optJSONArray("exports") ?: JSONArray()
                require(exports.length() <= 128)
                val names = (0 until exports.length()).map { exports.getString(it).trim() }
                require(names.all { it.length in 1..128 && it.none(Char::isISOControl) } && names.distinct().size == names.size) { "WASM 导出声明无效或重复" }
                put(JSONObject().put("id", id).put("exports", JSONArray(names)).apply {
                    if (includeBytes) put("bytes", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
                })
            }
        }
    }
}
