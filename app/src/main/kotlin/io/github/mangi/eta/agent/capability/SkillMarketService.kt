package io.github.mangi.eta.agent.capability

import android.content.Context
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.skill.GitHubSkillRepository
import io.github.mangi.eta.agent.skill.GitHubSkillRepositoryParser
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillParser
import io.github.mangi.eta.agent.skill.SkillProvenance
import io.github.mangi.eta.agent.skill.SkillPrerequisiteStatus
import io.github.mangi.eta.agent.skill.SkillReadinessInspector
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.SkillVersionService
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * Skill 市场聚合器。每个来源独立更新状态，失败只影响当前来源。
 * 搜索不执行 Skill，也不把远端内容放入 Agent 上下文。
 */
internal class SkillMarketService(
    context: Context,
    baseClient: OkHttpClient = AgentHttpClient.client,
) : Closeable {
    private val appContext = context.applicationContext
    private val sourceStore = SkillSourceStore(appContext)
    private val provenanceStore = SkillRuntime.createProvenanceStore(appContext)
    private val installer = SkillRuntime.createPackageInstaller(appContext)
    private val githubSource = PublicGitHubSkillSource(
        cacheRoot = appContext.cacheDir,
        baseClient = baseClient,
    )
    private val httpClient = baseClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val httpAdapter = HttpsSkillCatalogAdapter(httpClient)

    fun sources(): List<SkillSourceDefinition> = sourceStore.load()

    fun saveSources(sources: List<SkillSourceDefinition>): List<SkillSourceDefinition> =
        sourceStore.save(sources)

    fun addSource(
        type: String,
        name: String,
        location: String,
        ref: String? = null,
        path: String? = null,
    ): List<SkillSourceDefinition> = sourceStore.add(type, name, location, ref, path)

    fun setSourceEnabled(sourceId: String, enabled: Boolean): List<SkillSourceDefinition> =
        sourceStore.setEnabled(sourceId, enabled)

    fun deleteSource(sourceId: String): List<SkillSourceDefinition> =
        sourceStore.delete(sourceId)

    fun resetBuiltinSources(): List<SkillSourceDefinition> = sourceStore.resetBuiltinSources()

    suspend fun search(query: String = ""): SkillMarketSnapshot = withContext(Dispatchers.IO) {
        val enabled = sourceStore.load().filter(SkillSourceDefinition::enabled)
        val statuses = mutableListOf<SkillSourceFetchStatus>()
        val issues = mutableListOf<SkillSourceIssue>()
        val gathered = mutableListOf<SkillMarketItem>()
        val outcomes = coroutineScope {
            enabled.map { source -> async { fetchSource(source) } }.awaitAll()
        }
        outcomes.forEach { outcome ->
            val source = outcome.source
            val failure = outcome.failure
            if (failure == null) {
                gathered += outcome.items
                statuses += SkillSourceFetchStatus(
                    sourceId = source.id,
                    sourceName = source.name,
                    succeeded = true,
                    itemCount = outcome.items.size,
                )
            } else {
                val code = (failure as? SkillCatalogException)?.code ?: "SOURCE_UNAVAILABLE"
                val message = boundedMessage(failure)
                statuses += SkillSourceFetchStatus(
                    sourceId = source.id,
                    sourceName = source.name,
                    succeeded = false,
                    itemCount = 0,
                    message = message,
                )
                issues += SkillSourceIssue(source.id, source.name, code, message)
            }
        }
        val filtered = filterMarketItems(gathered, query)
            .distinctBy(SkillMarketItem::key)
            .sortedWith(
                compareBy<SkillMarketItem> {
                    when (it.trust) {
                        SkillSourceTrust.OFFICIAL -> 0
                        SkillSourceTrust.CURATED -> 1
                        SkillSourceTrust.COMMUNITY -> 2
                        else -> 3
                    }
                }.thenBy { it.displayName.lowercase(Locale.ROOT) },
            )
        SkillMarketSnapshot(
            items = filtered,
            statuses = statuses,
            issues = issues,
            searchedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun fetchSource(source: SkillSourceDefinition): SourceFetchOutcome =
        try {
            val items = when (source.type) {
                SkillSourceType.GITHUB_REPOSITORY -> fetchGitHub(source)
                SkillSourceType.HTTP_CATALOG -> httpAdapter.fetch(source)
                else -> throw SkillCatalogException(
                    "UNSUPPORTED_SOURCE",
                    "不支持的 Skill 来源类型",
                )
            }
            SourceFetchOutcome(source, items, null)
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            SourceFetchOutcome(source, emptyList(), failure)
        }

    private data class SourceFetchOutcome(
        val source: SkillSourceDefinition,
        val items: List<SkillMarketItem>,
        val failure: Throwable?,
    )

    suspend fun loadDetails(item: SkillMarketItem): SkillMarketDetails = withContext(Dispatchers.IO) {
        if (item.sourceType != SkillSourceType.GITHUB_REPOSITORY || item.path.isNullOrBlank()) {
            return@withContext SkillMarketDetails(
                item = item,
                usage = item.usage,
                dependencies = item.dependencies,
                permissions = item.permissions,
                releaseNotes = item.releaseNotes,
            )
        }
        val repository = item.repository?.let(GitHubSkillRepositoryParser::parse)
        val ref = item.ref?.takeIf { COMMIT_SHA.matches(it) }
        if (repository == null || ref == null) {
            return@withContext SkillMarketDetails(
                item = item,
                usage = item.usage,
                dependencies = item.dependencies,
                permissions = item.permissions,
                releaseNotes = item.releaseNotes,
                detailWarning = "来源缺少固定 commit，详情无法安全加载",
            )
        }
        val raw = runCatching {
            fetchRawSkillMarkdown(repository, ref, item.path)
        }.getOrElse {
            return@withContext SkillMarketDetails(
                item = item,
                usage = item.usage,
                dependencies = item.dependencies,
                permissions = item.permissions,
                releaseNotes = item.releaseNotes,
                detailWarning = "暂时无法读取 SKILL.md，安装时仍会执行完整包检查",
            )
        }
        val parsed = parseSkillMarkdown(raw)
        val name = parsed.frontmatter["name"]?.trim()?.take(120).orEmpty()
        val description = parsed.frontmatter["description"]?.trim()?.take(2_048).orEmpty()
        SkillMarketDetails(
            item = item.copy(
                displayName = name.ifBlank { item.displayName },
                description = description.ifBlank { item.description },
            ),
            usage = item.usage ?: parsed.body.trim().take(8_000).ifBlank { null },
            dependencies = item.dependencies,
            permissions = item.permissions,
            releaseNotes = item.releaseNotes,
            readmeExcerpt = parsed.body.trim().take(8_000).ifBlank { null },
        )
    }

    /**
     * GitHub 安装固定到搜索结果中的 commit；HTTPS 目录只有校验和匹配才自动安装。
     * 无校验和目录必须经过 allowUnverified=true 的显式确认。
     */
    suspend fun install(
        item: SkillMarketItem,
        replaceSkillId: String? = null,
        allowUnverified: Boolean = false,
        isCancelled: () -> Boolean = { false },
    ): SkillMarketInstallOutcome = withContext(Dispatchers.IO) {
        if (!item.integrityVerified && !allowUnverified) {
            return@withContext SkillMarketInstallOutcome.ConfirmationRequired(
                reason = "该来源没有提供 SHA-256 校验和，安装前需要确认信任来源",
            )
        }
        if (replaceSkillId != null) {
            runCatching { SkillVersionService(appContext).backupCurrent(replaceSkillId) }
                .onFailure { failure ->
                    return@withContext SkillMarketInstallOutcome.Failure(
                        "更新前无法创建版本备份：" + boundedMessage(failure),
                    )
                }
        }
        try {
            when (item.sourceType) {
                SkillSourceType.GITHUB_REPOSITORY -> installGitHub(item, replaceSkillId, isCancelled)
                SkillSourceType.HTTP_CATALOG -> installHttp(item, replaceSkillId, isCancelled)
                else -> SkillMarketInstallOutcome.Failure("不支持的 Skill 来源类型")
            }
        } catch (failure: Throwable) {
            SkillMarketInstallOutcome.Failure(boundedMessage(failure))
        }
    }

    override fun close() {
        githubSource.close()
    }

    private fun fetchGitHub(source: SkillSourceDefinition): List<SkillMarketItem> {
        val repository = GitHubSkillRepositoryParser.resolve(
            repository = source.location,
            explicitRef = source.ref,
            explicitPath = source.path,
        )
        val inspection = githubSource.inspect(repository)
        return inspection.candidates.map { candidate ->
            SkillMarketItem(
                sourceId = source.id,
                sourceName = source.name,
                sourceType = source.type,
                trust = source.trust,
                skillId = SkillParser.sanitizeSkillId(candidate.name, null),
                displayName = prettySkillName(candidate.name),
                description = "来自 " + inspection.repository + " 的 Skill，安装前按需读取正文",
                publisher = inspection.repository.substringBefore('/'),
                repository = inspection.repository,
                ref = inspection.commitSha,
                path = candidate.path,
                tags = candidate.path
                    .split('/')
                    .dropLast(1)
                    .filter(String::isNotBlank)
                    .takeLast(4),
            )
        }
    }

    private fun installGitHub(
        item: SkillMarketItem,
        replaceSkillId: String?,
        isCancelled: () -> Boolean,
    ): SkillMarketInstallOutcome {
        val repository = item.repository?.let(GitHubSkillRepositoryParser::parse)
            ?: return SkillMarketInstallOutcome.Failure("GitHub 来源缺少仓库信息")
        val ref = item.ref?.takeIf(COMMIT_SHA::matches)
            ?: return SkillMarketInstallOutcome.Failure("GitHub 来源未固定 commit，请重新搜索")
        val path = item.path?.trim().orEmpty()
        if (path.isBlank()) return SkillMarketInstallOutcome.Failure("GitHub 来源缺少 Skill 路径")
        val pinned = repository.copy(ref = ref)
        githubSource.downloadArchive(pinned).use { archive ->
            val result = installer.installRepositoryZip(
                openStream = { archive.file.inputStream() },
                selectedPaths = listOf(path),
                replaceUserSkills = replaceSkillId != null,
                expectedReplacementIds = replaceSkillId?.let(::setOf).orEmpty(),
                isCancelled = isCancelled,
            )
            return applyInstallResult(result, item, archive.commitSha)
        }
    }

    private fun installHttp(
        item: SkillMarketItem,
        replaceSkillId: String?,
        isCancelled: () -> Boolean,
    ): SkillMarketInstallOutcome {
        val download = downloadHttpArchive(item, isCancelled)
        return try {
            val result = installer.installLocalZip(
                openStream = { download.file.inputStream() },
                replaceUserSkill = replaceSkillId != null,
                expectedReplacementId = replaceSkillId,
                expectedArchiveSha256 = replaceSkillId?.let { download.sha256 },
                isCancelled = isCancelled,
            )
            applyInstallResult(result, item, null, download.sha256)
        } finally {
            download.file.delete()
        }
    }

    private fun applyInstallResult(
        result: SkillInstallResult,
        item: SkillMarketItem,
        commitSha: String?,
        archiveSha256: String? = item.archiveSha256,
    ): SkillMarketInstallOutcome = when (result) {
        is SkillInstallResult.Success -> {
            val installedAt = System.currentTimeMillis()
            provenanceStore.recordAll(
                result.installed.map { installed ->
                    SkillProvenance(
                        skillId = installed.id,
                        sourceId = item.sourceId,
                        sourceName = item.sourceName,
                        sourceType = item.sourceType,
                        repository = item.repository,
                        ref = commitSha ?: item.ref,
                        path = item.path,
                        archiveSha256 = archiveSha256,
                        version = item.version,
                        installedAt = installedAt,
                    )
                },
            )
            val indexService = SkillRuntime.createIndexService(appContext)
            val refreshed = indexService.listSkillsForManagement(forceRefresh = true)
            val environment = runCatching { SkillReadinessInspector.capture(appContext) }.getOrNull()
            val paused = mutableSetOf<String>()
            val warnings = mutableListOf<String>()
            if (environment != null) {
                result.installed.forEach { installed ->
                    val entry = refreshed.firstOrNull { it.id == installed.id && it.installed } ?: return@forEach
                    val missing = SkillReadinessInspector.inspect(entry.dependencies, environment)
                        .filter { it.status == SkillPrerequisiteStatus.BLOCKED }
                    if (missing.isNotEmpty() && entry.enabled) {
                        runCatching { indexService.setSkillEnabled(entry.id, false) }
                            .onSuccess {
                                paused += entry.id
                                warnings += "${entry.name} 缺少：${missing.joinToString { it.requirement }}"
                            }
                    }
                }
            } else {
                warnings += "依赖状态暂时无法读取，Skill 已安装但建议在 Skill 管理中复核"
            }
            SkillMarketInstallOutcome.Success(
                skills = result.installed,
                setupRequiredSkillIds = paused,
                prerequisiteWarnings = warnings.take(8),
            )
        }
        is SkillInstallResult.Conflict -> SkillMarketInstallOutcome.Conflict(result.conflicts)
        is SkillInstallResult.Failure -> SkillMarketInstallOutcome.Failure(
            result.error.message,
            result.recoveryRequired,
        )
    }

    private fun downloadHttpArchive(
        item: SkillMarketItem,
        isCancelled: () -> Boolean,
    ): DownloadedArchive {
        val urlValue = item.archiveUrl?.trim().orEmpty()
        val url = validateArchiveUrl(urlValue)
            ?: throw SkillCatalogException("INVALID_ARCHIVE_URL", "Skill 归档地址无效")
        val directory = File(appContext.cacheDir, "capability-center/downloads")
        ensureSafeDirectory(directory)
        val file = File.createTempFile("eta-skill-market-", ".zip", directory)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            val request = Request.Builder()
                .url(url)
                .header("Accept", "application/zip, application/octet-stream")
                .header("User-Agent", "Nexus-Capability-Center/1")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isRedirect) {
                    throw SkillCatalogException("ARCHIVE_REDIRECT", "归档下载发生重定向，已拒绝")
                }
                if (!response.isSuccessful) {
                    throw SkillCatalogException(
                        "ARCHIVE_HTTP_ERROR",
                        "归档下载失败（HTTP " + response.code + "）",
                    )
                }
                if (response.body.contentLength() > MAX_ARCHIVE_BYTES) {
                    throw SkillCatalogException("ARCHIVE_TOO_LARGE", "Skill 归档超过 32 MiB 上限")
                }
                var total = 0L
                response.body.byteStream().use { input ->
                    FileOutputStream(file).buffered().use { output ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            if (isCancelled()) {
                                throw SkillCatalogException("INSTALL_CANCELLED", "安装已取消")
                            }
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            if (total > MAX_ARCHIVE_BYTES) {
                                throw SkillCatalogException(
                                    "ARCHIVE_TOO_LARGE",
                                    "Skill 归档超过 32 MiB 上限",
                                )
                            }
                            digest.update(buffer, 0, count)
                            output.write(buffer, 0, count)
                        }
                    }
                }
            }
            if (file.length() == 0L) {
                throw SkillCatalogException("EMPTY_ARCHIVE", "Skill 归档为空")
            }
            val actual = digest.digest().joinToString("") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }
            item.archiveSha256?.let { expected ->
                if (actual != expected) {
                    throw SkillCatalogException(
                        "CHECKSUM_MISMATCH",
                        "Skill 归档校验失败，文件可能已损坏或被替换",
                    )
                }
            }
            return DownloadedArchive(file, actual)
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    private fun fetchRawSkillMarkdown(
        repository: GitHubSkillRepository,
        ref: String,
        path: String,
    ): String {
        val normalizedPath = GitHubSkillRepositoryParser.normalizeRelativePath(path)
        val url = HttpUrlBuilder()
            .scheme("https")
            .host("raw.githubusercontent.com")
            .addPathSegment(repository.owner)
            .addPathSegment(repository.repository)
            .addPathSegment(ref)
            .apply {
                normalizedPath.split('/').filter(String::isNotBlank).forEach(::addPathSegment)
            }
            .addPathSegment("SKILL.md")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Nexus-Capability-Center/1")
            .build()
        return httpClient.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                throw SkillCatalogException("DETAIL_REDIRECT", "详情读取发生重定向，已拒绝")
            }
            if (!response.isSuccessful) {
                throw SkillCatalogException("DETAIL_HTTP_ERROR", "详情读取失败")
            }
            readBoundedUtf8(response.body.byteStream(), MAX_DETAIL_BYTES)
        }
    }

    private fun validateArchiveUrl(raw: String): String? {
        if (raw.isBlank() || raw.length > MAX_URL_LENGTH) return null
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) return null
        if (uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in setOf(-1, 443)) return null
        return uri.toASCIIString()
    }

    private fun ensureSafeDirectory(directory: File) {
        val path = directory.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                throw SkillCatalogException("CACHE_UNSAFE", "Skill 下载缓存目录不安全")
            }
            return
        }
        if (!directory.mkdirs() || Files.isSymbolicLink(path)) {
            throw SkillCatalogException("CACHE_UNAVAILABLE", "无法创建 Skill 下载缓存")
        }
    }

    private fun readBoundedUtf8(input: java.io.InputStream, limit: Int): String {
        val output = java.io.ByteArrayOutputStream()
        input.use {
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                total += count
                if (total > limit) {
                    throw SkillCatalogException("DETAIL_TOO_LARGE", "远端 Skill 详情超过安全上限")
                }
                output.write(buffer, 0, count)
            }
        }
        return output.toByteArray().toString(Charsets.UTF_8)
    }

    private fun filterMarketItems(
        items: List<SkillMarketItem>,
        rawQuery: String,
    ): List<SkillMarketItem> {
        val tokens = rawQuery.trim().lowercase(Locale.ROOT)
            .split(' ')
            .filter(String::isNotBlank)
        if (tokens.isEmpty()) return items
        return items.filter { item ->
            val haystack = buildString {
                append(item.displayName)
                append(' ')
                append(item.skillId)
                append(' ')
                append(item.description)
                append(' ')
                append(item.publisher.orEmpty())
                append(' ')
                append(item.sourceName)
                append(' ')
                append(item.tags.joinToString(" "))
                append(' ')
                append(item.usage.orEmpty())
            }.lowercase(Locale.ROOT)
            tokens.all(haystack::contains)
        }
    }

    private fun boundedMessage(failure: Throwable): String {
        val raw = failure.message?.trim().orEmpty()
        return raw.ifBlank { "来源暂时不可用" }.lineSequence().first().take(300)
    }

    private fun prettySkillName(value: String): String = value
        .replace('-', ' ')
        .replace('_', ' ')
        .split(' ')
        .filter(String::isNotBlank)
        .joinToString(" ") { token ->
            token.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase(Locale.ROOT) else char.toString()
            }
        }
        .ifBlank { value }
        .take(120)

    private fun parseSkillMarkdown(raw: String): ParsedMarkdown {
        val normalized = raw.replace("\r\n", "\n")
        if (!normalized.startsWith("---\n")) {
            return ParsedMarkdown(emptyMap(), normalized.trim())
        }
        val end = normalized.indexOf("\n---", startIndex = 4)
        if (end < 0) return ParsedMarkdown(emptyMap(), normalized.trim())
        val frontmatter = normalized.substring(4, end).trim()
        val body = normalized.substring(end + 4).trim()
        return ParsedMarkdown(SkillParser.parseSimpleFrontmatter(frontmatter), body)
    }

    private data class ParsedMarkdown(
        val frontmatter: Map<String, String>,
        val body: String,
    )

    private data class DownloadedArchive(
        val file: File,
        val sha256: String,
    )

    private companion object {
        val COMMIT_SHA = Regex("[a-f0-9]{40}")
        const val MAX_ARCHIVE_BYTES = 32L * 1024L * 1024L
        const val MAX_DETAIL_BYTES = 256 * 1024
        const val MAX_URL_LENGTH = 2_048
    }
}

private class HttpsSkillCatalogAdapter(
    private val client: OkHttpClient,
) {
    fun fetch(source: SkillSourceDefinition): List<SkillMarketItem> {
        val request = Request.Builder()
            .url(source.location)
            .header("Accept", "application/json")
            .header("User-Agent", "Nexus-Capability-Center/1")
            .build()
        val bytes = client.newCall(request).execute().use { response ->
            if (response.isRedirect) {
                throw SkillCatalogException("CATALOG_REDIRECT", "目录发生重定向，已拒绝")
            }
            if (!response.isSuccessful) {
                throw SkillCatalogException(
                    "CATALOG_HTTP_ERROR",
                    "目录读取失败（HTTP " + response.code + "）",
                )
            }
            if (response.body.contentLength() > MAX_CATALOG_BYTES) {
                throw SkillCatalogException("CATALOG_TOO_LARGE", "目录超过 1 MiB 上限")
            }
            response.body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_CATALOG_BYTES) {
                        throw SkillCatalogException("CATALOG_TOO_LARGE", "目录超过 1 MiB 上限")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        val root = runCatching { JSONObject(bytes.toString(Charsets.UTF_8)) }
            .getOrElse { throw SkillCatalogException("INVALID_CATALOG", "目录不是有效 JSON") }
        return SkillCatalogParser.parse(root, source)
    }

    private companion object {
        const val MAX_CATALOG_BYTES = 1024 * 1024
    }
}

/** 独立解析器便于在不访问网络的情况下验证来源清单格式。 */
internal object SkillCatalogParser {
    fun parse(root: JSONObject, source: SkillSourceDefinition): List<SkillMarketItem> {
        if (root.optString("schema") != SCHEMA) {
            throw SkillCatalogException(
                "INVALID_CATALOG_SCHEMA",
                "目录必须声明 schema=" + SCHEMA,
            )
        }
        val values = root.optJSONArray("skills") ?: JSONArray()
        if (values.length() > MAX_ITEMS) {
            throw SkillCatalogException("CATALOG_ITEM_LIMIT", "目录最多包含 500 个 Skill")
        }
        val result = buildList {
            for (index in 0 until values.length()) {
                val value = values.optJSONObject(index)
                    ?: throw SkillCatalogException("INVALID_CATALOG_ITEM", "目录条目格式无效")
                add(parseItem(value, source, index))
            }
        }
        if (result.map(SkillMarketItem::skillId).distinct().size != result.size) {
            throw SkillCatalogException("DUPLICATE_SKILL_ID", "目录包含重复 Skill id")
        }
        return result
    }

    private fun parseItem(
        value: JSONObject,
        source: SkillSourceDefinition,
        index: Int,
    ): SkillMarketItem {
        val skillId = value.optString("id").trim()
        if (!SKILL_ID.matches(skillId)) {
            throw SkillCatalogException(
                "INVALID_SKILL_ID",
                "第 " + (index + 1) + " 个 Skill id 无效",
            )
        }
        val name = visible(value.optString("name"), 1, 120)
            ?: throw SkillCatalogException("INVALID_SKILL_NAME", "Skill 名称无效：" + skillId)
        val description = visible(value.optString("description"), 0, 2_048).orEmpty()
        val archiveUrl = value.optString("archiveUrl").trim()
        if (archiveUrl.isBlank()) {
            throw SkillCatalogException(
                "MISSING_ARCHIVE_URL",
                "Skill 缺少 archiveUrl：" + skillId,
            )
        }
        val checksum = value.optString("archiveSha256").trim()
            .takeIf(String::isNotBlank)
            ?.takeIf(SHA256::matches)
            ?: if (value.has("archiveSha256") && !value.isNull("archiveSha256")) {
                throw SkillCatalogException(
                    "INVALID_CHECKSUM",
                    "Skill 校验和格式无效：" + skillId,
                )
            } else {
                null
            }
        return SkillMarketItem(
            sourceId = source.id,
            sourceName = source.name,
            sourceType = source.type,
            trust = source.trust,
            skillId = skillId,
            displayName = name,
            description = description,
            version = visible(value.optString("version"), 0, 64),
            publisher = visible(value.optString("publisher"), 0, 120),
            repository = visible(value.optString("repository"), 0, 160),
            ref = visible(value.optString("ref"), 0, 200),
            path = visible(value.optString("path"), 0, 1_000),
            archiveUrl = archiveUrl,
            archiveSha256 = checksum,
            tags = stringList(value.optJSONArray("tags"), 12, 40),
            usage = visible(value.optString("usage"), 0, 8_000),
            dependencies = stringList(value.optJSONArray("dependencies"), 32, 160),
            permissions = stringList(value.optJSONArray("permissions"), 32, 160),
            releaseNotes = visible(value.optString("releaseNotes"), 0, 2_000),
        )
    }

    private fun visible(raw: String, min: Int, max: Int): String? {
        val value = raw.trim()
        if (value.length !in min..max) return null
        if (value.any { it.isISOControl() && it != '\n' && it != '\t' }) return null
        return value
    }

    private fun stringList(values: JSONArray?, maxItems: Int, maxLength: Int): List<String> {
        if (values == null) return emptyList()
        if (values.length() > maxItems) {
            throw SkillCatalogException("INVALID_CATALOG_ITEM", "目录列表字段超过数量上限")
        }
        return buildList {
            for (index in 0 until values.length()) {
                val item = values.optString(index).trim()
                if (item.isBlank() || item.length > maxLength || item.any(Char::isISOControl)) {
                    throw SkillCatalogException("INVALID_CATALOG_ITEM", "目录列表字段格式无效")
                }
                add(item)
            }
        }.distinct()
    }

    private const val SCHEMA = "eta-skill-catalog/v1"
    private const val MAX_ITEMS = 500
    private val SKILL_ID = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val SHA256 = Regex("[a-f0-9]{64}")
}

private class HttpUrlBuilder {
    private val builder = okhttp3.HttpUrl.Builder()

    fun scheme(value: String) = apply { builder.scheme(value) }
    fun host(value: String) = apply { builder.host(value) }
    fun addPathSegment(value: String) = apply { builder.addPathSegment(value) }
    fun build(): okhttp3.HttpUrl = builder.build()
}
