package io.github.mangi.eta.agent.packages

import org.json.JSONObject

internal object PackageCanvasContract {
    private val types = setOf("line", "rect", "roundRect", "circle", "text", "drawPath", "drawRoundRect", "drawText")
    fun validate(props: JSONObject) {
        val commands = props.optJSONArray("commands") ?: return
        require(commands.length() <= 128) { "插件画布指令过多" }
        var textChars = 0; var pathOps = 0
        for (i in 0 until commands.length()) {
            val item = commands.getJSONObject(i)
            require(item.optString("type") in types && !item.has("brush")) { "插件画布指令尚未支持" }
            textChars += item.optString("text").length
            item.optJSONArray("path")?.let { path ->
                pathOps += path.length()
                for (index in 0 until path.length()) require(path.getJSONObject(index).optString("type") in setOf("moveTo", "lineTo", "cubicTo", "quadTo", "close")) { "插件画布路径无效" }
            }
        }
        require(textChars <= 8000 && pathOps <= 1024) { "插件画布内容超过上限" }
    }
}
