package io.github.mangi.eta.agent.dsh

import org.json.JSONObject

internal interface DshEffectTarget {
    suspend fun capability(effect: JSONObject, origin: DshEffects) { error("此界面不支持媒体能力") }
    fun discardCapability(effect: JSONObject, origin: DshEffects) = Unit
    suspend fun selectFiles(effect: JSONObject, origin: DshEffects): DshEffects
    fun notice(text: String)
    fun copy(text: String)
    suspend fun insert(text: String): Boolean
    fun command(name: String, args: String): Boolean
    fun link(url: String)
    suspend fun open(page: String): Boolean
    suspend fun refresh(id: String)
    fun patch(id: String, data: JSONObject)
}

/** Panels and conversation slots apply the same bounded, gesture-aware effect contract. */
internal object DshEffectRouter {
    suspend fun apply(effects: DshEffects, target: DshEffectTarget) {
        val handled = mutableSetOf<JSONObject>()
        try { for (effect in effects.values) {
            val type = effect.getString("type")
            if (effects.gestureDeadlineNanos != null && System.nanoTime() > effects.gestureDeadlineNanos && type !in setOf("patch", "refresh")) {
                if (type in setOf("audio", "voice", "overlay")) target.discardCapability(effect, effects)
                handled += effect; continue
            }
            when (type) {
                "audio", "voice", "overlay" -> target.capability(effect, effects)
                "pickFile", "pickImage" -> apply(target.selectFiles(effect, effects), target)
                "toast" -> target.notice(effect.getString("text"))
                "copy" -> { target.copy(effect.getString("text")); target.notice("已复制") }
                "insert" -> target.notice(if (target.insert(effect.getString("text"))) "已插入会话草稿" else "没有可接收内容的会话，内容未发送")
                "command" -> if (!target.command(effect.getString("name"), effect.optString("args"))) target.notice("当前会话不支持此命令")
                "link" -> target.link(effect.getString("url"))
                "open" -> if (!target.open(effect.getString("page"))) target.notice("该页面尚未支持")
                "refresh" -> target.refresh(effect.optString("id"))
                "patch" -> target.patch(effect.optString("id"), effect.getJSONObject("data"))
            }
            handled += effect
        } } finally {
            effects.values.filter { it !in handled && it.optString("type") in setOf("audio", "voice", "overlay") }.forEach { target.discardCapability(it, effects) }
        }
    }
}
