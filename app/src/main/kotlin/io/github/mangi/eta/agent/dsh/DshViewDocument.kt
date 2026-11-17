package io.github.mangi.eta.agent.dsh

import org.json.JSONArray
import org.json.JSONObject

internal data class DshViewNode(val source: JSONObject, val path: String, val children: List<DshViewNode>, val groups: List<Pair<String, List<DshViewNode>>>,
    val form: String?, val truncated: Boolean = false)
internal data class DshViewDocument(val root: DshViewNode, val fields: Map<String, DshViewNode>, val v2: Boolean,
    val diagnostics: List<DshViewDiagnostic> = emptyList()) {
    companion object {
        val inputs = setOf("textField", "number", "stepper", "toggle", "slider", "select", "segmented", "chips", "date", "time", "rating")
        val additions = inputs + setOf("form", "tabs", "collapsible", "accordion", "carousel", "grid", "timeline", "badge", "tag", "avatar", "codeBlock", "divider", "spacer")
        private val segment = Regex("[A-Za-z_][A-Za-z0-9_-]{0,63}")
        fun writable(path: String): Boolean {
            val keys = path.split('.')
            return path.length <= 256 && keys.size in 2..17 && keys.first() == "data" && keys.drop(1).all {
                segment.matches(it) && it !in setOf("__proto__", "prototype", "constructor")
            }
        }
        fun parse(root: JSONObject, data: Any?): DshViewDocument {
            val v2 = root.optInt("v", 1) == 2
            var count = 0
            var spans = 0
            val fields = linkedMapOf<String, DshViewNode>()
            val diagnostics = mutableListOf<DshViewDiagnostic>()
            fun read(raw: Any?, path: String, depth: Int, currentForm: String?): DshViewNode? {
                val source = DshProtocol.binding(raw, data) as? JSONObject ?: return null
                if (++count > 120 || depth > 8) {
                    if (diagnostics.size < 64) diagnostics += DshViewDiagnostic(path, "TREE_LIMIT", "控件数量或层级超过限制，已截取")
                    return null
                }
                require(source.optString("role") !in setOf("system", "approval", "authorization", "dialog", "permission"))
                val type = source.optString("type")
                require(type in DshProtocol.baseComponents || v2 && type in additions) { "插件组件尚不受支持" }
                if (v2 && diagnostics.size < 64) diagnostics += DshViewDiagnostics.inspect(source, path, data).take(64 - diagnostics.size)
                val inline = value(source.opt("spans"), data) as? JSONArray
                if (v2 && inline != null) { spans += minOf(inline.length(), 32); require(spans <= 256) { "插件界面行内样式超过数量限制" } }
                val form = if (type == "form") { require(currentForm == null) { "表单不能嵌套" }; path } else currentForm
                val rawChildren = DshProtocol.binding(source.opt("children"), data) as? JSONArray ?: JSONArray()
                val children = (0 until minOf(rawChildren.length(), 32)).mapNotNull { index -> read(rawChildren.opt(index), "$path.children.$index", depth + 1, form) }
                val groups = if (type in setOf("tabs", "accordion", "carousel", "timeline")) {
                    val items = DshProtocol.binding(source.opt("items"), data) as? JSONArray ?: JSONArray()
                    (0 until minOf(items.length(), if (type == "timeline") 24 else 8)).mapNotNull { index ->
                        val group = items.optJSONObject(index) ?: return@mapNotNull null
                        val nested = DshProtocol.binding(group.opt("children"), data) as? JSONArray ?: JSONArray()
                        DshProtocol.text(DshProtocol.binding(group.opt("label"), data), 200) to (0 until minOf(nested.length(), 32)).mapNotNull { child ->
                            read(nested.opt(child), "$path.items.$index.children.$child", depth + 1, form)
                        }
                    }
                } else emptyList()
                val node = DshViewNode(source, path, children, groups, form, children.size < rawChildren.length())
                if (type in inputs) {
                    require(DshProtocol.text(DshProtocol.binding(source.opt("label"), data), 200).isNotBlank()) { "输入项缺少名称" }
                    require(!source.has("password") && !source.has("obscureText"))
                    val bind = source.optString("bind")
                    if (bind.isNotEmpty()) {
                        require(writable(bind) && fields.size < 32 && fields.keys.none { it == bind || it.startsWith("$bind.") || bind.startsWith("$it.") }) { "输入绑定路径无效、重复或冲突" }
                        fields[bind] = node
                    }
                }
                return node
            }
            val result = requireNotNull(read(root, "root", 0, null))
            return DshViewDocument(result, fields, v2, diagnostics.distinct())
        }
        fun value(raw: Any?, data: Any?): Any? = if (raw is JSONObject && raw.length() == 1 && raw.has("="))
            DshFormula.evaluate(raw.optString("="), data) else DshProtocol.binding(raw, data)
        fun resolvedObject(raw: Any?, data: Any?): JSONObject = runCatching {
            fun resolve(source: Any?, depth: Int): Any? {
                require(depth <= 16)
                return when (val found = value(source, data)) {
                    is JSONObject -> JSONObject().apply { found.keys().forEach { key -> if (key !in setOf("__proto__", "constructor", "prototype")) put(key, resolve(found.opt(key), depth + 1) ?: JSONObject.NULL) } }
                    is JSONArray -> { require(found.length() <= 256); JSONArray().apply { for (index in 0 until found.length()) put(resolve(found.opt(index), depth + 1) ?: JSONObject.NULL) } }
                    else -> found
                }
            }
            resolve(raw, 0) as? JSONObject ?: JSONObject()
        }.getOrDefault(JSONObject())
    }
}
