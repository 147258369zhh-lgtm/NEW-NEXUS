package io.github.mangi.eta.agent.skill

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class GiteeSkillRepository(val owner: String, val repository: String, val ref: String? = null, val path: String? = null) {
    val slug: String get() = "$owner/$repository"
}
internal data class GiteeSkillCandidate(val name: String, val path: String)
internal data class GiteeSkillInspection(val repository: String, val ref: String, val commitSha: String, val prefix: String?, val candidates: List<GiteeSkillCandidate>)
internal class GiteeSkillSourceException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

internal object GiteeSkillRepositoryParser {
    fun parse(value: String): GiteeSkillRepository = resolve(value, null, null)
    fun resolve(location: String, explicitRef: String?, explicitPath: String?): GiteeSkillRepository {
        val raw = location.trim().removeSuffix(".git").removeSuffix("/")
        val segments = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            val uri = runCatching { URI(raw) }.getOrThrow()
            if (!uri.scheme.equals("https", true) || uri.host != "gitee.com" || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || uri.port !in setOf(-1, 443)) invalid()
            uri.path.trim('/').split('/').filter(String::isNotBlank)
        } else raw.split('/').filter(String::isNotBlank)
        if (segments.size < 2) invalid()
        if (segments.size > 2 && (segments.getOrNull(2) !in setOf("tree", "blob") || segments.size < 4)) invalid()
        val tree = if (segments.getOrNull(2) in setOf("tree", "blob")) 2 else -1
        val owner = segments[0]; val repo = segments[1]
        val urlRef = if (tree == 2) segments.getOrNull(3) else null
        val urlPath = if (tree == 2 && segments.size > 4) segments.drop(4).joinToString("/").removeSuffix("/SKILL.md") else null
        if (urlRef != null && explicitRef != null && urlRef != explicitRef) invalid()
        if (urlPath != null && explicitPath != null && normalizePath(urlPath) != normalizePath(explicitPath)) invalid()
        return GiteeSkillRepository(owner, repo, explicitRef ?: urlRef, (explicitPath ?: urlPath)?.let(::normalizePath))
    }
    fun normalizePath(value: String): String = GitHubSkillRepositoryParser.normalizeRelativePath(value)
    private fun invalid(): Nothing = throw GiteeSkillSourceException("INVALID_GITEE_SOURCE", "仅支持公共 gitee.com 仓库或 owner/project 路径")
}

internal class PublicGiteeSkillSource(cacheRoot: File, baseClient: OkHttpClient) : Closeable {
    private val client = baseClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val cache = File(cacheRoot, "skill-gitee")
    private val active = ConcurrentHashMap.newKeySet<Call>()
    private val closed = AtomicBoolean(false)

    fun inspect(repository: GiteeSkillRepository): GiteeSkillInspection {
        val ref = repository.ref ?: (api(repository) as JSONObject).optString("default_branch").takeIf(String::isNotBlank)
            ?: throw GiteeSkillSourceException("INVALID_GITEE_RESPONSE", "Gitee 未返回默认分支")
        val commit = commit(repository, ref)
        val root = api(repository, "git", "trees", commit, query = mapOf("recursive" to "1")) as? JSONObject
            ?: throw GiteeSkillSourceException("INVALID_GITEE_RESPONSE", "Gitee 未返回目录")
        if (root.optBoolean("truncated", false)) throw GiteeSkillSourceException("REPOSITORY_TREE_TOO_LARGE", "仓库目录过大，请提供明确 Skill 路径")
        val rows = root.optJSONArray("tree") ?: JSONArray(); if (rows.length() > 1000) throw GiteeSkillSourceException("REPOSITORY_TREE_TOO_LARGE", "仓库目录过大，请提供明确 Skill 路径")
        val prefix = repository.path?.takeUnless { it == "." }
        val candidates = buildList {
            for (i in 0 until rows.length()) { val row = rows.optJSONObject(i) ?: continue; if (row.optString("type") !in setOf("blob", "file")) continue
                val file = row.optString("path"); if (file.substringAfterLast('/') != "SKILL.md") continue
                val skillRoot = GitHubSkillRepositoryParser.normalizeRelativePath(file.substringBeforeLast('/', "."))
                if (prefix != null && skillRoot != prefix && !skillRoot.startsWith("$prefix/")) continue
                add(GiteeSkillCandidate(skillRoot.substringAfterLast('/').takeUnless { skillRoot == "." } ?: repository.repository, skillRoot)) }
        }.sortedBy { it.path }
        if (candidates.size > 500) throw GiteeSkillSourceException("TOO_MANY_SKILLS", "候选 Skill 超过 500 个")
        return GiteeSkillInspection(repository.slug, ref, commit, prefix, candidates)
    }

    fun downloadArchive(repository: GiteeSkillRepository): DownloadedGiteeArchive {
        val ref = repository.ref ?: (api(repository) as JSONObject).optString("default_branch").takeIf(String::isNotBlank)
            ?: throw GiteeSkillSourceException("INVALID_GITEE_RESPONSE", "Gitee 未返回默认分支")
        val commit = commit(repository, ref); ensureCache(); val target = File.createTempFile("nexus-skill-gitee-", ".zip", cache)
        return try { val url = HttpUrl.Builder().scheme("https").host("gitee.com").addPathSegment(repository.owner).addPathSegment(repository.repository)
            .addPathSegment("repository").addPathSegment("archive").addPathSegment("$commit.zip").build()
            download(url, target); DownloadedGiteeArchive(target, repository.slug, ref, commit)
        } catch (failure: Throwable) { target.delete(); throw failure }
    }

    override fun close() { if (closed.compareAndSet(false, true)) { active.forEach(Call::cancel); active.clear() } }
    private fun commit(repository: GiteeSkillRepository, ref: String): String {
        if (Regex("[a-f0-9]{40}").matches(ref)) return ref
        val body = api(repository, "commits", ref) as? JSONObject ?: throw GiteeSkillSourceException("INVALID_GITEE_RESPONSE", "Gitee 提交信息无效")
        return body.optString("sha").takeIf { Regex("[a-f0-9]{40}").matches(it) }
            ?: throw GiteeSkillSourceException("INVALID_GITEE_RESPONSE", "Gitee 未返回有效 commit")
    }
    private fun api(repository: GiteeSkillRepository, vararg path: String, query: Map<String, String> = emptyMap()): Any {
        val url = HttpUrl.Builder().scheme("https").host("gitee.com").addPathSegment("api").addPathSegment("v5")
            .addPathSegment("repos").addPathSegment(repository.owner).addPathSegment(repository.repository)
            .apply { path.forEach(::addPathSegment); query.forEach { addQueryParameter(it.key, it.value) } }.build()
        val bytes = execute(url) { response -> read(response, MAX_JSON) }
        return runCatching { org.json.JSONTokener(bytes.toString(Charsets.UTF_8)).nextValue() }
            .getOrElse { throw GiteeSkillSourceException("INVALID_GITEE_RESPONSE", "Gitee 返回无效 JSON", it) }
    }
    private fun download(url: HttpUrl, target: File) = execute(url) { response -> response.body.byteStream().use { input ->
        FileOutputStream(target).use { output -> var total = 0L; val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; if (total > MAX_ARCHIVE) throw GiteeSkillSourceException("ARCHIVE_TOO_LARGE", "Gitee 归档超过 32 MiB"); output.write(buffer, 0, n) } } } }
    private fun <T> execute(url: HttpUrl, block: (okhttp3.Response) -> T): T {
        if (closed.get()) throw GiteeSkillSourceException("GITEE_REQUEST_CANCELLED", "Gitee 请求已取消")
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "Nexus-Capability-Center/1").build()); active += call
        return try { call.execute().use { response -> if (response.isRedirect) throw GiteeSkillSourceException("GITEE_REDIRECT_REJECTED", "Gitee 响应发生重定向")
            if (!response.isSuccessful) throw GiteeSkillSourceException("GITEE_HTTP_ERROR", "Gitee 请求失败（HTTP ${response.code}）"); block(response) }
        } catch (failure: GiteeSkillSourceException) { throw failure } catch (failure: IOException) { throw GiteeSkillSourceException("GITEE_NETWORK_ERROR", "无法访问 Gitee", failure)
        } finally { active -= call }
    }
    private fun read(response: okhttp3.Response, max: Int): ByteArray = response.body.byteStream().use { input ->
        val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(DEFAULT_BUFFER_SIZE); var total = 0
        while (true) { val n = input.read(buffer); if (n < 0) break; total += n; if (total > max) throw GiteeSkillSourceException("GITEE_RESPONSE_TOO_LARGE", "Gitee 响应超过上限"); output.write(buffer, 0, n) }; output.toByteArray() }
    private fun ensureCache() { if (cache.exists()) { if (cache.isFile || java.nio.file.Files.isSymbolicLink(cache.toPath())) throw GiteeSkillSourceException("CACHE_UNSAFE", "Gitee 缓存目录不安全") } else if (!cache.mkdirs()) throw GiteeSkillSourceException("CACHE_UNAVAILABLE", "无法创建 Gitee 缓存") }
    internal data class DownloadedGiteeArchive(val file: File, val repository: String, val ref: String, val commitSha: String) : Closeable { override fun close() { file.delete() } }
    private companion object { const val MAX_JSON = 4 * 1024 * 1024; const val MAX_ARCHIVE = 32L * 1024 * 1024 }
}
