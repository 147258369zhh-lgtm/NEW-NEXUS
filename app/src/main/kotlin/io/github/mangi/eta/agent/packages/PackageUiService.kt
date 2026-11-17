package io.github.mangi.eta.agent.packages

import android.content.Context
import io.github.mangi.eta.agent.runtime.AgentRunController
import io.github.mangi.eta.data.model.ConversationSubject
import org.json.JSONArray
import org.json.JSONObject

internal data class PackageUiModule(val record: CapabilityPackage, val tool: PackageTool, val id: String, val route: String,
    val title: String, val params: JSONObject, val messageMenuId: String? = null, val launchSpec: JSONObject = JSONObject(),
    val screenCallback: String? = null)
internal data class PackageUiDocument(val tree: JSONObject, val state: JSONObject, val memo: JSONObject, val effects: List<JSONObject>) {
    val actions: Set<String> get() = buildSet {
        fun visit(value: Any?) {
            when (value) {
                is JSONObject -> { value.optString("__actionId").takeIf { it.matches(Regex("__action_[1-9][0-9]{0,4}")) }?.let(::add); value.keys().forEach { visit(value.opt(it)) } }
                is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index))
            }
        }
        visit(tree)
    }
    companion object {
        internal val supported = setOf("Column", "Row", "Box", "LazyColumn", "LazyRow", "Surface", "Card", "OutlinedCard", "ElevatedCard",
            "Text", "Markdown", "TextField", "OutlinedTextField", "BasicTextField", "Switch", "Checkbox", "Button", "TextButton", "OutlinedButton",
            "FilledTonalButton", "IconButton", "FilledIconButton", "Icon", "Spacer", "Divider", "HorizontalDivider", "VerticalDivider",
            "CircularProgressIndicator", "LinearProgressIndicator", "AlertDialog", "Scaffold", "TopAppBar", "MaterialTheme",
            "Image", "Slider", "RadioButton", "SelectionContainer", "DropdownMenu", "DropdownMenuItem", "Canvas", "TabRow", "ScrollableTabRow", "Tab")
        fun parse(value: JSONObject): PackageUiDocument {
            require(value.toString().toByteArray(Charsets.UTF_8).size <= 60 * 1024) { "PACKAGE_UI_SIZE_LIMIT" }
            var values = 0
            fun data(value: Any?, depth: Int) {
                require(depth <= 32 && ++values <= 12_000) { "PACKAGE_UI_DATA_LIMIT" }
                when (value) {
                    is JSONObject -> value.keys().forEach { key ->
                        require(key !in setOf("__proto__", "prototype", "constructor") && key.length <= 256) { "PACKAGE_UI_DATA_KEY_INVALID" }
                        data(value.get(key), depth + 1)
                    }
                    is JSONArray -> for (index in 0 until value.length()) data(value.get(index), depth + 1)
                    is Number -> require(value.toDouble().isFinite())
                }
            }
            data(value, 0)
            var count = 0
            fun node(value: JSONObject, depth: Int) {
                require(depth <= 20 && ++count <= 256) { "PACKAGE_UI_TREE_LIMIT" }
                val type = value.getString("type")
                require(type in supported) { "插件控件尚未适配：$type" }
                require(value.optBoolean("__composeNode")) { "PACKAGE_UI_NODE_INVALID" }
                require(value.optJSONObject("props") != null) { "PACKAGE_UI_PROPS_REQUIRED" }
                val children = value.optJSONArray("children") ?: JSONArray()
                if (type == "Canvas") PackageCanvasContract.validate(value.getJSONObject("props"))
                for (i in 0 until children.length()) node(children.getJSONObject(i), depth + 1)
                value.optJSONObject("slots")?.let { slots -> slots.keys().forEach { key ->
                    val rows = slots.getJSONArray(key); for (i in 0 until rows.length()) node(rows.getJSONObject(i), depth + 1)
                } }
            }
            val tree = value.getJSONObject("tree"); node(tree, 0)
            val effects = value.optJSONArray("effects") ?: JSONArray()
            require(effects.length() <= 16) { "PACKAGE_UI_EFFECT_LIMIT" }
            for (i in 0 until effects.length()) require(effects.getJSONObject(i).getString("type") in setOf("navigate", "notice")) { "PACKAGE_UI_EFFECT_UNSUPPORTED" }
            return PackageUiDocument(tree, value.getJSONObject("state"), value.getJSONObject("memo"),
                (0 until effects.length()).map(effects::getJSONObject))
        }
    }
}

internal class PackageUiService(context: Context,
    private val streamExecute: ((PackageUiModule, JSONObject, AgentRunController, ConversationSubject?, (io.github.mangi.eta.agent.runtime.AgentEvent) -> Unit) -> String)? = null,
    private val execute: ((PackageUiModule, JSONObject, AgentRunController, ConversationSubject?) -> String)? = null,
) {
    private val context = context.applicationContext
    private val store = CapabilityPackageStore(context)
    fun image(module: PackageUiModule, path: String): ByteArray = store.imageAsset(module.record, path)
    fun modules(id: String? = null): List<PackageUiModule> = store.list().filter { it.enabled && (id == null || it.id == id) }.flatMap { record ->
        val declarations = JSONArray(record.registrations)
        (0 until declarations.length()).mapNotNull { index ->
            val row = declarations.getJSONObject(index)
            val ui = PackageUiRegistration.describe(row) ?: return@mapNotNull null
            val tool = record.tools.singleOrNull { it.callbackKey == ui.callback } ?: return@mapNotNull null
            PackageUiModule(record, tool, ui.id, ui.route, ui.title, ui.params, ui.messageMenuId, screenCallback = ui.screenCallback)
        }
    }
    fun render(module: PackageUiModule, state: JSONObject = module.params, memo: JSONObject = JSONObject(), controller: AgentRunController,
        subject: ConversationSubject? = null): PackageUiDocument = invoke(module, state, memo, null, null, controller, subject)

    fun action(module: PackageUiModule, current: PackageUiDocument, action: String, payload: Any?, controller: AgentRunController,
        subject: ConversationSubject? = null, onFrame: (PackageUiDocument) -> Unit = {}): PackageUiDocument {
        require(action in current.actions) { "插件操作已变化，请重新打开" }
        return invoke(module, current.state, current.memo, action, payload, controller, subject, current.tree, onFrame)
    }
    private fun invoke(module: PackageUiModule, state: JSONObject, memo: JSONObject, action: String?, payload: Any?, controller: AgentRunController,
        subject: ConversationSubject?, tree: JSONObject? = null, onFrame: (PackageUiDocument) -> Unit = {}): PackageUiDocument {
        check(modules(module.record.id).any { it.record.digest == module.record.digest && it.tool == module.tool && it.params.toString() == module.params.toString() }) {
            "插件界面已更新或停用，请重新打开"
        }
        val args = JSONObject().put("state", state).put("memo", memo).put("moduleId", module.id)
            .put("moduleSpec", JSONObject(module.launchSpec.toString()).put("params", module.params).put("id", module.id))
            .put("routes", JSONArray(modules(module.record.id).map { JSONObject().put("routeId", it.route).put("title", it.title)
                .put("runtime", "compose_dsl").put("ownerPackageName", module.record.id) }))
        module.screenCallback?.let { args.put("screenCallback", it) }
        if (action != null) args.put("actionId", action).put("payload", payload ?: JSONObject.NULL).put("expectedTree", tree)
        require(args.toString().toByteArray(Charsets.UTF_8).size <= 192 * 1024) { "插件界面状态过大" }
        var lastSequence = 0
        var frameBytes = 0L
        val active = java.util.concurrent.atomic.AtomicBoolean(true)
        val frameLock = Any()
        val event: (io.github.mangi.eta.agent.runtime.AgentEvent) -> Unit = event@{ frame ->
            if (frame !is io.github.mangi.eta.agent.runtime.AgentEvent.PluginUiFrame || !active.get() || action == null || controller.isCancelled) return@event
            synchronized(frameLock) {
                if (!active.get() || frame.packageId != module.record.id || frame.digest != module.record.digest || frame.moduleId != module.id || frame.sequence <= lastSequence) return@synchronized
                try {
                    check(modules(module.record.id).any { it.tool == module.tool && it.record.digest == module.record.digest }) { "PACKAGE_UI_REVOKED" }
                    lastSequence = frame.sequence; frameBytes += frame.documentJson.toByteArray(Charsets.UTF_8).size
                    require(frameBytes <= 2 * 1024 * 1024) { "PACKAGE_UI_FRAME_LIMIT" }
                    val value = PackageUiDocument.parse(JSONObject(frame.documentJson))
                    require(value.effects.isEmpty()) { "PACKAGE_UI_PREVIEW_EFFECTS_DENIED" }
                    onFrame(value)
                } catch (failure: Exception) {
                    active.set(false)
                    io.github.mangi.eta.core.AndroidAgentLogger.warn("Plugin UI preview rejected: ${failure.javaClass.simpleName}")
                }
            }
        }
        val raw = try { if (streamExecute != null) streamExecute.invoke(module, args, controller, subject, event)
        else if (execute != null) execute.invoke(module, args, controller, subject) else {
            val result = PackageApplicationService(context).runActionReviewed(module.record, module.tool, args, controller, subject, onEvent = event)
            check(result.ok) { result.error?.take(200) ?: "插件界面执行未完成" }
            result.content
        } } finally { active.set(false) }
        val result = JSONObject(raw)
        check(result.optBoolean("ok")) { "插件界面执行失败" }
        return PackageUiDocument.parse(result.getJSONObject("value"))
    }
}
