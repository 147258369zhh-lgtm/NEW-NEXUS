package io.github.mangi.eta.ui.screens.capability

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.mangi.eta.agent.dsh.DshProtocol
import io.github.mangi.eta.agent.dsh.DshViewDocument
import io.github.mangi.eta.agent.dsh.DshViewNode
import java.time.LocalDate
import java.time.LocalTime
import org.json.JSONArray
import org.json.JSONObject

internal data class DshFieldReceipt(val revision: Long, val remote: String, val schema: String)
internal data class DshFormSubmission(val value: JSONObject, val fields: Map<String, DshFieldReceipt>)
internal data class DshFieldResult(val value: Any?, val error: String? = null)

/** Drafts belong to this mounted panel; acknowledgements never overwrite a newer edit. */
internal class DshFormState(document: DshViewDocument, data: Any?) {
    private data class Draft(val value: Any?, val revision: Long, val protected: Boolean = false)
    var document = document
        private set
    private var remote: Any? = data
    private val drafts = linkedMapOf<String, Draft>()
    private val inFlight = linkedMapOf<String, DshFieldReceipt>()
    private var nextRevision = 0L
    var version by mutableIntStateOf(0)
        private set
    var errors by mutableStateOf<Map<String, String>>(emptyMap())
        private set
    var failure by mutableStateOf<String?>(null)
        private set

    fun editable(node: DshViewNode) = node.source.optString("bind") in document.fields && DshViewDocument.value(node.source.opt("readOnly"), remote) != true
    fun value(node: DshViewNode): Any? {
        version
        val bind = node.source.optString("bind")
        drafts[bind]?.let { return it.value }
        return rawValue(node, remote)
    }
    private fun rawValue(node: DshViewNode, data: Any?): Any? {
        val bind = node.source.optString("bind")
        val found = if (DshViewDocument.writable(bind)) DshProtocol.binding(JSONObject().put("$", bind), data) else DshViewDocument.value(node.source.opt("value"), data)
        return found ?: when (node.source.optString("type")) {
            "toggle" -> false; "chips" -> if (node.source.optBoolean("multi")) JSONArray() else ""
            "rating" -> 0
            "stepper", "slider" -> runCatching {
                val control = node.copy(source = DshViewDocument.resolvedObject(node.source, data)); val (low, high) = DshInputContract.limits(control)
                if (control.source.optBoolean("range")) JSONArray().put(low).put(high) else 0.0.coerceIn(low, high) }.getOrDefault("")
            else -> ""
        }
    }
    fun edit(node: DshViewNode, value: Any?) {
        check(editable(node))
        val path = node.source.getString("bind")
        drafts[path] = Draft(copy(value), ++nextRevision, path in inFlight || drafts[path]?.protected == true)
        errors = errors - path; failure = null; version++
    }
    fun update(document: DshViewDocument, data: Any?) {
        val oldDocument = this.document
        drafts.keys.toList().forEach { path ->
            val node = document.fields[path]; val old = oldDocument.fields[path]
            if (node == null || old == null || node.source.toString() != old.source.toString()) { drafts.remove(path); inFlight.remove(path) }
            else if (DshProtocol.optionKey(rawValue(node, data)) != DshProtocol.optionKey(rawValue(old, remote))) {
                val request = inFlight[path]
                if (request != null && drafts.getValue(path).revision <= request.revision || request == null && !drafts.getValue(path).protected) drafts.remove(path)
            }
        }
        this.document = document; remote = data
        errors = errors.filterKeys { it in document.fields }; version++
    }
    fun effectiveData(): Any? {
        version
        val result = if (remote is JSONObject) JSONObject(remote.toString()) else JSONObject()
        drafts.forEach { (path, draft) ->
            val keys = path.split('.').drop(1); var target = result
            for (key in keys.dropLast(1)) target = target.optJSONObject(key) ?: JSONObject().also { target.put(key, it) }
            val field = document.fields[path] ?: return@forEach
            val converted = DshInputContract.validate(field, draft.value, remote).value
            target.put(keys.last(), converted ?: JSONObject.NULL)
        }
        return result
    }
    fun submission(form: String? = null, only: String? = null): DshFormSubmission? {
        val selected = document.fields.filter { (path, node) -> editable(node) && if (only != null) path == only else node.form == form }
        val values = JSONObject(); val receipts = linkedMapOf<String, DshFieldReceipt>(); val rejected = linkedMapOf<String, String>()
        selected.forEach { (path, node) ->
            val result = DshInputContract.validate(node, value(node), remote)
            if (result.error != null) rejected[path] = result.error
            else { values.put(path, result.value ?: JSONObject.NULL); receipts[path] = DshFieldReceipt(drafts[path]?.revision ?: 0,
                DshProtocol.optionKey(rawValue(node, remote)), node.source.toString()) }
        }
        errors = errors.filterKeys { it !in selected } + rejected
        if (rejected.isNotEmpty()) { failure = "请检查未填写或格式有误的项目"; return null }
        val payload = if (only == null) JSONObject().put("values", values) else JSONObject().put("bind", only).put("value", values.opt(only) ?: JSONObject.NULL)
        if (payload.toString().toByteArray().size > 4096) { failure = "提交内容超过大小限制"; return null }
        return DshFormSubmission(payload, receipts)
    }
    fun started(submission: DshFormSubmission) {
        inFlight.putAll(submission.fields)
        submission.fields.forEach { (path, receipt) -> drafts[path]?.takeIf { it.revision == receipt.revision }?.let { drafts[path] = it.copy(protected = false) } }
    }
    fun settled(submission: DshFormSubmission, success: Boolean) {
        submission.fields.forEach { (path, receipt) -> if (inFlight[path] == receipt) inFlight.remove(path) }
        submission.fields.forEach { (path, receipt) -> drafts[path]?.takeIf { it.revision > receipt.revision }?.let { drafts[path] = it.copy(protected = true) } }
        if (!success) failure = "未保存，请重试" else failure = null
    }
    fun failed(message: String) { failure = message }
    private fun copy(value: Any?): Any? = when (value) { is JSONObject -> JSONObject(value.toString()); is JSONArray -> JSONArray(value.toString()); else -> value }
}

internal object DshInputContract {
    fun options(node: DshViewNode, data: Any?): Map<String, String> {
        val array = DshViewDocument.value(node.source.opt("options"), data) as? JSONArray ?: return emptyMap()
        require(array.length() <= 24)
        return linkedMapOf<String, String>().apply { for (index in 0 until array.length()) {
            val raw = array.opt(index)
            val label = if (raw is JSONObject) DshProtocol.text(DshViewDocument.value(raw.opt("label"), data), 200) else DshProtocol.text(raw, 200)
            val value = if (raw is JSONObject) DshViewDocument.value(raw.opt("value"), data) as? String ?: label else raw as? String ?: error("Invalid option")
            require(value.length in 1..200 && label.isNotBlank() && !containsKey(value)); put(value, label)
        } }
    }
    fun number(node: DshViewNode, key: String, default: Double): Double {
        val raw = node.source.opt(key) ?: return default
        return raw.toString().toDoubleOrNull()?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 1e12 } ?: error("Invalid numeric constraint")
    }
    fun limits(node: DshViewNode): Pair<Double, Double> {
        val slider = node.source.optString("type") == "slider"
        val low = number(node, "min", if (slider) 0.0 else -1e9)
        val high = number(node, "max", if (slider) 100.0 else 1e9)
        require(high > low); return low to high
    }
    fun textLimit(node: DshViewNode) = node.source.optInt("maxLength", 256).coerceIn(0, 2000)
    fun truncate(raw: String, maximum: Int): String = raw.substring(0, raw.offsetByCodePoints(0, raw.codePointCount(0, raw.length).coerceAtMost(maximum)))
    fun validate(node: DshViewNode, raw: Any?, data: Any?): DshFieldResult = try {
        val node = node.copy(source = DshViewDocument.resolvedObject(node.source, data))
        val type = node.source.getString("type")
        val empty = raw == null || raw == JSONObject.NULL || raw is String && raw.isBlank() || raw is JSONArray && raw.length() == 0
        if (empty) {
            if (node.source.optBoolean("required")) DshFieldResult(null, "此项为必填")
            else DshFieldResult(if (type == "textField" && raw is String || type == "chips" && raw is JSONArray) raw else JSONObject.NULL)
        } else {
            val value: Any = when (type) {
                "toggle" -> raw as? Boolean ?: error("请选择开关状态")
                "textField" -> {
                    val text = raw as? String ?: error("请输入文字")
                    val length = text.codePointCount(0, text.length)
                    require(length in node.source.optInt("minLength", 0).coerceIn(0, 2000)..textLimit(node)) { "文字长度不符合要求" }
                    val pattern = when (node.source.optString("pattern")) {
                        "" -> null; "email" -> Regex("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
                        "integer" -> Regex("-?[0-9]+")
                        "decimal" -> Regex("-?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)")
                        "alphanumeric" -> Regex("[A-Za-z0-9]+")
                        "slug" -> Regex("[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*")
                        else -> error("不支持的校验规则")
                    }
                    require(pattern == null || pattern.matches(text)) { "文字格式不符合要求" }; text
                }
                "number", "stepper", "slider", "rating" -> {
                    val (low, high) = if (type == "rating") 0.0 to node.source.optInt("max", 5).coerceIn(1, 10).toDouble() else limits(node)
                    fun finite(value: Any?): Double = value?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it in low..high } ?: error("数值不在允许范围内")
                    if (type == "slider") {
                        val step = number(node, "step", 1.0); val parts = (high - low) / step
                        require(step >= 1e-9 && parts.isFinite() && parts in 1.0..1000.0 && kotlin.math.abs(parts - kotlin.math.round(parts)) <= 1e-7) { "滑块步长无效" }
                    }
                    if (type == "slider" && node.source.optBoolean("range")) {
                        val range = raw as? JSONArray ?: error("请选择区间")
                        require(range.length() == 2); val start = finite(range.opt(0)); val end = finite(range.opt(1))
                        require(start <= end) { "区间起止顺序有误" }; JSONArray().put(start).put(end)
                    } else finite(raw).also { if (type == "rating") require(it % 1 == 0.0) }
                }
                "select", "segmented", "chips" -> {
                    val allowed = options(node, data)
                    if (type == "chips" && node.source.optBoolean("multi")) {
                        val values = raw as? JSONArray ?: error("请选择选项")
                        val items = (0 until values.length()).map { values.getString(it) }
                        require(items.distinct().size == items.size && items.all { it in allowed }) { "所选选项已失效" }; values
                    } else (raw as? String)?.takeIf { it in allowed } ?: error("请选择有效选项")
                }
                "date" -> {
                    val text = raw as? String ?: error("请选择日期")
                    require(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}").matches(text))
                    val date = LocalDate.parse(text)
                    val low = node.source.optString("minDate").takeIf(String::isNotBlank)?.let(LocalDate::parse) ?: LocalDate.of(1900, 1, 1)
                    val high = node.source.optString("maxDate").takeIf(String::isNotBlank)?.let(LocalDate::parse) ?: LocalDate.of(2100, 12, 31)
                    require(date.year in 1900..2100 && date >= low && date <= high) { "日期不在允许范围内" }; text
                }
                "time" -> {
                    val text = raw as? String ?: error("请选择时间")
                    require(Regex("[0-9]{2}:[0-9]{2}").matches(text)); LocalTime.parse(text); text
                }
                else -> error("输入类型尚不支持")
            }
            DshFieldResult(value)
        }
    } catch (failure: Exception) { DshFieldResult(null, failure.message?.take(100) ?: "输入内容有误") }
}
