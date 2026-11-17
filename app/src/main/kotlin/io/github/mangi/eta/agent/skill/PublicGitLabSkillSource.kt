package io.github.mangi.eta.agent.skill

import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

internal data class GitLabSkillRepository(val projectPath: String, val ref: String? = null, val path: String? = null) {
    val slug: String get() = projectPath
}

internal data class GitLabSkillCandidate(val name: String, val path: String)
internal data class GitLabSkillInspection(
    val repository: String, val ref: String, val commitSha: String, val prefix: String?, val candidates: List<GitLabSkillCandidate>,
)

internal class GitLabSkillSourceException(val code: String, message: String, cause: Throwable? = null) : Exception(message, cause)

internal object GitLabSkillRepositoryParser {
    private val sha = Regex("[a-f0-9]{40}")
    fun parse(value: String): GitLabSkillRepository = resolve(value, null, null)
    fun resolve(location: String, explicitRef: String?, explicitPath: String?): GitLabSkillRepository {
        val raw = location.trim().removeSuffix(".git").removeSuffix("/")
        val segments: List<String> = if (raw.startsWith("http://") || raw.startsWith("https://")) {
            val uri = runCatching { URI(raw) }.getOrThrow()
            if (!uri.scheme.equals("https", true) || uri.host != "gitlab.com" || uri.rawUserInfo != null ||
                uri.rawQuery != null || uri.rawFragment != null || uri.port !in setOf(-1, 443)) invalid()
            uri.path.trim('/').split('/').filter(String::isNotBlank)
        } else raw.split('/').filter(String::isNotBlank)
        if (segments.size < 2) invalid()
        val tree = segments.indexOf("-")
        val project = if (tree >= 2) segments.subList(0, tree) else segments
        val urlRef = if (tree >= 2 && segments.getOrNull(tree + 1) in setOf("tree", "blob")) segments.getOrNull(tree + 2) else null
        val urlPath = if (tree >= 2 && segments.getOrNull(tree + 1) in setOf("tree", "blob") && segments.size > tree + 3) {
            segments.subList(tree + 3, segments.size).joinToString("/").removeSuffix("/SKILL.md")
        } else null
        if (urlRef != null && explicitRef != null && urlRef != explicitRef) invalid()
        if (urlPath != null && explicitPath != null && normalizePath(urlPath) != normalizePath(explicitPath)) invalid()
        return GitLabSkillRepository(project.joinToString("/"), explicitRef ?: urlRef,
            (explicitPath ?: urlPath)?.let(::normalizePath))
    }
    fun normalizePath(value: String): String = GitHubSkillRepositoryParser.normalizeRelativePath(value)
    private fun invalid(): Nothing = throw GitLabSkillSourceException("INVALID_GITLAB_SOURCE", "仅支持公共 gitlab.com 仓库或 group/project 路径")
}

/** Public-only GitLab adapter. Search pins commits and archives are fetched by immutable SHA. */
internal class PublicGitLabSkillSource(cacheRoot: File, baseClient: OkHttpClient) : Closeable {
    private val client = baseClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val cache = File(cacheRoot, "skill-gitlab")
    private val active = ConcurrentHashMap.newKeySet<Call>()
    private val closed = AtomicBoolean(false)

    fun inspect(repository: GitLabSkillRepository): GitLabSkillInspection {
        val ref = repository.ref ?: defaultBranch(repository)
        val commit = commit(repository, ref)
        val files = ArrayList<JSONObject>()
        for (page in 1..10) {
            val rows = (getJson(url(repository, "repository", "tree", query = mapOf(
                "ref" to commit, "recursive" to "true", "per_page" to "100", "page" to page.toString(),
            )), MAX_JSON) as? JSONArray) ?: JSONArray()
            for (i in 0 until rows.length()) rows.optJSONObject(i)?.let(files::add)
            if (rows.length() < 100) break
        }
        if (files.size >= 1_000) throw GitLabSkillSourceException("REPOSITORY_TREE_TOO_LARGE", "仓库目录过大，请提供明确 Skill 路径")
        val prefix = repository.path?.takeUnless { it == "." }
        val candidates = files.mapNotNull { row ->
            if (row.optString("type") != "blob") return@mapNotNull null
            val file = row.optString("path")
            if (file.substringAfterLast('/') != "SKILL.md") return@mapNotNull null
            val root = GitHubSkillRepositoryParser.normalizeRelativePath(file.substringBeforeLast('/', "."))
            if (prefix != null && root != prefix && !root.startsWith("$prefix/")) return@mapNotNull null
            GitLabSkillCandidate(root.substringAfterLast('/').takeUnless { root == "." } ?: repository.projectPath.substringAfterLast('/'), root)
        }.sortedBy { it.path }
        if (candidates.size > 500) throw GitLabSkillSourceException("TOO_MANY_SKILLS", "候选 Skill 超过 500 个")
        return GitLabSkillInspection(repository.slug, ref, commit, prefix, candidates)
    }

    fun downloadArchive(repository: GitLabSkillRepository): DownloadedGitLabArchive {
        val ref = repository.ref ?: defaultBranch(repository)
        val commit = commit(repository, ref)
        ensureCache()
        val target = File.createTempFile("nexus-skill-gitlab-", ".zip", cache)
        return try {
            download(url(repository, "repository", "archive.zip", query = mapOf("sha" to commit)), target)
            DownloadedGitLabArchive(target, repository.slug, ref, commit)
        } catch (failure: Throwable) { target.delete(); throw failure }
    }

    override fun close() { if (closed.compareAndSet(false, true)) { active.forEach(Call::cancel); active.clear() } }

    private fun defaultBranch(repository: GitLabSkillRepository): String =
        (getJson(url(repository), MAX_JSON) as? JSONObject)?.optString("default_branch")?.takeIf(String::isNotBlank)
            ?: throw GitLabSkillSourceException("INVALID_GITLAB_RESPONSE", "GitLab 未返回默认分支")

    private fun commit(repository: GitLabSkillRepository, ref: String): String {
        if (Regex("[a-f0-9]{40}").matches(ref)) return ref
        val rows = getJson(url(repository, "repository", "commits", query = mapOf("ref_name" to ref, "per_page" to "1")), MAX_JSON) as? JSONArray
        return rows?.optJSONObject(0)?.optString("id")?.takeIf { Regex("[a-f0-9]{40}").matches(it) }
            ?: throw GitLabSkillSourceException("INVALID_GITLAB_RESPONSE", "GitLab 未返回有效 commit")
    }

    private fun url(repository: GitLabSkillRepository, vararg path: String, query: Map<String, String> = emptyMap()): HttpUrl =
        HttpUrl.Builder().scheme("https").host("gitlab.com").addPathSegment("api").addPathSegment("v4")
            .addPathSegment("projects").addPathSegment(repository.projectPath)
            .apply { path.forEach(::addPathSegment); query.forEach { addQueryParameter(it.key, it.value) } }.build()

    private fun getJson(url: HttpUrl, max: Long): Any {
        val bytes = execute(url) { response -> read(response, max) }
        return runCatching { org.json.JSONTokener(bytes.toString(Charsets.UTF_8)).nextValue() }
            .getOrElse { throw GitLabSkillSourceException("INVALID_GITLAB_RESPONSE", "GitLab 返回无效 JSON", it) }
    }

    private fun download(url: HttpUrl, target: File) = execute(url) { response ->
        response.body.byteStream().use { input -> FileOutputStream(target).use { output ->
            var total = 0L; val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val count = input.read(buffer); if (count < 0) break; total += count
                if (total > MAX_ARCHIVE) throw GitLabSkillSourceException("ARCHIVE_TOO_LARGE", "GitLab 归档超过 32 MiB")
                output.write(buffer, 0, count) }
        } }
    }

    private fun <T> execute(url: HttpUrl, block: (okhttp3.Response) -> T): T {
        if (closed.get()) throw GitLabSkillSourceException("GITLAB_REQUEST_CANCELLED", "GitLab 请求已取消")
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "Nexus-Capability-Center/1").build())
        active += call
        return try { call.execute().use { response ->
            if (response.isRedirect) throw GitLabSkillSourceException("GITLAB_REDIRECT_REJECTED", "GitLab 响应发生重定向")
            if (!response.isSuccessful) throw GitLabSkillSourceException("GITLAB_HTTP_ERROR", "GitLab 请求失败（HTTP ${response.code}）")
            block(response)
        } } catch (failure: GitLabSkillSourceException) { throw failure
        } catch (failure: IOException) { throw GitLabSkillSourceException("GITLAB_NETWORK_ERROR", "无法访问 GitLab", failure)
        } finally { active -= call }
    }

    private fun read(response: okhttp3.Response, max: Long): ByteArray {
        if (response.body.contentLength() > max) throw GitLabSkillSourceException("GITLAB_RESPONSE_TOO_LARGE", "GitLab 响应超过上限")
        return response.body.byteStream().use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(DEFAULT_BUFFER_SIZE); var total = 0
            while (true) { val count = input.read(buffer); if (count < 0) break; total += count
                if (total > max) throw GitLabSkillSourceException("GITLAB_RESPONSE_TOO_LARGE", "GitLab 响应超过上限"); output.write(buffer, 0, count) }
            output.toByteArray()
        }
    }

    private fun ensureCache() {
        val p = cache.toPath()
        if (Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(p) || !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)) throw GitLabSkillSourceException("CACHE_UNSAFE", "GitLab 缓存目录不安全")
        } else if (!cache.mkdirs()) throw GitLabSkillSourceException("CACHE_UNAVAILABLE", "无法创建 GitLab 缓存")
    }

    internal data class DownloadedGitLabArchive(val file: File, val repository: String, val ref: String, val commitSha: String) : Closeable {
        override fun close() { file.delete() }
    }
    private companion object { const val MAX_JSON = 4L * 1024 * 1024; const val MAX_ARCHIVE = 32L * 1024 * 1024 }
}
