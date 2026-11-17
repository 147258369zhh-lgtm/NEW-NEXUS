package io.github.mangi.eta.agent.skill

/**
 * SKILL.md 中声明的另一个 Skill 依赖。
 *
 * 只有以 skill: 前缀写在普通依赖字段里，或写进 skill-dependencies / depends-on
 * 这类专用字段的条目才会被当作 Skill 依赖；root、linux、bash 等能力依赖继续沿用
 * SkillReadinessInspector 的只读检查，不会被误当成需要安装的 Skill。
 *
 * 紧凑写法：skill:<id>[@<version>][?source=<sourceId>]. 可选 sha256 参数。
 */
data class SkillDependencySpec(
    val id: String,
    val versionConstraint: String? = null,
    val sourceId: String? = null,
    val archiveSha256: String? = null,
    val raw: String = id,
    val declarationProblem: String? = null,
) {
    val requirement: String
        get() = buildString {
            append("skill:").append(id)
            versionConstraint?.let { append('@').append(it) }
            sourceId?.let { append("?source=").append(it) }
        }
}

/** Parses optional dependency metadata without treating descriptive prose as executable requirements. */
internal object SkillDependencyParser {
    private val KEYS = listOf(
        "dependencies",
        "requires",
        "requirements",
        "required-tools",
        "required_tools",
        "tools",
    )
    private val SKILL_KEYS = listOf(
        "skill-dependencies",
        "skill_dependencies",
        "depends-on",
        "depends_on",
    )
    private val SKILL_ID = Regex("[a-z0-9]+(?:-[a-z0-9]+)*")
    private val SOURCE_ID = Regex("[A-Za-z0-9._:-]{1,128}")
    private val SHA_256 = Regex("[a-f0-9]{64}")

    fun parse(
        frontmatter: Map<String, String>,
        metadata: Map<String, String>,
    ): List<String> = buildList {
        KEYS.forEach { key ->
            frontmatter[key]?.let(::parseValue)?.let(::addAll)
            metadata[key]?.let(::parseValue)?.let(::addAll)
        }
    }.map(String::trim)
        .filter { it.isNotEmpty() && it.length <= 160 && it.none(Char::isISOControl) }
        .distinctBy { it.lowercase() }
        .take(MAX_DEPENDENCIES)

    /** 专用字段允许裸 id；普通依赖字段必须带 skill: 前缀。非法声明会保留为 problem 以便失败关闭。 */
    fun parseDeclarations(
        frontmatter: Map<String, String>,
        metadata: Map<String, String>,
    ): List<SkillDependencySpec> {
        val declarations = mutableListOf<SkillDependencySpec>()
        SKILL_KEYS.forEach { key ->
            frontmatter[key]?.let { raw ->
                parseValue(raw).forEach { entry ->
                    parseDeclaration(entry, allowBare = true)?.let(declarations::add)
                }
            }
            metadata[key]?.let { raw ->
                parseValue(raw).forEach { entry ->
                    parseDeclaration(entry, allowBare = true)?.let(declarations::add)
                }
            }
        }
        KEYS.forEach { key ->
            frontmatter[key]?.let { raw ->
                parseValue(raw).forEach { entry ->
                    parseDeclaration(entry, allowBare = false)?.let(declarations::add)
                }
            }
            metadata[key]?.let { raw ->
                parseValue(raw).forEach { entry ->
                    parseDeclaration(entry, allowBare = false)?.let(declarations::add)
                }
            }
        }
        return declarations
            .distinctBy { dependencyKey(it) }
            .take(MAX_DEPENDENCIES)
    }

    /** 目录依赖字段是普通字符串列表，只接受带 skill: 前缀的条目。 */
    fun parseList(values: List<String>): List<SkillDependencySpec> = values
        .flatMap(::parseValue)
        .mapNotNull { entry ->
            // 非 Skill 条目是普通工具能力描述；以 skill: 开头的畸形条目必须保留问题。
            if (!entry.startsWith("skill:", ignoreCase = true)) null
            else parseDeclaration(entry, allowBare = false)
        }
        .distinctBy { dependencyKey(it) }
        .take(MAX_DEPENDENCIES)

    private fun dependencyKey(spec: SkillDependencySpec): String =
        spec.id + "\u0000" + spec.versionConstraint.orEmpty() + "\u0000" +
            spec.sourceId.orEmpty() + "\u0000" + spec.archiveSha256.orEmpty()

    private fun parseDeclaration(raw: String, allowBare: Boolean): SkillDependencySpec? {
        val value = raw.trim().trim('\'', '"')
        if (value.isEmpty()) return null
        val hasPrefix = value.startsWith("skill:", ignoreCase = true)
        if (!hasPrefix && !allowBare) return null
        val body = if (hasPrefix) value.substring(6) else value
        if (body.isBlank()) return problem(value, "依赖缺少 Skill id")

        val queryIndex = body.indexOf('?')
        val head = if (queryIndex >= 0) body.substring(0, queryIndex) else body
        val query = if (queryIndex >= 0) body.substring(queryIndex + 1) else ""
        val atIndex = head.indexOf('@')
        if (atIndex >= 0 && head.indexOf('@', atIndex + 1) >= 0) {
            return problem(value, "依赖只能声明一个版本范围")
        }
        val id = if (atIndex >= 0) head.substring(0, atIndex) else head
        val constraint = if (atIndex >= 0) head.substring(atIndex + 1).trim() else null
        if (!SKILL_ID.matches(id)) return problem(value, "Skill 依赖 id 无效")
        if (constraint != null) {
            if (constraint.isEmpty() || constraint.length > MAX_CONSTRAINT_LENGTH) {
                return problem(value, "Skill 依赖版本范围无效")
            }
            if (SkillVersionConstraint.parse(constraint) == null) {
                return problem(value, "Skill 依赖版本范围无效")
            }
        }

        var sourceId: String? = null
        var sha256: String? = null
        if (query.isNotEmpty()) {
            query.split('&').forEach { parameter ->
                val separator = parameter.indexOf('=')
                if (separator <= 0) return problem(value, "Skill 依赖参数格式无效")
                val key = parameter.substring(0, separator).trim()
                val parameterValue = parameter.substring(separator + 1).trim()
                when (key) {
                    "source" -> {
                        if (sourceId != null || !SOURCE_ID.matches(parameterValue)) {
                            return problem(value, "Skill 依赖来源无效")
                        }
                        sourceId = parameterValue
                    }
                    "sha256" -> {
                        val digest = parameterValue.lowercase()
                        if (sha256 != null || !SHA_256.matches(digest)) {
                            return problem(value, "Skill 依赖校验和格式无效")
                        }
                        sha256 = digest
                    }
                    else -> return problem(value, "Skill 依赖参数不受支持")
                }
            }
        }
        return SkillDependencySpec(
            id = id,
            versionConstraint = constraint,
            sourceId = sourceId,
            archiveSha256 = sha256,
            raw = value,
        )
    }

    private fun problem(raw: String, reason: String): SkillDependencySpec =
        SkillDependencySpec(id = raw.take(64), raw = raw, declarationProblem = reason)

    private fun parseValue(raw: String): List<String> {
        val normalized = raw.trim().removePrefix("[").removeSuffix("]")
        if (normalized.isBlank()) return emptyList()
        return normalized.split(',', ';', '\n')
            .map { value -> value.trim().removePrefix("-").trim().trim('\'', '"') }
            .filter(String::isNotBlank)
    }

    private const val MAX_DEPENDENCIES = 32
    private const val MAX_CONSTRAINT_LENGTH = 64
}

/** 只支持明确的比较符和 semver 主次修订号；未知写法在解析阶段就失败关闭。 */
internal sealed interface SkillVersionConstraint {
    fun accepts(version: SkillVersionNumber): Boolean

    data object Any : SkillVersionConstraint {
        override fun accepts(version: SkillVersionNumber): Boolean = true
    }

    data class Exact(private val version: SkillVersionNumber) : SkillVersionConstraint {
        override fun accepts(version: SkillVersionNumber): Boolean = this.version == version
    }

    data class AtLeast(private val version: SkillVersionNumber, private val inclusive: Boolean) :
        SkillVersionConstraint {
        override fun accepts(version: SkillVersionNumber): Boolean =
            if (inclusive) version >= this.version else version > this.version
    }

    data class AtMost(private val version: SkillVersionNumber, private val inclusive: Boolean) :
        SkillVersionConstraint {
        override fun accepts(version: SkillVersionNumber): Boolean =
            if (inclusive) version <= this.version else version < this.version
    }

    data class CompatibleMajor(private val version: SkillVersionNumber) : SkillVersionConstraint {
        override fun accepts(version: SkillVersionNumber): Boolean {
            if (version < this.version) return false
            val upper = if (this.version.major == 0) {
                SkillVersionNumber(0, this.version.minor + 1, 0)
            } else {
                SkillVersionNumber(this.version.major + 1, 0, 0)
            }
            return version < upper
        }
    }

    data class CompatibleMinor(private val version: SkillVersionNumber) : SkillVersionConstraint {
        override fun accepts(version: SkillVersionNumber): Boolean {
            if (version < this.version) return false
            return version < SkillVersionNumber(this.version.major, this.version.minor + 1, 0)
        }
    }

    companion object {
        fun parse(raw: String?): SkillVersionConstraint? {
            val value = raw?.trim().orEmpty()
            if (value.isEmpty() || value == "*") return Any
            val match = PATTERN.matchEntire(value) ?: return null
            val version = SkillVersionNumber.parse(match.groupValues[2]) ?: return null
            return when (match.groupValues[1]) {
                "", "=" -> Exact(version)
                ">=" -> AtLeast(version, inclusive = true)
                ">" -> AtLeast(version, inclusive = false)
                "<=" -> AtMost(version, inclusive = true)
                "<" -> AtMost(version, inclusive = false)
                "^" -> CompatibleMajor(version)
                "~" -> CompatibleMinor(version)
                else -> null
            }
        }

        private val PATTERN = Regex(
            "^(>=|<=|>|<|=|\\^|~)?\\s*(v?\\d{1,6}(?:\\.\\d{1,6}){0,2}(?:[-+][0-9A-Za-z.-]+)?)$",
        )
    }
}

internal data class SkillVersionNumber(
    val major: Int,
    val minor: Int,
    val patch: Int,
) : Comparable<SkillVersionNumber> {
    override fun compareTo(other: SkillVersionNumber): Int {
        val majorCompare = major.compareTo(other.major)
        if (majorCompare != 0) return majorCompare
        val minorCompare = minor.compareTo(other.minor)
        if (minorCompare != 0) return minorCompare
        return patch.compareTo(other.patch)
    }

    companion object {
        fun parse(raw: String?): SkillVersionNumber? {
            val value = raw?.trim().orEmpty().removePrefix("v")
            if (value.isEmpty()) return null
            val numeric = value.substringBefore('-').substringBefore('+')
            val parts = numeric.split('.')
            if (parts.size !in 1..3) return null
            val numbers = IntArray(3)
            parts.forEachIndexed { index, part ->
                val number = part.toIntOrNull() ?: return null
                if (number < 0) return null
                numbers[index] = number
            }
            return SkillVersionNumber(numbers[0], numbers[1], numbers[2])
        }
    }
}

internal data class SkillDependencyInstalledState(
    val id: String,
    val version: String?,
    val sourceId: String? = null,
    val archiveSha256: String? = null,
)

internal data class SkillDependencyCatalogEntry<out T>(
    val item: T,
    val skillId: String,
    val version: String?,
    val sourceId: String,
    val sourceName: String,
    val trust: String,
    val integrityVerified: Boolean,
    val archiveSha256: String? = null,
    val declarations: List<SkillDependencySpec> = emptyList(),
    val declarationsAuthoritative: Boolean = true,
)

internal enum class SkillDependencyIssueKind {
    INVALID_DECLARATION,
    MISSING_SOURCE,
    UNVERIFIED_SOURCE,
    VERSION_UNKNOWN,
    VERSION_CONFLICT,
    SOURCE_CONFLICT,
    CHECKSUM_CONFLICT,
    ARCHIVE_UNAVAILABLE,
    ARCHIVE_CHECKSUM_MISMATCH,
    CYCLE,
    LIMIT,
}

internal data class SkillDependencyIssue(
    val dependencyId: String,
    val kind: SkillDependencyIssueKind,
    val message: String,
)

/** catalog 未声明依赖时，按需读取已准入依赖归档中的 SKILL.md 得到下一层依赖。 */
internal sealed interface SkillDependencyExpansion {
    data class Declarations(
        val declarations: List<SkillDependencySpec>,
        val archiveSha256: String? = null,
    ) : SkillDependencyExpansion

    data class Unavailable(val message: String) : SkillDependencyExpansion

    data class ChecksumMismatch(val message: String) : SkillDependencyExpansion
}

internal sealed interface SkillDependencyAdmission<out T> {
    data class Ready<out T>(
        val installOrder: List<SkillDependencyCatalogEntry<T>>,
        val reusedIds: Set<String>,
        val edges: Map<String, Set<String>>,
        val owners: Map<String, Set<String>> = emptyMap(),
        val newInstalledIds: Set<String> = emptySet(),
        /** 已声明但没有出现在 catalog 的归档摘要；安装时必须用实际归档复核。 */
        val expectedArchiveSha256: Map<String, String> = emptyMap(),
    ) : SkillDependencyAdmission<T>

    data class SetupRequired<out T>(
        val issues: List<SkillDependencyIssue>,
    ) : SkillDependencyAdmission<T>

    data class Conflict<out T>(
        val issues: List<SkillDependencyIssue>,
    ) : SkillDependencyAdmission<T>
}

/**
 * 纯函数式依赖准入：先验证已安装版本，再从调用方目录中挑选带完整性证明的来源并生成安装顺序。
 * 任意冲突都会失败关闭，缺少来源、校验或可验证版本的依赖会明确标记为待配置。
 */
internal class SkillDependencyResolver<T>(
    private val installedById: (String) -> SkillDependencyInstalledState?,
    private val catalogById: (String) -> List<SkillDependencyCatalogEntry<T>>,
    private val maxSkills: Int = 32,
    private val maxDepth: Int = 8,
    private val expansion: ((SkillDependencyCatalogEntry<T>, String?) -> SkillDependencyExpansion)? = null,
) {
    fun resolve(
        rootId: String,
        declarations: List<SkillDependencySpec>,
    ): SkillDependencyAdmission<T> = resolveInternal(rootId, declarations)

    private fun resolveInternal(
        rootId: String,
        declarations: List<SkillDependencySpec>,
    ): SkillDependencyAdmission<T> {
        val state = ResolutionState(rootId)
        state.walk(rootId, declarations, depth = 0)
        val conflicts = state.issues.values.filter { it.kind in CONFLICT_KINDS }
        if (conflicts.isNotEmpty()) return SkillDependencyAdmission.Conflict(conflicts)
        if (state.issues.isNotEmpty()) {
            return SkillDependencyAdmission.SetupRequired(state.issues.values.toList())
        }
        return SkillDependencyAdmission.Ready(
            installOrder = state.order.values.toList(),
            reusedIds = state.selections.filterValues { it is Selection.Installed }.keys.toSet(),
            edges = state.edges.mapValues { it.value.toSet() },
            owners = buildMap {
                // The root belongs to this admission even when it is an update.
                // Its pre-install edges are captured separately by the installer.
                put(rootId, state.edges[rootId].orEmpty().toSet())
                state.selections.forEach { (id, selection) ->
                    if (selection is Selection.Catalog) {
                        put(id, state.edges[id].orEmpty().toSet())
                    }
                }
            },
            newInstalledIds = state.selections
                .filterValues { it is Selection.Catalog }
                .keys
                .toSet(),
            expectedArchiveSha256 = state.expectedArchiveSha256(),
        )
    }

    private inner class ResolutionState(private val rootId: String) {
        val constraints = LinkedHashMap<String, MutableList<ConstraintUse>>()
        val selections = LinkedHashMap<String, Selection<T>>()
        val issues = LinkedHashMap<String, SkillDependencyIssue>()
        val order = LinkedHashMap<String, SkillDependencyCatalogEntry<T>>()
        val edges = LinkedHashMap<String, LinkedHashSet<String>>()
        val expandedArchiveSha256 = LinkedHashMap<String, String>()
        private val visiting = linkedSetOf(rootId)

        fun walk(ownerId: String, declarations: List<SkillDependencySpec>, depth: Int) {
            declarations.forEach { spec ->
                if (spec.declarationProblem != null) {
                    issue(spec.id, SkillDependencyIssueKind.INVALID_DECLARATION, spec.declarationProblem)
                    return@forEach
                }
                if (spec.id == ownerId) {
                    issue(spec.id, SkillDependencyIssueKind.CYCLE, "Skill 依赖不能指向自身")
                    return@forEach
                }
                edges.getOrPut(ownerId) { linkedSetOf() }.add(spec.id)
                val uses = constraints.getOrPut(spec.id) { mutableListOf() }
                if (uses.none { it.ownerId == ownerId && it.spec == spec }) {
                    uses += ConstraintUse(spec, ownerId)
                }
                val requestedSources = uses.mapNotNull { it.spec.sourceId }.toSet()
                if (requestedSources.size > 1) {
                    issue(
                        spec.id,
                        SkillDependencyIssueKind.SOURCE_CONFLICT,
                        "Skill 依赖 " + spec.id + " 被要求来自多个来源：" +
                            requestedSources.sorted().joinToString(),
                    )
                    return@forEach
                }
                val requestedChecksums = uses.mapNotNull { it.spec.archiveSha256 }.toSet()
                if (requestedChecksums.size > 1) {
                    issue(
                        spec.id,
                        SkillDependencyIssueKind.CHECKSUM_CONFLICT,
                        "Skill 依赖 " + spec.id + " 被要求使用多个归档摘要",
                    )
                    return@forEach
                }

                if (spec.id in visiting) {
                    issue(spec.id, SkillDependencyIssueKind.CYCLE, "Skill 依赖形成环：" + spec.id)
                    return@forEach
                }
                val existing = selections[spec.id]
                if (existing != null) {
                    validateSelection(spec.id, existing)
                    return@forEach
                }
                if (selections.size >= maxSkills) {
                    issue(spec.id, SkillDependencyIssueKind.LIMIT, "Skill 依赖数量超过 $maxSkills 个")
                    return@forEach
                }
                if (depth >= maxDepth) {
                    issue(spec.id, SkillDependencyIssueKind.LIMIT, "Skill 依赖层级超过 $maxDepth 层")
                    return@forEach
                }

                val installed = installedById(spec.id)
                if (installed != null) {
                    val selection = Selection.Installed(installed)
                    selections[spec.id] = selection
                    validateSelection(spec.id, selection)
                    return@forEach
                }

                val candidates = catalogById(spec.id)
                if (candidates.isEmpty()) {
                    issue(
                        spec.id,
                        SkillDependencyIssueKind.MISSING_SOURCE,
                        "没有可用来源提供 Skill 依赖 " + spec.id + "，请先配置来源",
                    )
                    return@forEach
                }
                val sourceFiltered = requestedSources.singleOrNull()?.let { sourceId ->
                    candidates.filter { it.sourceId == sourceId }
                } ?: candidates
                if (sourceFiltered.isEmpty()) {
                    issue(
                        spec.id,
                        SkillDependencyIssueKind.MISSING_SOURCE,
                        "指定来源 " + requestedSources.single() + " 没有提供 Skill 依赖 " + spec.id,
                    )
                    return@forEach
                }
                val verified = sourceFiltered.filter { it.integrityVerified }
                if (verified.isEmpty()) {
                    issue(
                        spec.id,
                        SkillDependencyIssueKind.UNVERIFIED_SOURCE,
                        "Skill 依赖 " + spec.id + " 缺少 SHA-256 或固定提交，无法自动安装",
                    )
                    return@forEach
                }
                val eligible = verified.filter { entry -> uses.all { use -> entryMatches(use.spec, entry) } }
                if (eligible.isEmpty()) {
                    val hasChecksumMismatch = requestedChecksums.isNotEmpty() && verified.any { entry ->
                        entry.archiveSha256 != null && entry.archiveSha256 != requestedChecksums.single()
                    }
                    val hasKnownVersion = verified.any { SkillVersionNumber.parse(it.version) != null }
                    issue(
                        spec.id,
                        when {
                            hasChecksumMismatch -> SkillDependencyIssueKind.CHECKSUM_CONFLICT
                            hasKnownVersion -> SkillDependencyIssueKind.VERSION_CONFLICT
                            else -> SkillDependencyIssueKind.VERSION_UNKNOWN
                        },
                        if (hasChecksumMismatch) {
                            "Skill 依赖 " + spec.id + " 的可用归档摘要与声明不一致"
                        } else if (hasKnownVersion) {
                            "Skill 依赖 " + spec.id + " 的可用版本不满足 " +
                                uses.mapNotNull { it.spec.versionConstraint }.distinct().joinToString()
                        } else {
                            "Skill 依赖 " + spec.id + " 缺少可验证版本，无法满足版本要求"
                        },
                    )
                    return@forEach
                }
                val chosen = eligible.sortedWith(
                    compareBy<SkillDependencyCatalogEntry<T>> { trustRank(it.trust) }
                        .thenByDescending { SkillVersionNumber.parse(it.version) ?: SkillVersionNumber(0, 0, 0) }
                        .thenBy { it.sourceId }
                        .thenBy { it.sourceName }
                        .thenBy { it.archiveSha256.orEmpty() },
                ).first()
                selections[spec.id] = Selection.Catalog(chosen)
                visiting += spec.id
                walk(
                    spec.id,
                    nestedDeclarations(
                        chosen,
                        requestedChecksums.singleOrNull() ?: chosen.archiveSha256,
                    ),
                    depth + 1,
                )
                visiting -= spec.id
                order.putIfAbsent(spec.id, chosen)
            }
        }

        /**
         * A declared empty list is authoritative; a missing/filtered catalog field is not.
         * The market passes declarationsAuthoritative=false when it must inspect the archive.
         */
        private fun nestedDeclarations(
            entry: SkillDependencyCatalogEntry<T>,
            expectedSha256: String?,
        ): List<SkillDependencySpec> {
            if (entry.declarations.isNotEmpty() || entry.declarationsAuthoritative) return entry.declarations
            val loader = expansion ?: run {
                issue(
                    entry.skillId,
                    SkillDependencyIssueKind.ARCHIVE_UNAVAILABLE,
                    "Skill 依赖 " + entry.skillId + " 的 catalog 未列依赖且没有归档解析器",
                )
                return emptyList()
            }
            return when (val result = loader(entry, expectedSha256)) {
                is SkillDependencyExpansion.Declarations -> {
                    result.archiveSha256?.let { expandedArchiveSha256[entry.skillId] = it }
                    result.declarations
                }
                is SkillDependencyExpansion.Unavailable -> {
                    issue(
                        entry.skillId,
                        SkillDependencyIssueKind.ARCHIVE_UNAVAILABLE,
                        "Skill 依赖 " + entry.skillId + " 的归档声明无法读取：" + result.message.take(200),
                    )
                    emptyList()
                }
                is SkillDependencyExpansion.ChecksumMismatch -> {
                    issue(
                        entry.skillId,
                        SkillDependencyIssueKind.ARCHIVE_CHECKSUM_MISMATCH,
                        "Skill 依赖 " + entry.skillId + " 的归档校验失败：" + result.message.take(200),
                    )
                    emptyList()
                }
            }
        }

        fun expectedArchiveSha256(): Map<String, String> = buildMap {
            selections.forEach { (id, selection) ->
                if (selection !is Selection.Catalog) return@forEach
                val declaredChecksum = constraints[id].orEmpty()
                    .mapNotNull { it.spec.archiveSha256 }
                    .distinct()
                    .singleOrNull()
                val checksum = declaredChecksum ?: selection.entry.archiveSha256 ?: expandedArchiveSha256[id]
                checksum?.let { put(id, it) }
            }
        }

        private fun validateSelection(id: String, selection: Selection<T>) {
            val uses = constraints[id].orEmpty()
            when (selection) {
                is Selection.Installed -> {
                    val version = SkillVersionNumber.parse(selection.state.version)
                    uses.forEach { use ->
                        val sourceId = use.spec.sourceId
                        if (sourceId != null && selection.state.sourceId != sourceId) {
                            issue(
                                id,
                                SkillDependencyIssueKind.SOURCE_CONFLICT,
                                "已安装的 Skill " + id + " 来源为 " +
                                    (selection.state.sourceId ?: "未知") + "，不满足 " + sourceId,
                            )
                        }
                        val checksum = use.spec.archiveSha256
                        if (checksum != null && selection.state.archiveSha256 != checksum) {
                            issue(
                                id,
                                SkillDependencyIssueKind.CHECKSUM_CONFLICT,
                                "已安装的 Skill " + id + " 归档摘要为 " +
                                    (selection.state.archiveSha256 ?: "未知") +
                                    "，与声明的 " + checksum + " 不一致",
                            )
                        }
                        val constraintRaw = use.spec.versionConstraint ?: return@forEach
                        if (version == null) {
                            issue(
                                id,
                                SkillDependencyIssueKind.VERSION_CONFLICT,
                                "已安装的 Skill " + id + " 没有可比较版本，无法满足 " + constraintRaw,
                            )
                            return@forEach
                        }
                        val constraint = SkillVersionConstraint.parse(constraintRaw) ?: return@forEach
                        if (!constraint.accepts(version)) {
                            issue(
                                id,
                                SkillDependencyIssueKind.VERSION_CONFLICT,
                                "已安装的 Skill " + id + " 版本 " + selection.state.version +
                                    " 不满足 " + constraintRaw,
                            )
                        }
                    }
                }
                is Selection.Catalog -> {
                    val version = SkillVersionNumber.parse(selection.entry.version)
                    uses.forEach { use ->
                        val sourceId = use.spec.sourceId
                        if (sourceId != null && selection.entry.sourceId != sourceId) {
                            issue(
                                id,
                                SkillDependencyIssueKind.SOURCE_CONFLICT,
                                "Skill 依赖 " + id + " 来源 " + selection.entry.sourceId +
                                    " 不满足 " + sourceId,
                            )
                        }
                        val checksum = use.spec.archiveSha256
                        if (checksum != null && selection.entry.archiveSha256 != null &&
                            selection.entry.archiveSha256 != checksum
                        ) {
                            issue(
                                id,
                                SkillDependencyIssueKind.CHECKSUM_CONFLICT,
                                "Skill 依赖 " + id + " 归档摘要与声明不一致",
                            )
                        }
                        if (use.spec.versionConstraint == null) return@forEach
                        if (version == null) {
                            issue(
                                id,
                                SkillDependencyIssueKind.VERSION_UNKNOWN,
                                "Skill 依赖 " + id + " 缺少可验证版本，无法确认满足 " +
                                    use.spec.versionConstraint,
                            )
                            return@forEach
                        }
                        if (!entryMatches(use.spec, selection.entry)) {
                            issue(
                                id,
                                SkillDependencyIssueKind.VERSION_CONFLICT,
                                "Skill 依赖 " + id + " 的版本 " + selection.entry.version +
                                    " 不满足 " + use.spec.versionConstraint,
                            )
                        }
                    }
                }
            }
        }

        private fun issue(id: String, kind: SkillDependencyIssueKind, message: String) {
            val key = id + "\u0000" + kind.name + "\u0000" + message
            issues.putIfAbsent(key, SkillDependencyIssue(id, kind, message))
        }
    }

    private sealed interface Selection<out T> {
        data class Installed(val state: SkillDependencyInstalledState) : Selection<Nothing>
        data class Catalog<T>(val entry: SkillDependencyCatalogEntry<T>) : Selection<T>
    }

    private data class ConstraintUse(
        val spec: SkillDependencySpec,
        val ownerId: String,
    )

    private fun entryMatches(spec: SkillDependencySpec, entry: SkillDependencyCatalogEntry<T>): Boolean {
        if (spec.sourceId != null && entry.sourceId != spec.sourceId) return false
        if (spec.archiveSha256 != null &&
            entry.archiveSha256 != null &&
            entry.archiveSha256 != spec.archiveSha256
        ) {
            return false
        }
        val raw = spec.versionConstraint ?: return true
        val version = SkillVersionNumber.parse(entry.version) ?: return false
        return SkillVersionConstraint.parse(raw)?.accepts(version) == true
    }

    private fun trustRank(trust: String): Int = when (trust) {
        "official" -> 0
        "curated" -> 1
        "community" -> 2
        else -> 3
    }

    private companion object {
        val CONFLICT_KINDS = setOf(
            SkillDependencyIssueKind.INVALID_DECLARATION,
            SkillDependencyIssueKind.VERSION_CONFLICT,
            SkillDependencyIssueKind.SOURCE_CONFLICT,
            SkillDependencyIssueKind.CHECKSUM_CONFLICT,
            SkillDependencyIssueKind.ARCHIVE_UNAVAILABLE,
            SkillDependencyIssueKind.ARCHIVE_CHECKSUM_MISMATCH,
            SkillDependencyIssueKind.CYCLE,
            SkillDependencyIssueKind.LIMIT,
        )
    }
}
