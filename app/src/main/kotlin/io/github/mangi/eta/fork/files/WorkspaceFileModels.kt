package io.github.mangi.eta.fork.files

import java.util.Locale

/** The logical origin of a file. Provider IDs distinguish concrete roots within a source. */
internal enum class WorkspaceFileSource {
    APP_WORKSPACE,
    CHAT_ARTIFACT,
    MEETING_ARTIFACT,
    LINUX_FILES,
    USER_SHARED,
    EXTERNAL_SAF,
}

internal enum class WorkspaceFileKind {
    FILE,
    DIRECTORY,
}

internal enum class WorkspacePermissionScope {
    APP_PRIVATE,
    CONVERSATION,
    MEETING,
    LINUX_SANDBOX,
    USER_SHARED,
    EXTERNAL_GRANT,
}

internal enum class WorkspaceFileAvailability {
    AVAILABLE,
    MISSING,
    PERMISSION_REQUIRED,
    UNSUPPORTED,
    UNAVAILABLE,
}

@JvmInline
internal value class WorkspaceProviderId(val value: String) {
    init {
        WorkspaceFileSafety.validateProviderId(value)
    }
}

/** Persist this identity across moves; a location-derived identity is used when no record exists. */
@JvmInline
internal value class WorkspaceFileId(val value: String) {
    init {
        require(PATTERN.matches(value)) { "WORKSPACE_FILE_ID_INVALID" }
    }

    private companion object {
        val PATTERN = Regex("file_[0-9a-f]{32}")
    }
}

internal sealed interface WorkspaceRootLocator {
    val identityKey: String

    @ConsistentCopyVisibility
    data class Logical private constructor(val key: String) : WorkspaceRootLocator {
        override val identityKey: String = "logical:$key"

        companion object {
            fun parse(raw: String): Logical = Logical(WorkspaceFileSafety.normalizeRootKey(raw))
        }
    }

    @ConsistentCopyVisibility
    data class LinuxPath private constructor(val path: String) : WorkspaceRootLocator {
        override val identityKey: String = "linux:$path"

        companion object {
            fun parse(raw: String): LinuxPath = LinuxPath(WorkspaceFileSafety.normalizeLinuxPath(raw))
        }
    }

    @ConsistentCopyVisibility
    data class ContentTree private constructor(val uri: String) : WorkspaceRootLocator {
        override val identityKey: String = "content:$uri"

        companion object {
            fun parse(raw: String): ContentTree = ContentTree(WorkspaceFileSafety.normalizeSafUri(raw))
        }
    }
}

internal sealed interface WorkspaceFileLocator {
    val identityKey: String

    @ConsistentCopyVisibility
    data class RootRelative private constructor(val path: String) : WorkspaceFileLocator {
        override val identityKey: String = "relative:$path"

        companion object {
            fun parse(raw: String): RootRelative = RootRelative(WorkspaceFileSafety.normalizeRelativePath(raw))
        }
    }

    @ConsistentCopyVisibility
    data class LinuxPath private constructor(val path: String) : WorkspaceFileLocator {
        override val identityKey: String = "linux:$path"

        companion object {
            fun parse(raw: String): LinuxPath = LinuxPath(WorkspaceFileSafety.normalizeLinuxPath(raw))
        }
    }

    @ConsistentCopyVisibility
    data class SafUri private constructor(val uri: String) : WorkspaceFileLocator {
        override val identityKey: String = "content:$uri"

        companion object {
            fun parse(raw: String): SafUri = SafUri(WorkspaceFileSafety.normalizeSafUri(raw))
        }
    }
}

internal data class WorkspaceFileRoot(
    val providerId: WorkspaceProviderId,
    val source: WorkspaceFileSource,
    val displayName: String,
    val locator: WorkspaceRootLocator,
    val permissionScope: WorkspacePermissionScope,
    val availability: WorkspaceFileAvailability = WorkspaceFileAvailability.AVAILABLE,
) {
    init {
        WorkspaceFileSafety.validateDisplayName(displayName)
        val compatible = when (source) {
            WorkspaceFileSource.EXTERNAL_SAF -> locator is WorkspaceRootLocator.ContentTree
            WorkspaceFileSource.LINUX_FILES -> locator is WorkspaceRootLocator.LinuxPath
            else -> locator is WorkspaceRootLocator.Logical
        }
        require(compatible) { "WORKSPACE_FILE_ROOT_LOCATOR_MISMATCH" }
    }
}

/** A root-independent indexed file. The ID can be retained by a catalog across rename and move. */
@ConsistentCopyVisibility
internal data class WorkspaceFileEntry private constructor(
    val id: WorkspaceFileId,
    val providerId: WorkspaceProviderId,
    val source: WorkspaceFileSource,
    val locator: WorkspaceFileLocator,
    val kind: WorkspaceFileKind,
    val displayName: String,
    val permissionScope: WorkspacePermissionScope,
    val availability: WorkspaceFileAvailability,
    val mimeType: String?,
    val sizeBytes: Long?,
    val modifiedAtEpochMillis: Long?,
    val parentId: WorkspaceFileId?,
    val sourceConversationId: String?,
    val sourceMeetingId: String?,
) {
    init {
        WorkspaceFileSafety.validateDisplayName(displayName)
        WorkspaceFileSafety.validateOptionalIdentifier(sourceConversationId)
        WorkspaceFileSafety.validateOptionalIdentifier(sourceMeetingId)
        WorkspaceFileSafety.validateOptionalMimeType(mimeType)
        require(sizeBytes == null || sizeBytes >= 0L) { "WORKSPACE_FILE_SIZE_INVALID" }
        require(modifiedAtEpochMillis == null || modifiedAtEpochMillis >= 0L) {
            "WORKSPACE_FILE_MODIFIED_TIME_INVALID"
        }
        require(parentId != id) { "WORKSPACE_FILE_PARENT_CYCLE" }
        val compatible = when (source) {
            WorkspaceFileSource.EXTERNAL_SAF -> locator is WorkspaceFileLocator.SafUri
            WorkspaceFileSource.LINUX_FILES -> locator is WorkspaceFileLocator.LinuxPath
            else -> locator is WorkspaceFileLocator.RootRelative
        }
        require(compatible) { "WORKSPACE_FILE_LOCATOR_MISMATCH" }
    }

    companion object {
        fun create(
            providerId: WorkspaceProviderId,
            source: WorkspaceFileSource,
            locator: WorkspaceFileLocator,
            kind: WorkspaceFileKind,
            displayName: String,
            permissionScope: WorkspacePermissionScope,
            availability: WorkspaceFileAvailability = WorkspaceFileAvailability.AVAILABLE,
            mimeType: String? = null,
            sizeBytes: Long? = null,
            modifiedAtEpochMillis: Long? = null,
            parentId: WorkspaceFileId? = null,
            sourceConversationId: String? = null,
            sourceMeetingId: String? = null,
            persistedId: WorkspaceFileId? = null,
        ): WorkspaceFileEntry = WorkspaceFileEntry(
            id = persistedId ?: WorkspaceFileIdentity.fromLocation(providerId, source, locator),
            providerId = providerId,
            source = source,
            locator = locator,
            kind = kind,
            displayName = displayName,
            permissionScope = permissionScope,
            availability = availability,
            mimeType = mimeType,
            sizeBytes = sizeBytes,
            modifiedAtEpochMillis = modifiedAtEpochMillis,
            parentId = parentId,
            sourceConversationId = sourceConversationId,
            sourceMeetingId = sourceMeetingId,
        )
    }
}

internal sealed interface WorkspaceParentFilter {
    data object Any : WorkspaceParentFilter
    data object Root : WorkspaceParentFilter
    data class Direct(val parentId: WorkspaceFileId) : WorkspaceParentFilter
}

internal enum class WorkspaceSortField {
    NAME,
    MODIFIED_TIME,
    SIZE,
    SOURCE,
}

internal enum class WorkspaceSortDirection {
    ASCENDING,
    DESCENDING,
}

internal data class WorkspaceFileSort(
    val field: WorkspaceSortField = WorkspaceSortField.NAME,
    val direction: WorkspaceSortDirection = WorkspaceSortDirection.ASCENDING,
    val directoriesFirst: Boolean = true,
)

internal data class WorkspaceFileQuery(
    val searchText: String = "",
    val sources: Set<WorkspaceFileSource> = emptySet(),
    val kinds: Set<WorkspaceFileKind> = emptySet(),
    val providerIds: Set<WorkspaceProviderId> = emptySet(),
    val availability: Set<WorkspaceFileAvailability> = emptySet(),
    val parent: WorkspaceParentFilter = WorkspaceParentFilter.Any,
    val sourceConversationId: String? = null,
    val sourceMeetingId: String? = null,
    val sort: WorkspaceFileSort = WorkspaceFileSort(),
) {
    init {
        require(searchText.length <= MAX_SEARCH_CHARS) { "WORKSPACE_FILE_SEARCH_TOO_LONG" }
        WorkspaceFileSafety.validateOptionalIdentifier(sourceConversationId)
        WorkspaceFileSafety.validateOptionalIdentifier(sourceMeetingId)
    }

    private companion object {
        const val MAX_SEARCH_CHARS = 512
    }
}

internal interface WorkspaceFileCatalog {
    val roots: List<WorkspaceFileRoot>

    suspend fun list(query: WorkspaceFileQuery = WorkspaceFileQuery()): List<WorkspaceFileEntry>

    suspend fun find(id: WorkspaceFileId): WorkspaceFileEntry?
}

internal class InMemoryWorkspaceFileCatalog(
    override val roots: List<WorkspaceFileRoot>,
    private val entries: List<WorkspaceFileEntry>,
) : WorkspaceFileCatalog {
    private val entriesById = entries.associateBy(WorkspaceFileEntry::id)

    init {
        require(entriesById.size == entries.size) { "WORKSPACE_FILE_ID_DUPLICATE" }
        val providers = roots.associateBy(WorkspaceFileRoot::providerId)
        require(providers.size == roots.size) { "WORKSPACE_FILE_PROVIDER_DUPLICATE" }
        require(entries.all { it.providerId in providers }) { "WORKSPACE_FILE_PROVIDER_UNKNOWN" }
    }

    override suspend fun list(query: WorkspaceFileQuery): List<WorkspaceFileEntry> =
        WorkspaceFileQueryEngine.apply(entries, query)

    override suspend fun find(id: WorkspaceFileId): WorkspaceFileEntry? = entriesById[id]
}

internal object WorkspaceFileQueryEngine {
    fun apply(
        entries: List<WorkspaceFileEntry>,
        query: WorkspaceFileQuery = WorkspaceFileQuery(),
    ): List<WorkspaceFileEntry> {
        val terms = query.searchText.trim()
            .lowercase(Locale.ROOT)
            .split(Regex("\\s+"))
            .filter(String::isNotBlank)
        return entries.asSequence()
            .filter { entry ->
                query.sources.isEmpty() || entry.source in query.sources
            }
            .filter { entry ->
                query.kinds.isEmpty() || entry.kind in query.kinds
            }
            .filter { entry ->
                query.providerIds.isEmpty() || entry.providerId in query.providerIds
            }
            .filter { entry ->
                query.availability.isEmpty() || entry.availability in query.availability
            }
            .filter { entry ->
                when (val parent = query.parent) {
                    WorkspaceParentFilter.Any -> true
                    WorkspaceParentFilter.Root -> entry.parentId == null
                    is WorkspaceParentFilter.Direct -> entry.parentId == parent.parentId
                }
            }
            .filter { entry ->
                query.sourceConversationId == null || entry.sourceConversationId == query.sourceConversationId
            }
            .filter { entry ->
                query.sourceMeetingId == null || entry.sourceMeetingId == query.sourceMeetingId
            }
            .filter { entry ->
                if (terms.isEmpty()) true else {
                    val searchable = buildString {
                        append(entry.displayName)
                        append(' ').append(entry.locator.identityKey)
                        entry.mimeType?.let { append(' ').append(it) }
                        entry.sourceConversationId?.let { append(' ').append(it) }
                        entry.sourceMeetingId?.let { append(' ').append(it) }
                    }.lowercase(Locale.ROOT)
                    terms.all(searchable::contains)
                }
            }
            .sortedWith(comparator(query.sort))
            .toList()
    }

    private fun comparator(sort: WorkspaceFileSort): Comparator<WorkspaceFileEntry> =
        Comparator { left, right ->
            if (sort.directoriesFirst) {
                val directoryOrder = when {
                    left.kind == right.kind -> 0
                    left.kind == WorkspaceFileKind.DIRECTORY -> -1
                    else -> 1
                }
                if (directoryOrder != 0) return@Comparator directoryOrder
            }
            val direction = if (sort.direction == WorkspaceSortDirection.ASCENDING) 1 else -1
            val primary = when (sort.field) {
                WorkspaceSortField.NAME -> left.displayName.lowercase(Locale.ROOT)
                    .compareTo(right.displayName.lowercase(Locale.ROOT)) * direction
                WorkspaceSortField.MODIFIED_TIME -> compareNullableLong(
                    left.modifiedAtEpochMillis,
                    right.modifiedAtEpochMillis,
                    direction,
                )
                WorkspaceSortField.SIZE -> compareNullableLong(left.sizeBytes, right.sizeBytes, direction)
                WorkspaceSortField.SOURCE -> left.source.name.compareTo(right.source.name) * direction
            }
            if (primary != 0) return@Comparator primary
            val displayNameOrder = left.displayName.lowercase(Locale.ROOT)
                .compareTo(right.displayName.lowercase(Locale.ROOT))
            if (displayNameOrder != 0) return@Comparator displayNameOrder
            val providerOrder = left.providerId.value.compareTo(right.providerId.value)
            if (providerOrder != 0) return@Comparator providerOrder
            left.id.value.compareTo(right.id.value)
        }

    private fun compareNullableLong(left: Long?, right: Long?, direction: Int): Int = when {
        left == null && right == null -> 0
        left == null -> 1
        right == null -> -1
        else -> left.compareTo(right) * direction
    }
}
