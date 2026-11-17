package io.github.mangi.eta.agent.packages

import org.json.JSONObject

internal data class PackageUiDeclaration(val id: String, val route: String, val screen: String?, val title: String,
    val callback: String, val params: JSONObject, val messageMenuId: String? = null, val screenCallback: String? = null)

internal object PackageUiRegistration {
    fun describe(row: JSONObject): PackageUiDeclaration? {
        val dialog = row.optJSONObject("dialog").takeIf { row.optString("kind") == "ChatMessageMenuItem" }
        val source = dialog ?: row.takeIf { it.optString("kind") in PackageExtensionKinds.ui } ?: return null
        val screen = source.opt("screen") as? String
        val expected = "${row.optString("kind")}/${row.optString("id")}/" + if (dialog != null) "dialog/screen" else "screen"
        val screenCallback = source.optJSONObject("screen")?.optString("nexus_callback")?.takeIf { it == expected }
        if (screen == null && screenCallback == null) return null
        val menuId = row.getString("id").takeIf { dialog != null }
        val id = if (menuId != null) "message-dialog-$menuId" else row.getString("id")
        val kind = if (dialog != null) "UiRoute" else row.getString("kind")
        val title = source.opt("title") ?: row.opt("title")
        val text = when (title) { is String -> title; is JSONObject -> title.optString("zh").ifBlank { title.optString("en") }; else -> "" }.ifBlank { row.getString("id") }
        return PackageUiDeclaration(id, source.optString("routeId").ifBlank { source.optString("route").ifBlank { id } }, screen, text,
            "$kind/$id/screen", source.optJSONObject("params") ?: JSONObject(), menuId, screenCallback)
    }
}
