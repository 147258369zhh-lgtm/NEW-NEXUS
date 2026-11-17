package io.github.mangi.eta.agent.terminal

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class LinuxLatestVersion(val id: String, val version: String?, val officialUrl: String,
    val checkedAt: Long, val error: String? = null)

/** Read-only upstream discovery. New binaries still need official digest/size verification before installation. */
internal class LinuxLatestVersionChecker(private val context: Context,
    private val client: OkHttpClient = VerifiedArtifactDownloader.defaultHttpClient()) {
    suspend fun check(id: String): LinuxLatestVersion = withContext(Dispatchers.IO) {
        val url = officialUrl(id)
        val result = runCatching {
            client.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
                check(response.isSuccessful) { "HTTP ${response.code}" }
                val body = response.body.byteStream().use { input ->
                    val buffer = java.io.ByteArrayOutputStream()
                    val chunk = ByteArray(8192)
                    while(buffer.size() <= 2 * 1024 * 1024) { val n = input.read(chunk); if(n < 0) break; buffer.write(chunk, 0, n) }
                    val bytes = buffer.toByteArray()
                    check(bytes.size <= 2 * 1024 * 1024) { "版本元数据过大" }
                    bytes.decodeToString()
                }
                val version = parseVersion(id, body) ?: error("未找到最新正式版本")
                LinuxLatestVersion(id, version, url, System.currentTimeMillis())
            }
        }.getOrElse { LinuxLatestVersion(id, null, url, System.currentTimeMillis(), it.message ?: "版本检查失败") }
        synchronized(lock) {
            val file = file(); file.parentFile?.mkdirs()
            val data = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
            data.put(id, JSONObject().put("version", result.version ?: JSONObject.NULL).put("officialUrl", url)
                .put("checkedAt", result.checkedAt).put("error", result.error ?: JSONObject.NULL))
            val temp = File.createTempFile("latest-", ".tmp", file.parentFile)
            try { temp.writeText(data.toString()); check(temp.renameTo(file) || runCatching { temp.copyTo(file, true); true }.getOrDefault(false)) } finally { temp.delete() }
        }
        result
    }
    fun recorded(): List<LinuxLatestVersion> = runCatching {
        val data = JSONObject(file().readText())
        data.keys().asSequence().map { id -> val row = data.getJSONObject(id)
            LinuxLatestVersion(id, row.optString("version").takeUnless { it == "null" || it.isBlank() }, row.getString("officialUrl"), row.getLong("checkedAt"), row.optString("error").takeUnless { it == "null" || it.isBlank() })
        }.toList()
    }.getOrDefault(emptyList())
    private fun file() = File(context.filesDir, "linux-environment/latest-versions.json")
    companion object {
        private val lock = Any()
        val ids = listOf("alpine", "debian", "node", "uv", "python", "go", "rust", "jdk", "maven", "gradle")
        fun officialUrl(id: String): String = when(id) {
            "alpine" -> "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/latest-releases.yaml"
            "debian" -> "https://www.debian.org/releases/stable/"
            "node" -> "https://nodejs.org/dist/index.json"
            "uv" -> "https://api.github.com/repos/astral-sh/uv/releases/latest"
            "python" -> "https://www.python.org/downloads/"
            "go" -> "https://go.dev/dl/?mode=json"
            "rust" -> "https://static.rust-lang.org/dist/channel-rust-stable.toml"
            "jdk" -> "https://jdk.java.net/"
            "maven" -> "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/maven-metadata.xml"
            "gradle" -> "https://services.gradle.org/versions/current"
            else -> error("未知版本类别")
        }
        internal fun parseVersion(id: String, body: String): String? = when(id) {
            "alpine" -> Regex("(?m)^  version: ([0-9.]+)$").find(body)?.groupValues?.get(1)
            "debian" -> Regex("Debian\\s+(?:GNU/Linux\\s+)?([0-9]+\\.[0-9]+)", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)
            "node" -> (0 until JSONArray(body).length()).asSequence().map { JSONArray(body).getJSONObject(it).getString("version").removePrefix("v") }.firstOrNull { it.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+")) }
            "uv" -> JSONObject(body).takeUnless { it.optBoolean("prerelease") || it.optBoolean("draft") }?.optString("tag_name")?.removePrefix("v")
            "python" -> Regex("Latest Python 3 Release[^<]*(?:</[^>]+>\\s*)*(?:<[^>]+>)*Python ([0-9.]+)").find(body)?.groupValues?.get(1)
                ?: Regex("Download Python ([0-9]+\\.[0-9]+\\.[0-9]+)").find(body)?.groupValues?.get(1)
            "go" -> (0 until JSONArray(body).length()).asSequence().map { JSONArray(body).getJSONObject(it) }.firstOrNull { it.optBoolean("stable") }?.getString("version")?.removePrefix("go")
            "rust" -> Regex("(?s)\\[pkg\\.rust]\\s+version = \"([0-9.]+) ").find(body)?.groupValues?.get(1)
            "jdk" -> Regex("(?s)GA Releases.*?href=\"(?:\\.\\.?/)?([0-9]+)/\"").find(body)?.groupValues?.get(1)
            "maven" -> Regex("<version>([0-9]+\\.[0-9]+\\.[0-9]+)</version>").findAll(body).map { it.groupValues[1] }.maxWithOrNull(compareBy<String> { it.substringBefore('.').toInt() }.thenBy { it.split('.')[1].toInt() }.thenBy { it.substringAfterLast('.').toInt() })
            "gradle" -> JSONObject(body).takeUnless { it.optBoolean("snapshot") || it.optBoolean("nightly") || it.optBoolean("activeRc") }?.optString("version")
            else -> null
        }?.takeIf { it.matches(Regex("[0-9]+(?:\\.[0-9]+){0,4}")) }
    }
}
