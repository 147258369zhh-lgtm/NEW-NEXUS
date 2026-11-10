package io.github.mangi.eta.fork.files

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class WorkspaceFileQueryTest {
    private val chatProvider = WorkspaceProviderId("chat-local")
    private val linuxProvider = WorkspaceProviderId("linux-sandbox")
    private val root = WorkspaceFileRoot(
        providerId = chatProvider,
        source = WorkspaceFileSource.APP_WORKSPACE,
        displayName = "Eta workspace",
        locator = WorkspaceRootLocator.Logical.parse("workspace"),
        permissionScope = WorkspacePermissionScope.APP_PRIVATE,
    )
    private val linuxRoot = WorkspaceFileRoot(
        providerId = linuxProvider,
        source = WorkspaceFileSource.LINUX_FILES,
        displayName = "Linux",
        locator = WorkspaceRootLocator.LinuxPath.parse("/root"),
        permissionScope = WorkspacePermissionScope.LINUX_SANDBOX,
        availability = WorkspaceFileAvailability.PERMISSION_REQUIRED,
    )
    private val zeta = WorkspaceFileEntry.create(
        providerId = chatProvider,
        source = WorkspaceFileSource.CHAT_ARTIFACT,
        locator = WorkspaceFileLocator.RootRelative.parse("exports/Zeta.txt"),
        kind = WorkspaceFileKind.FILE,
        displayName = "Zeta.txt",
        permissionScope = WorkspacePermissionScope.CONVERSATION,
        mimeType = "text/plain",
        sizeBytes = 20,
        modifiedAtEpochMillis = 2_000,
        sourceConversationId = "conversation-1",
    )
    private val alpha = WorkspaceFileEntry.create(
        providerId = chatProvider,
        source = WorkspaceFileSource.MEETING_ARTIFACT,
        locator = WorkspaceFileLocator.RootRelative.parse("meetings/Alpha.md"),
        kind = WorkspaceFileKind.FILE,
        displayName = "Alpha.md",
        permissionScope = WorkspacePermissionScope.MEETING,
        mimeType = "text/markdown",
        sizeBytes = 40,
        modifiedAtEpochMillis = null,
        sourceMeetingId = "meeting-1",
    )
    private val folder = WorkspaceFileEntry.create(
        providerId = chatProvider,
        source = WorkspaceFileSource.APP_WORKSPACE,
        locator = WorkspaceFileLocator.RootRelative.parse("nested"),
        kind = WorkspaceFileKind.DIRECTORY,
        displayName = "nested",
        permissionScope = WorkspacePermissionScope.APP_PRIVATE,
    )
    private val missing = WorkspaceFileEntry.create(
        providerId = linuxProvider,
        source = WorkspaceFileSource.LINUX_FILES,
        locator = WorkspaceFileLocator.LinuxPath.parse("/root/missing.txt"),
        kind = WorkspaceFileKind.FILE,
        displayName = "missing.txt",
        permissionScope = WorkspacePermissionScope.LINUX_SANDBOX,
        availability = WorkspaceFileAvailability.MISSING,
    )
    private val nestedChild = WorkspaceFileEntry.create(
        providerId = chatProvider,
        source = WorkspaceFileSource.APP_WORKSPACE,
        locator = WorkspaceFileLocator.RootRelative.parse("nested/child.txt"),
        kind = WorkspaceFileKind.FILE,
        displayName = "child.txt",
        permissionScope = WorkspacePermissionScope.APP_PRIVATE,
        parentId = folder.id,
    )
    private val catalog = InMemoryWorkspaceFileCatalog(
        roots = listOf(root, linuxRoot),
        entries = listOf(zeta, alpha, folder, missing, nestedChild),
    )

    @Test
    fun queryCombinesSourceScopeSourceIdsAndSearchTerms() = runBlocking {
        val result = catalog.list(
            WorkspaceFileQuery(
                searchText = "conversation-1 text",
                sources = setOf(WorkspaceFileSource.CHAT_ARTIFACT),
                sourceConversationId = "conversation-1",
            ),
        )
        assertEquals(listOf(zeta), result)
    }

    @Test
    fun rootAndDirectParentFiltersSeparateHierarchyLevels() = runBlocking {
        assertEquals(setOf(zeta, alpha, folder, missing), catalog.list(
            WorkspaceFileQuery(parent = WorkspaceParentFilter.Root),
        ).toSet())
        assertEquals(listOf(alpha, missing, zeta), catalog.list(
            WorkspaceFileQuery(
                parent = WorkspaceParentFilter.Root,
                kinds = setOf(WorkspaceFileKind.FILE),
            ),
        ))
        assertEquals(listOf(nestedChild), catalog.list(
            WorkspaceFileQuery(parent = WorkspaceParentFilter.Direct(folder.id)),
        ))
    }

    @Test
    fun directoriesRemainFirstThenNamesSortInRequestedDirection() = runBlocking {
        val ascending = catalog.list(WorkspaceFileQuery(parent = WorkspaceParentFilter.Root))
        assertEquals(listOf(folder, alpha, missing, zeta), ascending)
        val descending = catalog.list(
            WorkspaceFileQuery(
                parent = WorkspaceParentFilter.Root,
                sort = WorkspaceFileSort(direction = WorkspaceSortDirection.DESCENDING),
            ),
        )
        assertEquals(listOf(folder, zeta, missing, alpha), descending)
    }

    @Test
    fun missingMetadataSortsLastEvenWhenDirectionDescending() = runBlocking {
        val result = catalog.list(
            WorkspaceFileQuery(
                kinds = setOf(WorkspaceFileKind.FILE),
                parent = WorkspaceParentFilter.Root,
                sort = WorkspaceFileSort(
                    field = WorkspaceSortField.MODIFIED_TIME,
                    direction = WorkspaceSortDirection.DESCENDING,
                ),
            ),
        )
        assertEquals(listOf(zeta, alpha, missing), result)
    }

    @Test
    fun repositoryExposesRootsAndPersistedIdentityLookup() = runBlocking {
        assertEquals(listOf(root, linuxRoot), catalog.roots)
        assertSame(zeta, catalog.find(zeta.id))
        assertNull(catalog.find(WorkspaceFileId("file_00000000000000000000000000000000")))
    }
}
