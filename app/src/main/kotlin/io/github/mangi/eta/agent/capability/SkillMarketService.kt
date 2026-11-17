package io.github.mangi.eta.agent.capability

import android.content.Context
import io.github.mangi.eta.agent.model.AgentHttpClient
import io.github.mangi.eta.agent.skill.GitHubSkillRepository
import io.github.mangi.eta.agent.skill.GitHubSkillRepositoryParser
import io.github.mangi.eta.agent.skill.PublicGitHubSkillSource
import io.github.mangi.eta.agent.skill.GitLabSkillRepositoryParser
import io.github.mangi.eta.agent.skill.PublicGitLabSkillSource
import io.github.mangi.eta.agent.skill.GiteeSkillRepositoryParser
import io.github.mangi.eta.agent.skill.PublicGiteeSkillSource
import io.github.mangi.eta.agent.skill.SkillInstallResult
import io.github.mangi.eta.agent.skill.SkillInstallError
import io.github.mangi.eta.agent.skill.SkillInstallErrorCode
import io.github.mangi.eta.agent.skill.SkillArchiveCandidate
import io.github.mangi.eta.agent.skill.SkillArchiveInspectionResult
import io.github.mangi.eta.agent.skill.SkillParser
import io.github.mangi.eta.agent.skill.SkillProvenance
import io.github.mangi.eta.agent.skill.SkillPrerequisiteStatus
import io.github.mangi.eta.agent.skill.SkillReadinessInspector
import io.github.mangi.eta.agent.skill.SkillRuntime
import io.github.mangi.eta.agent.skill.SkillVersionService
import io.github.mangi.eta.agent.skill.SkillDependencyAdmission
import io.github.mangi.eta.agent.skill.SkillDependencyCatalogEntry
import io.github.mangi.eta.agent.skill.SkillDependencyExpansion
import io.github.mangi.eta.agent.skill.SkillDependencyInstalledState
import io.github.mangi.eta.agent.skill.SkillDependencyParser
import io.github.mangi.eta.agent.skill.SkillDependencyResolver
import io.github.mangi.eta.agent.skill.SkillDependencySpec
import io.github.mangi.eta.agent.skill.SkillDependencyTransactionJournal
import io.github.mangi.eta.agent.skill.SkillRegistryRecoverySnapshot
import io.github.mangi.eta.agent.skill.completeRecoveredSkillOperations
import io.github.mangi.eta.agent.skill.recoverPendingSkillOperations
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
    private val dependencyInstallObserver: (String?) -> Unit = {},
) : Closeable {
    private val appContext = context.applicationContext
    private val sourceStore = SkillSourceStore(appContext)
    private val provenanceStore = SkillRuntime.createProvenanceStore(appContext)
    private val installer = SkillRuntime.createPackageInstaller(appContext)
    private val githubSource = PublicGitHubSkillSource(
        cacheRoot = appContext.cacheDir,
        baseClient = baseClient,
    )
    private val gitlabSource = PublicGitLabSkillSource(appContext.cacheDir, baseClient)
    private val giteeSource = PublicGiteeSkillSource(appContext.cacheDir, baseClient)
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
                SkillSourceType.GITLAB_REPOSITORY -> fetchGitLab(source)
                SkillSourceType.GITEE_REPOSITORY -> fetchGitee(source)
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
        resolveDependencies: Boolean = true,
    ): SkillMarketInstallOutcome = withContext(Dispatchers.IO) {
        installBySource(item, replaceSkillId, allowUnverified, isCancelled, resolveDependencies)
    }

    private suspend fun installBySource(
        item: SkillMarketItem,
        replaceSkillId: String?,
        allowUnverified: Boolean,
        isCancelled: () -> Boolean,
        resolveDependencies: Boolean,
        parentTransaction: SkillDependencyTransactionJournal? = null,
        dependencySkillId: String? = null,
    ): SkillMarketInstallOutcome {
        if (dependencySkillId != null) dependencyInstallObserver(dependencySkillId)
        if (!item.integrityVerified && !allowUnverified) {
            return SkillMarketInstallOutcome.ConfirmationRequired(
                reason = "该来源没有提供 SHA-256 校验和，安装前需要确认信任来源",
            )
        }
        if (replaceSkillId != null) {
            runCatching { SkillVersionService(appContext).backupCurrent(replaceSkillId) }
                .onFailure { failure ->
                    return SkillMarketInstallOutcome.Failure(
                        "更新前无法创建版本备份：" + boundedMessage(failure),
                    )
                }
        }
        return try {
            when (item.sourceType) {
        SkillSourceType.GITHUB_REPOSITORY -> installGitHub(
            item, replaceSkillId, isCancelled, resolveDependencies, parentTransaction, dependencySkillId,
        )
        SkillSourceType.GITLAB_REPOSITORY -> installGitLab(
            item, replaceSkillId, isCancelled, resolveDependencies, parentTransaction, dependencySkillId,
        )
        SkillSourceType.GITEE_REPOSITORY -> installGitee(
            item, replaceSkillId, isCancelled, resolveDependencies, parentTransaction, dependencySkillId,
        )
        SkillSourceType.HTTP_CATALOG -> installHttp(
            item, replaceSkillId, isCancelled, resolveDependencies, parentTransaction, dependencySkillId,
        )
                else -> SkillMarketInstallOutcome.Failure("不支持的 Skill 来源类型")
            }
        } catch (failure: Throwable) {
            SkillMarketInstallOutcome.Failure(boundedMessage(failure))
        }
    }

    /** Local ZIP dependencies are admitted from the configured market before the ZIP itself is installed. */
    suspend fun installLocalZip(
        openStream: () -> java.io.InputStream,
        replaceUserSkill: Boolean,
        expectedReplacementId: String?,
        expectedArchiveSha256: String?,
        dependencyOwnerSnapshot: List<io.github.mangi.eta.agent.skill.SkillDependencyRecoverySnapshot> = emptyList(),
        isCancelled: () -> Boolean = { false },
    ): SkillInstallResult = withContext(Dispatchers.IO) {
        val staged = try {
            materializeLocalArchive(openStream, isCancelled)
        } catch (failure: Throwable) {
            return@withContext SkillInstallResult.Failure(
                SkillInstallError(
                    code = if (failure is SkillCatalogException && failure.code == "INSTALL_CANCELLED") {
                        SkillInstallErrorCode.CANCELLED
                    } else if (failure is SkillCatalogException && failure.code == "ARCHIVE_TOO_LARGE") {
                        SkillInstallErrorCode.ARCHIVE_TOO_LARGE
                    } else {
                        SkillInstallErrorCode.IO_ERROR
                    },
                    message = boundedMessage(failure),
                ),
            )
        }
        val stableRoot = File(appContext.filesDir, ".eta-skill-root-staging").apply { mkdirs() }
        val stableArchive = File(stableRoot, "root-${java.util.UUID.randomUUID()}.zip")
        try {
            // Dependency admission may recover/clean cache staging; keep the root archive in the
            // private installer area until the parent transaction has settled.
            staged.file.copyTo(stableArchive, overwrite = false)
            val rootArchiveBytes = stableArchive.readBytes()
            val inspection = installer.inspectLocalZip({ rootArchiveBytes.inputStream() }, isCancelled)
            if (inspection is SkillArchiveInspectionResult.Failure) {
                return@withContext SkillInstallResult.Failure(inspection.error)
            }
            inspection as SkillArchiveInspectionResult.Success
            val candidate = inspection.candidates.single()
            val archiveSha256 = staged.sha256
            if (inspection.archiveSha256 != archiveSha256) {
                return@withContext SkillInstallResult.Failure(
                    SkillInstallError(SkillInstallErrorCode.CHECKSUM_MISMATCH, "本地 Skill ZIP 摘要不一致"),
                )
            }

            if (replaceUserSkill) {
                val expectedId = expectedReplacementId?.trim().orEmpty()
                val expectedDigest = expectedArchiveSha256?.trim().orEmpty()
                if (expectedId.isBlank() || candidate.id != expectedId || !LOCAL_SHA256.matches(expectedDigest)) {
                    return@withContext SkillInstallResult.Failure(
                        SkillInstallError(
                            SkillInstallErrorCode.INVALID_SELECTION,
                            "替换确认与本地 Skill ZIP 不匹配",
                        ),
                    )
                }
                if (archiveSha256 != expectedDigest) {
                    return@withContext SkillInstallResult.Failure(
                        SkillInstallError(
                            SkillInstallErrorCode.CHECKSUM_MISMATCH,
                            "本地 Skill ZIP 内容与已确认归档不一致",
                        ),
                    )
                }
            } else {
                if (expectedReplacementId != null) {
                    return@withContext SkillInstallResult.Failure(
                        SkillInstallError(
                            SkillInstallErrorCode.INVALID_SELECTION,
                            "仅替换用户 Skill 时可指定替换身份",
                        ),
                    )
                }
                if (expectedArchiveSha256 != null) {
                    if (!LOCAL_SHA256.matches(expectedArchiveSha256)) {
                        return@withContext SkillInstallResult.Failure(
                            SkillInstallError(
                                SkillInstallErrorCode.INVALID_SELECTION,
                                "Skill 归档摘要必须是小写 SHA-256",
                            ),
                        )
                    }
                    if (archiveSha256 != expectedArchiveSha256) {
                        return@withContext SkillInstallResult.Failure(
                            SkillInstallError(
                                SkillInstallErrorCode.CHECKSUM_MISMATCH,
                                "本地 Skill ZIP SHA-256 与确认摘要不一致",
                            ),
                        )
                    }
                }
            }

            installer.inspectLocalZipConflict(candidate, archiveSha256, replaceUserSkill)?.let {
                return@withContext it
            }
            val preparation = prepareDependencies(
                rootId = candidate.id,
                declarations = candidate.skillDependencies,
                isCancelled = isCancelled,
                failOnSetupRequired = true,
            )
            preparation.failure?.let { message ->
                return@withContext SkillInstallResult.Failure(
                    SkillInstallError(
                        code = preparation.errorCode ?: SkillInstallErrorCode.DEPENDENCY_SETUP_REQUIRED,
                        message = message,
                    ),
                )
            }
            val transaction = preparation.transaction
                ?: DependencyTransactionHandle(null, candidate.id, emptyList())
            if (isCancelled()) {
                transaction.abandon()
                return@withContext SkillInstallResult.Failure(
                    SkillInstallError(SkillInstallErrorCode.CANCELLED, "Skill 安装已取消"),
                )
            }
            if (replaceUserSkill && !expectedReplacementId.isNullOrBlank()) {
                runCatching { SkillVersionService(appContext).backupCurrent(expectedReplacementId) }
                    .onFailure { failure ->
                        transaction.abandon()
                        return@withContext SkillInstallResult.Failure(
                            SkillInstallError(
                                SkillInstallErrorCode.IO_ERROR,
                                "更新前无法创建版本备份：" + boundedMessage(failure),
                            ),
                        )
                    }
            }
            val result = transaction.transaction?.let { journal ->
                installer.installLocalZipWithDependencyTransaction(
                openStream = { rootArchiveBytes.inputStream() },
                replaceUserSkill = replaceUserSkill,
                expectedReplacementId = expectedReplacementId,
                expectedArchiveSha256 = archiveSha256,
                dependencyOwners = mapOf(candidate.id to preparation.rootEdges),
                dependencyTransaction = journal,
                isCancelled = isCancelled,
                )
            } ?: installer.installLocalZip(
                openStream = { rootArchiveBytes.inputStream() },
                replaceUserSkill = replaceUserSkill,
                expectedReplacementId = expectedReplacementId,
                expectedArchiveSha256 = archiveSha256,
                isCancelled = isCancelled,
            )
            transaction.finish(result)
            result
        } finally {
            stableArchive.delete()
            staged.file.delete()
        }
    }

    override fun close() {
        githubSource.close()
        gitlabSource.close()
        giteeSource.close()
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

    private suspend fun installGitHub(
        item: SkillMarketItem,
        replaceSkillId: String?,
        isCancelled: () -> Boolean,
        resolveDependencies: Boolean,
        parentTransaction: SkillDependencyTransactionJournal? = null,
        dependencySkillId: String? = null,
    ): SkillMarketInstallOutcome {
        val repository = item.repository?.let(GitHubSkillRepositoryParser::parse)
            ?: return SkillMarketInstallOutcome.Failure("GitHub 来源缺少仓库信息")
        val ref = item.ref?.takeIf(COMMIT_SHA::matches)
            ?: return SkillMarketInstallOutcome.Failure("GitHub 来源未固定 commit，请重新搜索")
        val path = item.path?.trim().orEmpty()
        if (path.isBlank()) return SkillMarketInstallOutcome.Failure("GitHub 来源缺少 Skill 路径")
        val pinned = repository.copy(ref = ref)
        githubSource.downloadArchive(pinned).use { archive ->
            val candidate = inspectRepositoryCandidate(archive.file, path, isCancelled)
            val preparation = if (resolveDependencies) {
                prepareDependencies(candidate?.id.orEmpty(), candidate?.skillDependencies.orEmpty(), isCancelled)
            } else {
                DependencyPreparation(transaction = parentTransaction?.let {
                    DependencyTransactionHandle(it, candidate?.id.orEmpty(), emptyList())
                })
            }
            preparation.failure?.let { return SkillMarketInstallOutcome.Failure(it) }
            val transaction = preparation.transaction
                ?: DependencyTransactionHandle(null, candidate?.id.orEmpty(), emptyList())
            val result = transaction.transaction?.let { journal ->
                installer.installRepositoryZipWithDependencyTransaction(
                    openStream = { archive.file.inputStream() },
                    selectedPaths = listOf(path),
                    replaceUserSkills = replaceSkillId != null,
                    expectedReplacementIds = replaceSkillId?.let(::setOf).orEmpty(),
                    expectedArchiveSha256 = item.archiveSha256,
                    dependencyOwners = mapOf(candidate?.id.orEmpty() to preparation.rootEdges),
                    dependencyTransaction = journal,
                    dependencySkillId = dependencySkillId,
                    isCancelled = isCancelled,
                )
            } ?: installer.installRepositoryZip(
                openStream = { archive.file.inputStream() },
                selectedPaths = listOf(path),
                replaceUserSkills = replaceSkillId != null,
                expectedReplacementIds = replaceSkillId?.let(::setOf).orEmpty(),
                isCancelled = isCancelled,
                expectedArchiveSha256 = item.archiveSha256,
            )
            val outcome = applyInstallResult(
                result = result,
                item = item,
                commitSha = archive.commitSha,
                dependencyEdges = preparation.edges,
                extraSetupWarnings = preparation.warnings,
                dependencyTransaction = transaction.transaction,
            )
            if (dependencySkillId == null) transaction.finish(result, outcome)
            return outcome
        }
    }

    private fun fetchGitLab(source: SkillSourceDefinition): List<SkillMarketItem> {
        val repository = GitLabSkillRepositoryParser.resolve(source.location, source.ref, source.path)
        val inspection = gitlabSource.inspect(repository)
        return inspection.candidates.map { candidate ->
            SkillMarketItem(
                sourceId = source.id, sourceName = source.name, sourceType = source.type, trust = source.trust,
                skillId = SkillParser.sanitizeSkillId(candidate.name, null), displayName = prettySkillName(candidate.name),
                description = "来自 GitLab ${inspection.repository} 的 Skill，安装前按需读取正文",
                publisher = inspection.repository.substringBefore('/'), repository = inspection.repository,
                ref = inspection.commitSha, path = candidate.path,
                tags = candidate.path.split('/').dropLast(1).filter(String::isNotBlank).takeLast(4),
            )
        }
    }

    private suspend fun installGitLab(
        item: SkillMarketItem,
        replaceSkillId: String?,
        isCancelled: () -> Boolean,
        resolveDependencies: Boolean,
        parentTransaction: SkillDependencyTransactionJournal? = null,
        dependencySkillId: String? = null,
    ): SkillMarketInstallOutcome {
        val repository = item.repository?.let(GitLabSkillRepositoryParser::parse)
            ?: return SkillMarketInstallOutcome.Failure("GitLab 来源缺少仓库信息")
        val ref = item.ref?.takeIf(COMMIT_SHA::matches)
            ?: return SkillMarketInstallOutcome.Failure("GitLab 来源未固定 commit，请重新搜索")
        val path = item.path?.trim().orEmpty()
        if (path.isBlank()) return SkillMarketInstallOutcome.Failure("GitLab 来源缺少 Skill 路径")
        gitlabSource.downloadArchive(repository.copy(ref = ref)).use { archive ->
            val candidate = inspectRepositoryCandidate(archive.file, path, isCancelled)
            val preparation = if (resolveDependencies) {
                prepareDependencies(candidate?.id.orEmpty(), candidate?.skillDependencies.orEmpty(), isCancelled)
            } else {
                DependencyPreparation(transaction = parentTransaction?.let {
                    DependencyTransactionHandle(it, candidate?.id.orEmpty(), emptyList())
                })
            }
            preparation.failure?.let { return SkillMarketInstallOutcome.Failure(it) }
            val transaction = preparation.transaction
                ?: DependencyTransactionHandle(null, candidate?.id.orEmpty(), emptyList())
            val result = transaction.transaction?.let { journal ->
                installer.installRepositoryZipWithDependencyTransaction(
                    openStream = { archive.file.inputStream() }, selectedPaths = listOf(path),
                    replaceUserSkills = replaceSkillId != null,
                    expectedReplacementIds = replaceSkillId?.let(::setOf).orEmpty(),
                    expectedArchiveSha256 = item.archiveSha256,
                    dependencyOwners = mapOf(candidate?.id.orEmpty() to preparation.rootEdges),
                    dependencyTransaction = journal,
                    dependencySkillId = dependencySkillId,
                    isCancelled = isCancelled,
                )
            } ?: installer.installRepositoryZip(
                openStream = { archive.file.inputStream() }, selectedPaths = listOf(path),
                replaceUserSkills = replaceSkillId != null, expectedReplacementIds = replaceSkillId?.let(::setOf).orEmpty(),
                isCancelled = isCancelled,
                expectedArchiveSha256 = item.archiveSha256,
            )
            val outcome = applyInstallResult(
                result = result,
                item = item,
                commitSha = archive.commitSha,
                dependencyEdges = preparation.edges,
                extraSetupWarnings = preparation.warnings,
                dependencyTransaction = transaction.transaction,
            )
            if (dependencySkillId == null) transaction.finish(result, outcome)
            return outcome
        }
    }

    private fun fetchGitee(source: SkillSourceDefinition): List<SkillMarketItem> {
        val repository = GiteeSkillRepositoryParser.resolve(source.location, source.ref, source.path)
        val inspection = giteeSource.inspect(repository)
        return inspection.candidates.map { candidate ->
            SkillMarketItem(sourceId = source.id, sourceName = source.name, sourceType = source.type, trust = source.trust,
                skillId = SkillParser.sanitizeSkillId(candidate.name, null), displayName = prettySkillName(candidate.name),
                description = "来自 Gitee ${inspection.repository} 的 Skill，安装前按需读取正文",
                publisher = inspection.repository.substringBefore('/'), repository = inspection.repository,
                ref = inspection.commitSha, path = candidate.path,
                tags = candidate.path.split('/').dropLast(1).filter(String::isNotBlank).takeLast(4))
        }
    }

    private suspend fun installGitee(
        item: SkillMarketItem,
        replaceSkillId: String?,
        isCancelled: () -> Boolean,
        resolveDependencies: Boolean,
        parentTransaction: SkillDependencyTransactionJournal? = null,
        dependencySkillId: String? = null,
    ): SkillMarketInstallOutcome {
        val repository = item.repository?.let(GiteeSkillRepositoryParser::parse) ?: return SkillMarketInstallOutcome.Failure("Gitee 来源缺少仓库信息")
        val ref = item.ref?.takeIf(COMMIT_SHA::matches) ?: return SkillMarketInstallOutcome.Failure("Gitee 来源未固定 commit，请重新搜索")
        val path = item.path?.trim().orEmpty(); if (path.isBlank()) return SkillMarketInstallOutcome.Failure("Gitee 来源缺少 Skill 路径")
        giteeSource.downloadArchive(repository.copy(ref = ref)).use { archive ->
            val candidate = inspectRepositoryCandidate(archive.file, path, isCancelled)
            val preparation = if (resolveDependencies) {
                prepareDependencies(candidate?.id.orEmpty(), candidate?.skillDependencies.orEmpty(), isCancelled)
            } else {
                DependencyPreparation(transaction = parentTransaction?.let {
                    DependencyTransactionHandle(it, candidate?.id.orEmpty(), emptyList())
                })
            }
            preparation.failure?.let { return SkillMarketInstallOutcome.Failure(it) }
            val transaction = preparation.transaction
                ?: DependencyTransactionHandle(null, candidate?.id.orEmpty(), emptyList())
            val result = transaction.transaction?.let { journal ->
                installer.installRepositoryZipWithDependencyTransaction(
                    { archive.file.inputStream() }, listOf(path), replaceSkillId != null,
                    replaceSkillId?.let(::setOf).orEmpty(), item.archiveSha256,
                    mapOf(candidate?.id.orEmpty() to preparation.rootEdges), journal,
                    dependencySkillId = dependencySkillId,
                    isCancelled = isCancelled,
                )
            } ?: installer.installRepositoryZip({ archive.file.inputStream() }, listOf(path), replaceSkillId != null,
                replaceSkillId?.let(::setOf).orEmpty(), isCancelled, item.archiveSha256)
            val outcome = applyInstallResult(
                result = result,
                item = item,
                commitSha = archive.commitSha,
                dependencyEdges = preparation.edges,
                extraSetupWarnings = preparation.warnings,
                dependencyTransaction = transaction.transaction,
            )
            if (dependencySkillId == null) transaction.finish(result, outcome)
            return outcome
        }
    }

    private suspend fun installHttp(
        item: SkillMarketItem,
        replaceSkillId: String?,
        isCancelled: () -> Boolean,
        resolveDependencies: Boolean,
        parentTransaction: SkillDependencyTransactionJournal? = null,
        dependencySkillId: String? = null,
    ): SkillMarketInstallOutcome {
        val download = downloadHttpArchive(item, isCancelled)
        return try {
            // Dependency admission may recover or clean temporary download paths. Keep this
            // archive stable for the actual install transaction.
            val archiveBytes = download.file.readBytes()
            val declarations = if (resolveDependencies) {
                SkillDependencyParser.parseList(item.dependencies)
            } else {
                emptyList()
            }
            val preparation = if (resolveDependencies) {
                prepareDependencies(item.skillId, declarations, isCancelled)
            } else {
                DependencyPreparation(transaction = parentTransaction?.let {
                    DependencyTransactionHandle(it, item.skillId, emptyList())
                })
            }
            preparation.failure?.let { return SkillMarketInstallOutcome.Failure(it) }
            val transaction = preparation.transaction
                ?: DependencyTransactionHandle(null, item.skillId, emptyList())
            val result = transaction.transaction?.let { journal ->
                installer.installLocalZipWithDependencyTransaction(
                    openStream = { archiveBytes.inputStream() },
                    replaceUserSkill = replaceSkillId != null,
                    expectedReplacementId = replaceSkillId,
                    expectedArchiveSha256 = item.archiveSha256 ?: replaceSkillId?.let { download.sha256 },
                    dependencyOwners = mapOf(item.skillId to preparation.rootEdges),
                    dependencyTransaction = journal,
                    dependencySkillId = dependencySkillId,
                    isCancelled = isCancelled,
                )
            } ?: installer.installLocalZip(
                openStream = { archiveBytes.inputStream() },
                replaceUserSkill = replaceSkillId != null,
                expectedReplacementId = replaceSkillId,
                expectedArchiveSha256 = item.archiveSha256 ?: replaceSkillId?.let { download.sha256 },
                isCancelled = isCancelled,
            )
            val outcome = applyInstallResult(
                result = result,
                item = item,
                commitSha = null,
                archiveSha256 = download.sha256,
                dependencyEdges = preparation.edges,
                extraSetupWarnings = preparation.warnings,
                dependencyTransaction = transaction.transaction,
            )
            if (dependencySkillId == null) transaction.finish(result, outcome)
            outcome
        } finally {
            download.file.delete()
        }
    }

    private fun inspectRepositoryCandidate(
        archive: File,
        path: String,
        isCancelled: () -> Boolean,
    ): SkillArchiveCandidate? {
        val inspection = runCatching {
            installer.inspectRepositoryZip({ archive.inputStream() }, isCancelled)
        }.getOrNull() ?: return null
        if (inspection !is SkillArchiveInspectionResult.Success) return null
        val normalized = path.trim().removeSuffix("/")
        return inspection.candidates.firstOrNull { it.relativePath == normalized }
    }

    /**
     * 先准入再落盘：已安装版本直接复用；缺失依赖只从带校验的来源自动安装；
     * 缺来源、缺校验或无法校验版本时返回待配置；版本/来源冲突在根 Skill 安装前失败关闭。
     */
    private suspend fun prepareDependencies(
        rootId: String,
        declarations: List<SkillDependencySpec>,
        isCancelled: () -> Boolean,
        failOnSetupRequired: Boolean = false,
        parentTransaction: SkillDependencyTransactionJournal? = null,
    ): DependencyPreparation {
        if (rootId.isBlank() || declarations.isEmpty()) return DependencyPreparation()
        if (isCancelled()) return DependencyPreparation(
            failure = "Skill 安装已取消",
            errorCode = SkillInstallErrorCode.CANCELLED,
        )
        val index = SkillRuntime.createIndexService(appContext)
        val installedEntries = runCatching {
            index.listSkillsForManagement(forceRefresh = true)
        }.getOrDefault(emptyList())
        val installedById = installedEntries.filter { it.installed }.associateBy { it.id }
        val snapshot = try {
            search("")
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            return DependencyPreparation(
                failure = "无法读取 Skill 来源，依赖尚未安装：" + boundedMessage(failure),
                errorCode = SkillInstallErrorCode.DEPENDENCY_SETUP_REQUIRED,
            )
        }
        val itemsById = snapshot.items.groupBy(SkillMarketItem::skillId)
        val resolver = SkillDependencyResolver<SkillMarketItem>(
            installedById = { id ->
                installedById[id]?.let { entry ->
                    SkillDependencyInstalledState(
                        entry.id,
                        entry.declaration.version ?: entry.provenance?.version,
                        entry.provenance?.sourceId,
                        entry.provenance?.archiveSha256,
                    )
                }
            },
            catalogById = { id ->
                itemsById[id].orEmpty().map { item ->
                    SkillDependencyCatalogEntry(
                        item = item,
                        skillId = item.skillId,
                        version = item.version,
                        sourceId = item.sourceId,
                        sourceName = item.sourceName,
                        trust = item.trust,
                        integrityVerified = item.integrityVerified,
                        archiveSha256 = item.archiveSha256,
                        declarations = if (item.dependenciesDeclared) {
                            SkillDependencyParser.parseList(item.dependencies)
                        } else {
                            emptyList()
                        },
                        declarationsAuthoritative = item.dependenciesDeclared && item.dependencies.any {
                            it.startsWith("skill:", ignoreCase = true)
                        },
                    )
                }
            },
            expansion = { entry, expectedSha256 ->
                if (isCancelled()) {
                    SkillDependencyExpansion.Unavailable("安装已取消")
                } else {
                    loadDependencyDeclarations(entry.item, expectedSha256, isCancelled)
                }
            },
        )
        return when (val admission = resolver.resolve(rootId, declarations)) {
            is SkillDependencyAdmission.Conflict -> DependencyPreparation(
                failure = "Skill 依赖冲突，已停止安装：" + admission.issues.joinToString("；") { issue ->
                    issue.dependencyId + " " + issue.message
                },
                errorCode = SkillInstallErrorCode.DEPENDENCY_SETUP_REQUIRED,
            )
            is SkillDependencyAdmission.SetupRequired -> {
                val issues = admission.issues.map { issue -> issue.message }.distinct()
                if (failOnSetupRequired) {
                    DependencyPreparation(
                        failure = "本地 ZIP 的 Skill 依赖尚未满足，根 Skill 未安装：" + issues.joinToString("；"),
                        errorCode = SkillInstallErrorCode.DEPENDENCY_SETUP_REQUIRED,
                    )
                } else {
                    DependencyPreparation(warnings = issues)
                }
            }
            is SkillDependencyAdmission.Ready -> {
                val warnings = mutableListOf<String>()
                val newlyInstalled = mutableListOf<SkillDependencyTransactionJournal.InstalledDependency>()
                val transaction: DependencyTransactionHandle = parentTransaction?.let { journal ->
                    DependencyTransactionHandle(journal, rootId, newlyInstalled)
                } ?: if (admission.newInstalledIds.isEmpty()) {
                    DependencyTransactionHandle(null, rootId, emptyList())
                } else beginDependencyTransaction(
                    rootId = rootId,
                    ownerSnapshots = index.tryCaptureInstalledSkillDependencyOwners(
                        listOf(rootId) + admission.newInstalledIds,
                    ),
                    // The parent journal must exist before the first dependency writes any file.
                    installedDependencies = emptyList(),
                )
                admission.installOrder.forEach { entry ->
                    if (entry.skillId !in admission.newInstalledIds) return@forEach
                    transaction.transaction?.addPlannedDependency(
                        SkillDependencyTransactionJournal.InstalledDependency(
                            skillId = entry.skillId,
                            registrySnapshot = SkillRegistryRecoverySnapshot(
                                skillId = entry.skillId,
                                entryExisted = false,
                            ),
                        ),
                    )
                }
                index.replaceInstalledSkillDependenciesAtomically(admission.owners)
                admission.installOrder.forEach { entry ->
                    if (isCancelled()) {
                        transaction.abandon()
                        return DependencyPreparation(failure = "Skill 安装已取消", errorCode = SkillInstallErrorCode.CANCELLED)
                    }
                    val expectedDigest = admission.expectedArchiveSha256[entry.skillId]
                    if (expectedDigest != null && entry.item.archiveSha256 != null &&
                        !expectedDigest.equals(entry.item.archiveSha256, ignoreCase = true)
                    ) {
                        transaction.abandon()
                        return DependencyPreparation(failure = "Skill 依赖 " + entry.skillId + " 的 catalog 摘要与声明不一致",
                            errorCode = SkillInstallErrorCode.DEPENDENCY_SETUP_REQUIRED)
                    }
                    when (
                        val outcome = installBySource(
                            item = entry.item.copy(
                                archiveSha256 = expectedDigest ?: entry.item.archiveSha256,
                            ),
                            replaceSkillId = null,
                            allowUnverified = false,
                            isCancelled = isCancelled,
                            resolveDependencies = false,
                            parentTransaction = transaction.transaction,
                            dependencySkillId = entry.skillId,
                        )
                    ) {
                        is SkillMarketInstallOutcome.Success -> {
                            if (outcome.setupRequiredSkillIds.isNotEmpty()) {
                                warnings += "依赖 " + entry.skillId + " 仍需配置：" +
                                    outcome.prerequisiteWarnings.joinToString("；")
                            }
                        }
                        is SkillMarketInstallOutcome.Conflict -> {
                            val installedNow = runCatching {
                                index.listSkillsForManagement(forceRefresh = true)
                            }.getOrDefault(emptyList())
                                .firstOrNull { it.id == entry.skillId && it.installed }
                            if (installedNow == null) {
                                transaction.abandon()
                                return DependencyPreparation(
                                    failure = "Skill 依赖 " + entry.skillId + " 安装冲突，已停止安装",
                                    errorCode = SkillInstallErrorCode.DEPENDENCY_SETUP_REQUIRED,
                                )
                            }
                        }
                        is SkillMarketInstallOutcome.ConfirmationRequired -> {
                            transaction.abandon()
                            return DependencyPreparation(
                                failure = "Skill 依赖 " + entry.skillId + " 缺少可校验来源",
                                errorCode = SkillInstallErrorCode.DEPENDENCY_SETUP_REQUIRED,
                            )
                        }
                        is SkillMarketInstallOutcome.Failure -> {
                            transaction.abandon()
                            return DependencyPreparation(
                            failure = "Skill 依赖 " + entry.skillId + " 安装失败：" + outcome.message,
                            errorCode = SkillInstallErrorCode.DEPENDENCY_SETUP_REQUIRED,
                            )
                        }
                    }
                }
                DependencyPreparation(
                    edges = admission.owners,
                    rootEdges = admission.edges[rootId].orEmpty(),
                    installedDependencies = newlyInstalled,
                    transaction = transaction,
                    warnings = warnings,
                )
            }
        }
    }

    private data class DependencyPreparation(
        val edges: Map<String, Set<String>> = emptyMap(),
        val rootEdges: Set<String> = emptySet(),
        val installedDependencies: List<io.github.mangi.eta.agent.skill.SkillDependencyTransactionJournal.InstalledDependency> = emptyList(),
        val transaction: DependencyTransactionHandle? = null,
        val warnings: List<String> = emptyList(),
        val failure: String? = null,
        val errorCode: SkillInstallErrorCode? = null,
    )

    private inner class DependencyTransactionHandle(
        val transaction: io.github.mangi.eta.agent.skill.SkillDependencyTransactionJournal?,
        private val rootId: String,
        private val installedDependencies: List<SkillDependencyTransactionJournal.InstalledDependency>,
    ) {
        private var settled = false

        fun finish(result: SkillInstallResult, outcome: SkillMarketInstallOutcome? = null) {
            if (settled) return
            if (result is SkillInstallResult.Success &&
                (outcome == null || outcome is SkillMarketInstallOutcome.Success)
            ) {
                transaction?.complete()
                settled = true
            } else {
                abandon()
            }
        }

        fun abandon() {
            if (settled) return
            settled = true
            val journal = transaction ?: return
            val index = SkillRuntime.createIndexService(appContext)
            val root = File(appContext.filesDir, "skills")
            val recovered = recoverPendingSkillOperations(root)
            index.restoreRecoveredRegistry(recovered)
            completeRecoveredSkillOperations(root, recovered)
        }
    }

    private fun beginDependencyTransaction(
        rootId: String,
        ownerSnapshots: List<io.github.mangi.eta.agent.skill.SkillDependencyRecoverySnapshot>,
        installedDependencies: List<SkillDependencyTransactionJournal.InstalledDependency>,
    ): DependencyTransactionHandle {
        val root = File(appContext.filesDir, "skills")
        val transaction = SkillDependencyTransactionJournal.begin(
            skillsRoot = root,
            ownerSnapshots = ownerSnapshots,
            installedDependencies = installedDependencies,
            rootId = rootId,
        )
        return DependencyTransactionHandle(transaction, rootId, installedDependencies)
    }

    /** 读取下一层声明前，重新下载并检查选中 catalog 条目的同一归档。 */
    private fun loadDependencyDeclarations(
        item: SkillMarketItem,
        expectedSha256: String?,
        isCancelled: () -> Boolean,
    ): SkillDependencyExpansion {
        return try {
            when (item.sourceType) {
                SkillSourceType.GITHUB_REPOSITORY -> {
                    val repository = item.repository?.let(GitHubSkillRepositoryParser::parse)
                        ?: return SkillDependencyExpansion.Unavailable("GitHub 仓库信息缺失")
                    val ref = item.ref?.takeIf(COMMIT_SHA::matches)
                        ?: return SkillDependencyExpansion.Unavailable("GitHub commit 未固定")
                    githubSource.downloadArchive(repository.copy(ref = ref)).use { archive ->
                        if (archive.commitSha != ref) {
                            SkillDependencyExpansion.ChecksumMismatch("下载归档的 commit 与固定版本不一致")
                        } else {
                            extractDependencyDeclarations(archive.file, item.path, expectedSha256, isCancelled)
                        }
                    }
                }
                SkillSourceType.GITLAB_REPOSITORY -> {
                    val repository = item.repository?.let(GitLabSkillRepositoryParser::parse)
                        ?: return SkillDependencyExpansion.Unavailable("GitLab 仓库信息缺失")
                    val ref = item.ref?.takeIf(COMMIT_SHA::matches)
                        ?: return SkillDependencyExpansion.Unavailable("GitLab commit 未固定")
                    gitlabSource.downloadArchive(repository.copy(ref = ref)).use { archive ->
                        if (archive.commitSha != ref) {
                            SkillDependencyExpansion.ChecksumMismatch("下载归档的 commit 与固定版本不一致")
                        } else {
                            extractDependencyDeclarations(archive.file, item.path, expectedSha256, isCancelled)
                        }
                    }
                }
                SkillSourceType.GITEE_REPOSITORY -> {
                    val repository = item.repository?.let(GiteeSkillRepositoryParser::parse)
                        ?: return SkillDependencyExpansion.Unavailable("Gitee 仓库信息缺失")
                    val ref = item.ref?.takeIf(COMMIT_SHA::matches)
                        ?: return SkillDependencyExpansion.Unavailable("Gitee commit 未固定")
                    giteeSource.downloadArchive(repository.copy(ref = ref)).use { archive ->
                        if (archive.commitSha != ref) {
                            SkillDependencyExpansion.ChecksumMismatch("下载归档的 commit 与固定版本不一致")
                        } else {
                            extractDependencyDeclarations(archive.file, item.path, expectedSha256, isCancelled)
                        }
                    }
                }
                SkillSourceType.HTTP_CATALOG -> {
                    val download = downloadHttpArchive(item, isCancelled)
                    try {
                        extractDependencyDeclarations(
                            download.file,
                            null,
                            expectedSha256 ?: item.archiveSha256,
                            isCancelled,
                            localArchive = true,
                            requireExpectedSha256 = true,
                        )
                    } finally {
                        download.file.delete()
                    }
                }
                else -> SkillDependencyExpansion.Unavailable("不支持的来源类型")
            }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            SkillDependencyExpansion.Unavailable(boundedMessage(failure))
        }
    }

    private fun extractDependencyDeclarations(
        archive: File,
        path: String?,
        expectedSha256: String?,
        isCancelled: () -> Boolean,
        localArchive: Boolean = false,
        requireExpectedSha256: Boolean = false,
    ): SkillDependencyExpansion {
        val actual = sha256(archive)
        if (requireExpectedSha256 && expectedSha256 == null) {
            return SkillDependencyExpansion.Unavailable("未提供归档摘要，不能读取下一层依赖")
        }
        if (expectedSha256 != null && !actual.equals(expectedSha256, ignoreCase = true)) {
            return SkillDependencyExpansion.ChecksumMismatch("声明摘要与实际归档不一致")
        }
        if (isCancelled()) return SkillDependencyExpansion.Unavailable("安装已取消")
        val inspection = if (localArchive) {
            installer.inspectLocalZip({ archive.inputStream() }, isCancelled)
        } else {
            installer.inspectRepositoryZip({ archive.inputStream() }, isCancelled)
        }
        if (inspection !is SkillArchiveInspectionResult.Success) {
            return SkillDependencyExpansion.Unavailable("归档 Skill 结构无效")
        }
        val normalizedPath = path?.trim()?.removeSuffix("/")
        val candidate = inspection.candidates.firstOrNull {
            normalizedPath == null || it.relativePath == normalizedPath
        }
            ?: return SkillDependencyExpansion.Unavailable("归档中未找到对应 Skill")
        return SkillDependencyExpansion.Declarations(candidate.skillDependencies, actual)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    private fun applyInstallResult(
        result: SkillInstallResult,
        item: SkillMarketItem,
        commitSha: String?,
        archiveSha256: String? = item.archiveSha256,
        dependencyEdges: Map<String, Set<String>> = emptyMap(),
        extraSetupWarnings: List<String> = emptyList(),
        dependencyTransaction: SkillDependencyTransactionJournal? = null,
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
                        archiveSha256 = installed.archiveSha256 ?: archiveSha256,
                        version = item.version,
                        installedAt = installedAt,
                    )
                },
            )
            val indexService = SkillRuntime.createIndexService(appContext)
            val refreshed = indexService.withMutationLock(
                excludedRecoveryDirectories = dependencyTransaction?.operationDirectory?.let(::setOf).orEmpty(),
            ) {
                indexService.listSkillsForManagement(forceRefresh = true)
            }
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
            if (extraSetupWarnings.isNotEmpty()) {
                result.installed.forEach { installed ->
                    val entry = refreshed.firstOrNull { it.id == installed.id && it.installed }
                        ?: return@forEach
                    if (entry.enabled) {
                        runCatching { indexService.setSkillEnabled(entry.id, false) }
                    }
                    paused += entry.id
                    warnings += extraSetupWarnings
                }
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
        val urls = SkillArchiveSources.candidates(item.archiveUrl, item.archiveMirrors)
        if (urls.isEmpty()) throw SkillCatalogException("INVALID_ARCHIVE_URL", "Skill 归档地址无效")
        var lastFailure: Throwable? = null
        urls.forEach { url ->
            try {
                return downloadHttpArchive(url, item.archiveSha256, isCancelled)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                lastFailure = failure
            }
        }
        throw (lastFailure ?: SkillCatalogException("SOURCE_UNAVAILABLE", "所有 Skill 下载地址均不可用"))
    }

    private fun materializeLocalArchive(
        openStream: () -> java.io.InputStream,
        isCancelled: () -> Boolean,
    ): DownloadedArchive {
        val directory = File(appContext.filesDir, ".eta-skill-root-staging")
        ensureSafeDirectory(directory)
        val file = File.createTempFile("eta-local-skill-", ".zip", directory)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            var total = 0L
            openStream().use { input ->
                FileOutputStream(file).buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (isCancelled()) {
                            throw SkillCatalogException("INSTALL_CANCELLED", "Skill 安装已取消")
                        }
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_ARCHIVE_BYTES) {
                            throw SkillCatalogException("ARCHIVE_TOO_LARGE", "Skill 归档超过 32 MiB 上限")
                        }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (file.length() == 0L) {
                throw SkillCatalogException("EMPTY_ARCHIVE", "Skill 归档为空")
            }
            return DownloadedArchive(
                file,
                digest.digest().joinToString("") { byte ->
                    (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                },
            )
        } catch (failure: Throwable) {
            file.delete()
            throw failure
        }
    }

    private fun downloadHttpArchive(
        url: String,
        expectedSha256: String?,
        isCancelled: () -> Boolean,
    ): DownloadedArchive {
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
            expectedSha256?.let { expected ->
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
        val LOCAL_SHA256 = Regex("[a-f0-9]{64}")
        const val MAX_ARCHIVE_BYTES = 32L * 1024L * 1024L
        const val MAX_DETAIL_BYTES = 256 * 1024
        const val MAX_URL_LENGTH = 2_048
    }
}

internal object SkillArchiveSources {
    fun candidates(primary: String?, mirrors: List<String>): List<String> =
        (listOfNotNull(primary) + mirrors).mapNotNull { raw ->
            val uri = runCatching { URI(raw.trim()) }.getOrNull() ?: return@mapNotNull null
            if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank() ||
                uri.rawUserInfo != null || uri.rawFragment != null || uri.port !in setOf(-1, 443)) null
            else uri.toASCIIString()
        }.distinct().take(9)
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
            archiveMirrors = stringList(value.optJSONArray("archiveMirrors"), 8, 2_048)
                .map { raw ->
                    runCatching { URI(raw) }.getOrNull()?.takeIf {
                        it.scheme.equals("https", true) && !it.host.isNullOrBlank() && it.rawUserInfo == null &&
                            it.rawFragment == null && it.port in setOf(-1, 443)
                    }?.toASCIIString() ?: throw SkillCatalogException(
                        "INVALID_ARCHIVE_URL", "Skill 镜像地址无效：$skillId",
                    )
                },
            archiveSha256 = checksum,
            tags = stringList(value.optJSONArray("tags"), 12, 40),
            usage = visible(value.optString("usage"), 0, 8_000),
            dependencies = stringList(value.optJSONArray("dependencies"), 32, 160),
            dependenciesDeclared = value.has("dependencies") && !value.isNull("dependencies"),
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


