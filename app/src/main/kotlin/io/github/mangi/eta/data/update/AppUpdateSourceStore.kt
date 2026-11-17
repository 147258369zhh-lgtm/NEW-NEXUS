package io.github.mangi.eta.data.update

import android.content.Context
import java.net.URI

internal object AppUpdateSourceStore {
    const val DEFAULT = "https://api.github.com/repos/147258369zhh-lgtm/NEW-NEXUS/releases/latest"
    private const val PREFS = "app_update_source"
    private const val KEY = "url"
    // Updates always use the built-in release endpoint; legacy preferences are ignored.
    @Suppress("UNUSED_PARAMETER")
    fun read(context: Context): String = DEFAULT
    fun save(context: Context, raw: String): String { val value = normalize(raw); context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, value).commit().let { check(it) }; return value }
    fun reset(context: Context) { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).commit() }
    fun normalize(raw: String): String {
        val value = raw.trim(); require(value.length in 1..2048)
        val uri = runCatching { URI(value) }.getOrElse { throw IllegalArgumentException("更新源地址无效") }
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.rawUserInfo == null && uri.rawFragment == null && uri.port in setOf(-1,443)) {
            "更新源必须使用无凭据的 HTTPS 地址"
        }
        return uri.toASCIIString()
    }
}
