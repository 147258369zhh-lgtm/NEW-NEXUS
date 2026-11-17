package io.github.mangi.eta.agent.dsh

import org.json.JSONArray
import org.json.JSONObject

internal data class DshContribution(val pkg: String, val id: String, val surface: String, val title: String, val icon: String,
    val ready: Boolean, val live: Boolean, val panel: String?, val effects: JSONArray?, val raw: JSONObject)
internal data class DshPlugin(val pkg: String, val title: String, val version: String, val state: String,
    val wants: Set<String>, val contributions: List<DshContribution>, val capabilities: Set<String> = emptySet())
internal data class DshCatalog(val revision: String, val bridge: String, val plugins: List<DshPlugin>)
internal data class DshView(val pkg: String, val id: String, val title: String, val view: JSONObject, val data: Any?, val revision: String?, val live: Boolean)
internal data class DshEffects(val values: List<JSONObject>, val deferred: Boolean = false,
    val gestureDeadlineNanos: Long? = null, val streamId: String? = null)
internal class DshException(val code: String, message: String) : java.io.IOException(message)

/** Independently implemented from the published DSH Go wire contract; no DSH app code is linked. */
internal object DshProtocol {
    fun parse(raw: String, maximumBytes: Int): JSONObject {
        require(raw.toByteArray().size <= maximumBytes) { "DSH JSON 超过大小限制" }
        var depth = 0; var quoted = false; var escaped = false
        for (character in raw) {
            if (quoted) {
                if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
            } else when (character) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 64) { "DSH JSON 嵌套过深" } }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(!quoted && depth == 0)
        return JSONObject(raw)
    }
    val packageId = Regex("(?:@[a-z0-9][a-z0-9._~-]*/)?[a-z0-9][a-z0-9._~-]*")
    val id = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,39}")
    val actionId = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}")
    val requestToken = Regex("[A-Za-z0-9_-]{32}")
    const val FILE_BYTES = 8L * 1024 * 1024
    const val FILE_TOTAL_BYTES = 16L * 1024 * 1024
    val supportedFeatures = setOf("files", "audio", "voice", "micro", "overlay")
    val mediaTypes = setOf("audio/mpeg", "audio/wav", "audio/ogg", "audio/mp4", "audio/aac")
    val surfaces = linkedMapOf("composer.actions" to 2, "message.actions" to 2, "composer.strip" to 1,
        "panels" to 4, "composer.tools" to 2, "session.actions" to 2, "turn.note" to 1,
        "media" to 2, "micro" to 2, "overlays" to 2)
    val supportedSurfaces = surfaces.keys - setOf("media", "micro", "overlays")
    val baseComponents = setOf("stack", "row", "text", "metric", "chart", "compare", "steps", "callout", "options", "confirm",
        "list", "keyValue", "progress", "table", "actions", "image")
    val components: Set<String> get() = baseComponents + DshViewDocument.additions
    fun requiredFeatures(item: DshContribution): Set<String> {
        val values = item.raw.optJSONArray("requires") ?: return emptySet()
        if (values.length() > 16) return setOf("unsupported")
        return (0 until values.length()).map { values.optString(it).ifBlank { "unsupported" } }.toSet()
    }

    fun catalog(root: JSONObject): DshCatalog {
        require(root.optInt("v") == 1) { "DSH 协议版本不受支持" }
        val rows = root.getJSONArray("plugins")
        require(rows.length() <= 64) { "DSH 插件目录超过数量限制" }
        val seen = hashSetOf<String>()
        val plugins = (0 until rows.length()).mapNotNull { index ->
            val row = rows.optJSONObject(index) ?: return@mapNotNull null
            val pkg = row.optString("pkg")
            if (pkg.length > 214 || !packageId.matches(pkg) || !seen.add(pkg)) return@mapNotNull null
            val state = row.optString("state")
            val definitions = row.optJSONObject("contributes") ?: JSONObject()
            val ids = hashSetOf<String>()
            val contributions = if (state != "ok" || !row.optBoolean("native", true)) emptyList() else surfaces.flatMap { (surface, maximum) ->
                val array = definitions.optJSONArray(surface) ?: JSONArray()
                (0 until minOf(array.length(), maximum)).mapNotNull { childIndex ->
                    val child = array.optJSONObject(childIndex) ?: return@mapNotNull null
                    val childId = child.optString("id")
                    if (!id.matches(childId) || !ids.add(childId)) return@mapNotNull null
                    val static = when (val effect = child.opt("effect")) { is JSONArray -> effect; is JSONObject -> JSONArray().put(effect); else -> null }
                    DshContribution(pkg, childId, surface, text(child.opt(if (surface == "panels") "title" else "label"), 80)
                        .ifBlank { text(child.opt("text"), 80).ifBlank { childId } }, child.optString("icon").take(32),
                        child.optBoolean("ready", surface in setOf("media", "micro", "overlays")), child.optBoolean("live"), child.optString("panel").takeIf(id::matches), static, JSONObject(child.toString()))
                }
            }
            val wants = row.optJSONArray("wants") ?: JSONArray()
            val capabilities = row.optJSONArray("capabilities") ?: JSONArray()
            DshPlugin(pkg, text(row.opt("title"), 80).ifBlank { pkg }, row.optString("version").take(64), state,
                (0 until wants.length()).map { wants.optString(it) }.filter { it in setOf("draft", "message", "clipboard") }.toSet(), contributions,
                (0 until minOf(capabilities.length(), 16)).map { capabilities.optString(it) }.filter { it in supportedFeatures }.toSet())
        }
        return DshCatalog(root.optString("rev").take(256), root.optString("bridge").take(64), plugins)
    }

    fun view(root: JSONObject, pkg: String, id: String): DshView {
        require(root.optInt("v") == 1 && root.getString("pkg") == pkg && root.getString("id") == id) { "DSH 面板归属不匹配" }
        val view = root.getJSONObject("view")
        require(view.toString().toByteArray().size <= 32 * 1024 && jsonDepth(view) <= 64) { "DSH 面板超过大小或层级限制" }
        val version = view.optInt("v", 1)
        require(version in 1..2 && view.optString("type") in if (version == 2) components else baseComponents) { "DSH 面板组件尚不受支持" }
        val data = root.opt("data")?.takeUnless { it == JSONObject.NULL }
        require((data?.toString()?.toByteArray()?.size ?: 0) <= 32 * 1024 && jsonDepth(data) <= 64) { "DSH 面板数据超过限制" }
        return DshView(pkg, id, text(root.opt("title"), 80).ifBlank { id }, view, data,
            root.optString("rev").takeIf { root.opt("rev") != JSONObject.NULL && it.isNotBlank() }?.take(256), root.optBoolean("live"))
    }

    fun effects(array: JSONArray, deferred: Boolean = false, static: Boolean = false,
        features: Set<String> = emptySet(), fileResult: Boolean = false): DshEffects {
        val allowed = if (fileResult) setOf("patch", "refresh", "toast")
            else if (deferred) setOf("insert", "copy", "toast", "patch") else if (static) setOf("insert", "copy", "toast", "open", "link", "command")
            else setOf("insert", "copy", "toast", "open", "link", "command", "refresh", "patch") +
                (if ("files" in features) setOf("pickFile", "pickImage") else emptySet()) + (features intersect setOf("audio", "voice", "overlay"))
        val values = (0 until minOf(array.length(), 4)).mapNotNull { index ->
            val value = array.optJSONObject(index) ?: return@mapNotNull null
            val type = value.optString("type")
            if (type !in allowed) return@mapNotNull null
            val result = JSONObject().put("type", type)
            when (type) {
                "audio", "voice", "overlay" -> {
                    val instance = value.optString("instance"); val lease = value.optString("lease"); val operation = value.optString("op")
                    if (!id.matches(instance) || !requestToken.matches(lease)) return@mapNotNull null
                    val operations = when (type) { "audio" -> setOf("play", "pause", "resume", "stop"); "voice" -> setOf("start", "stop"); else -> setOf("show", "hide") }
                    if (operation !in operations) return@mapNotNull null
                    result.put("instance", instance).put("lease", lease).put("op", operation)
                    if (type == "audio" && operation == "play") {
                        val source = value.optString("src")
                        if (!source.startsWith("media:") || !id.matches(source.removePrefix("media:"))) return@mapNotNull null
                        result.put("src", source)
                        if (value.has("key")) {
                            val key = value.opt("key") as? String ?: return@mapNotNull null
                            if (!key.matches(Regex("[A-Za-z0-9_-]{1,128}"))) return@mapNotNull null
                            result.put("key", key)
                        }
                    } else if (type == "audio" && (value.has("src") || value.has("key"))) return@mapNotNull null
                    if (type == "overlay") {
                        val resource = value.optString("id"); if (!id.matches(resource)) return@mapNotNull null
                        result.put("id", resource)
                    }
                }
                "pickFile", "pickImage" -> {
                    val request = value.optString("request"); val action = value.optString("action")
                    val count = value.opt("maxFiles") as? Number ?: return@mapNotNull null
                    val perFile = value.opt("maxBytes") as? Number ?: return@mapNotNull null
                    val total = value.opt("maxTotalBytes") as? Number ?: return@mapNotNull null
                    if (!requestToken.matches(request) || !actionId.matches(action)
                        || count.toDouble() !in 1.0..4.0 || count.toDouble() != count.toInt().toDouble()
                        || perFile.toDouble() !in 1.0..FILE_BYTES.toDouble() || perFile.toDouble() != perFile.toLong().toDouble()
                        || total.toDouble() !in 1.0..FILE_TOTAL_BYTES.toDouble() || total.toDouble() != total.toLong().toDouble()) return@mapNotNull null
                    result.put("request", request).put("action", action).put("maxFiles", count.toInt())
                        .put("maxBytes", perFile.toLong()).put("maxTotalBytes", total.toLong())
                }
                "insert", "copy", "toast" -> {
                    val text = value.optString("text")
                    val maximum = when (type) { "insert" -> 2000; "copy" -> 8000; else -> 40 }
                    if (text.isEmpty() || text.codePointCount(0, text.length) > maximum || '\u0000' in text) return@mapNotNull null
                    result.put("text", text)
                }
                "link" -> {
                    val raw = value.optString("url")
                    val uri = runCatching { java.net.URI(raw) }.getOrNull() ?: return@mapNotNull null
                    if (raw.length > 2048 || uri.scheme != "https" || uri.host.isNullOrBlank() || uri.rawUserInfo != null || raw.any(Char::isISOControl)) return@mapNotNull null
                    result.put("url", raw)
                }
                "open" -> {
                    val page = value.optString("page")
                    if (!(page.startsWith("panel:") && id.matches(page.removePrefix("panel:"))) && !page.matches(Regex("[a-z][a-z0-9-]{0,31}"))) return@mapNotNull null
                    result.put("page", page)
                }
                "command" -> {
                    val name = value.optString("name"); val args = value.optString("args")
                    if (!actionId.matches(name) || args.length > 500 || args.any { it == '\u0000' }) return@mapNotNull null
                    result.put("name", name).put("args", args)
                }
                "refresh", "patch" -> {
                    if (value.has("id")) {
                        val target = value.getString("id"); if (!id.matches(target)) return@mapNotNull null
                        result.put("id", target)
                    }
                    if (type == "patch") {
                        val data = value.optJSONObject("data") ?: return@mapNotNull null
                        if (data.toString().toByteArray().size > 16 * 1024 || jsonDepth(data) > 32) return@mapNotNull null
                        result.put("data", JSONObject(data.toString()))
                    }
                }
            }
            result
        }
        return DshEffects(values, deferred)
    }

    fun scope(raw: JSONObject): JSONObject = JSONObject().also { result ->
        listOf("sessionId", "turnId", "messageId", "callId").forEach { key ->
            raw.optString(key).takeIf { raw.opt(key) is String && it.length in 1..256 && it.none(Char::isISOControl) }?.let { result.put(key, it) }
        }
    }
    fun micro(raw: Any?): JSONObject? {
        val value = raw as? JSONObject ?: return null
        if (value.length() !in 1..2 || value.keys().asSequence().any { it !in setOf("text", "progress") } || value.toString().toByteArray().size > 1024) return null
        if (value.has("text")) {
            val text = value.opt("text") as? String ?: return null
            if (text.codePointCount(0, text.length) > 240) return null
        }
        if (value.has("progress") && !value.isNull("progress")) {
            val progress = (value.opt("progress") as? Number)?.toDouble() ?: return null
            if (!progress.isFinite() || progress !in 0.0..1.0) return null
        }
        return JSONObject(value.toString())
    }
    fun mergePatch(original: Any?, patch: Any?, depth: Int = 0): Any? {
        require(depth <= 32)
        if (patch !is JSONObject) return patch
        val result = if (original is JSONObject) JSONObject(original.toString()) else JSONObject()
        patch.keys().forEach { key ->
            if (key !in setOf("__proto__", "prototype", "constructor")) {
                val value = patch.opt(key)
                if (value == null || value == JSONObject.NULL) result.remove(key) else result.put(key, mergePatch(result.opt(key), value, depth + 1))
            }
        }
        require(result.toString().toByteArray().size <= 32 * 1024)
        return result
    }
    fun binding(value: Any?, data: Any?): Any? {
        if (value !is JSONObject || value.length() != 1 || !value.has("$")) return value
        val path = value.optString("$")
        if (path.length > 256 || !path.startsWith("data")) return null
        val tokens = path.replace(Regex("\\[(\\d+)\\]"), ".$1").split('.')
        if (tokens.first() != "data" || tokens.size > 17) return null
        var current = data
        for (key in tokens.drop(1)) {
            if (key in setOf("__proto__", "prototype", "constructor")) return null
            current = when (val found = current) { is JSONObject -> found.opt(key); is JSONArray -> key.toIntOrNull()?.let(found::opt); else -> null }
        }
        return current?.takeUnless { it == JSONObject.NULL }
    }
    fun text(value: Any?, maximum: Int = 2000): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONObject -> value.optString("zh").ifBlank { value.optString("en") }.take(maximum)
        is Boolean -> if (value) "是" else "否"
        else -> value.toString().take(maximum)
    }
    fun optionKey(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + optionKey(value.opt(it)) }
        is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { optionKey(value.opt(it)) }
        is String -> JSONObject.quote(value)
        else -> value.toString()
    }
    fun optionValues(value: Any?): Map<String, Any> {
        val values = if (value is JSONArray) (0 until minOf(value.length(), 8)).map { value.opt(it) } else listOf(value)
        return values.mapNotNull { it?.takeUnless { it == JSONObject.NULL } }.associateBy { optionKey(it) }
    }
    private fun jsonDepth(value: Any?, depth: Int = 0): Int {
        if (depth > 64) return depth
        return when (value) {
            is JSONObject -> value.keys().asSequence().map { jsonDepth(value.opt(it), depth + 1) }.maxOrNull() ?: depth
            is JSONArray -> (0 until value.length()).maxOfOrNull { jsonDepth(value.opt(it), depth + 1) } ?: depth
            else -> depth
        }
    }
}
