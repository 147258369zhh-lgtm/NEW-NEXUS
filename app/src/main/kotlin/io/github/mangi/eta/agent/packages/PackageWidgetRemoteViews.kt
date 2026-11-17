package io.github.mangi.eta.agent.packages

import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews
import io.github.mangi.eta.R
import org.json.JSONObject

/** Native launcher views. Widget clicks open the reviewed page; they never replay a JS callback in the background. */
internal class PackageWidgetRemoteViews(private val context: Context, private val id: Int) {
    private val launch = PendingIntent.getActivity(context, id, PackageDesktopWidgetService(context).launch(id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    private var count = 0
    fun status(text: String): RemoteViews = frame().apply { addView(R.id.widget_content, text(text, title = true)) }
    fun build(title: String?, document: PackageUiDocument?): RemoteViews {
        if (document == null) return status("选择插件组件")
        count = 0
        return frame().apply {
            setContentDescription(R.id.widget_root, title ?: "Nexus")
            addView(R.id.widget_content, node(document.tree, 0))
        }
    }
    private fun frame() = RemoteViews(context.packageName, R.layout.package_widget_root).apply {
        removeAllViews(R.id.widget_content)
        setOnClickPendingIntent(R.id.widget_root, launch)
    }
    private fun node(value: JSONObject, depth: Int): RemoteViews {
        require(depth <= 8 && ++count <= 80) { "PACKAGE_WIDGET_TREE_LIMIT" }
        val props = value.getJSONObject("props"); val type = value.getString("type")
        val children = buildList {
            value.optJSONArray("children")?.let { rows -> for (i in 0 until rows.length()) add(rows.getJSONObject(i)) }
            value.optJSONObject("slots")?.let { slots -> for (key in listOf("content", "text", "title", "supportingText")) {
                slots.optJSONArray(key)?.let { rows -> for (i in 0 until rows.length()) add(rows.getJSONObject(i)) }
            } }
        }
        return when (type) {
            "Column", "LazyColumn", "Row", "LazyRow", "Box", "Surface", "Card", "ElevatedCard", "OutlinedCard", "Scaffold", "MaterialTheme" ->
                RemoteViews(context.packageName, if (type in setOf("Row", "LazyRow")) R.layout.package_widget_row else R.layout.package_widget_column).apply {
                    removeAllViews(R.id.widget_children)
                    children.forEach {
                        val child = node(it, depth + if (type in setOf("Row", "LazyRow")) 2 else 1)
                        addView(R.id.widget_children, if (type in setOf("Row", "LazyRow"))
                            RemoteViews(context.packageName, R.layout.package_widget_row_cell).apply { addView(R.id.widget_row_cell, child) } else child)
                    }
                }
            "Text", "Markdown" -> text(props.optString("text"), props = props)
            "Button", "TextButton", "OutlinedButton", "FilledTonalButton", "IconButton", "FilledIconButton" -> {
                fun label(item: JSONObject): String = item.optJSONObject("props")?.optString("text").orEmpty().ifBlank {
                    item.optJSONArray("children")?.let { rows -> (0 until rows.length()).joinToString(" ") { label(rows.getJSONObject(it)) } }.orEmpty()
                }
                text(props.optString("text").ifBlank { children.joinToString(" ", transform = ::label).ifBlank { "打开" } }, button = true)
            }
            "Spacer" -> RemoteViews(context.packageName, R.layout.package_widget_spacer)
            "Divider", "HorizontalDivider", "VerticalDivider" -> RemoteViews(context.packageName, R.layout.package_widget_divider)
            "LinearProgressIndicator", "CircularProgressIndicator" -> RemoteViews(context.packageName, R.layout.package_widget_progress).apply {
                val progress = props.optDouble("progress", props.optDouble("value", Double.NaN))
                setProgressBar(R.id.widget_progress, 100, if (progress.isFinite()) (progress.coerceIn(0.0, 1.0) * 100).toInt() else 0, !progress.isFinite())
            }
            else -> error("PACKAGE_WIDGET_NODE_UNSUPPORTED:$type")
        }
    }
    private fun text(value: String, title: Boolean = false, button: Boolean = false, props: JSONObject = JSONObject()) =
        RemoteViews(context.packageName, R.layout.package_widget_text).apply {
            setTextViewText(R.id.widget_text, value.take(4000))
            setTextViewTextSize(R.id.widget_text, TypedValue.COMPLEX_UNIT_SP,
                props.optDouble("fontSize", if (title) 16.0 else 14.0).takeIf { it.isFinite() }?.coerceIn(11.0, 24.0)?.toFloat() ?: 14f)
            setInt(R.id.widget_text, "setMaxLines", props.optInt("maxLines", if (button) 2 else 4).coerceIn(1, 8))
            if (button) {
                setInt(R.id.widget_text, "setBackgroundResource", R.drawable.package_widget_button)
                setTextColor(R.id.widget_text, context.getColor(R.color.package_widget_accent))
                setOnClickPendingIntent(R.id.widget_text, launch)
            } else parseColor(props.optString("color"))?.let { setTextColor(R.id.widget_text, it) }
            setViewVisibility(R.id.widget_text, if (value.isBlank()) View.GONE else View.VISIBLE)
        }
    private fun parseColor(raw: String): Int? = when {
        raw.startsWith("#") -> runCatching { Color.parseColor(raw) }.getOrNull()
        raw.contains("primary", ignoreCase = true) -> context.getColor(R.color.package_widget_accent)
        raw.contains("variant", ignoreCase = true) -> context.getColor(R.color.package_widget_secondary)
        else -> null
    }
}
