package io.github.mangi.eta.fork.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class WorkspaceFileSafetyTest {
    @Test
    fun relativePathsAreCanonicalButCannotEscapeTheirRoot() {
        assertEquals("exports/report.txt", WorkspaceFileSafety.normalizeRelativePath("exports//report.txt/"))
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFileSafety.normalizeRelativePath("exports/../secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFileSafety.normalizeRelativePath("exports\\secret")
        }
    }

    @Test
    fun linuxPathsMustRemainAbsoluteAndCannotContainTraversal() {
        assertEquals("/root/workspace/report.txt", WorkspaceFileSafety.normalizeLinuxPath("/root//workspace/report.txt/"))
        assertEquals("/", WorkspaceFileSafety.normalizeLinuxPath("///"))
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFileSafety.normalizeLinuxPath("root/workspace")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFileSafety.normalizeLinuxPath("/root/../../etc/passwd")
        }
    }

    @Test
    fun safUrisRequireContentGrantShapeAndRejectTraversal() {
        assertEquals(
            "content://provider/tree/primary%3ADownload/document/report.txt",
            WorkspaceFileSafety.normalizeSafUri(
                "CONTENT://provider/tree/primary%3ADownload/document/report.txt",
            ),
        )
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFileSafety.normalizeSafUri("file:///sdcard/report.txt")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFileSafety.normalizeSafUri("content://provider/tree/../secret")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFileSafety.normalizeSafUri("content://user:secret@provider/item/1")
        }
    }

    @Test
    fun locationIdentityIsStableAndScopedByProviderAndSource() {
        val provider = WorkspaceProviderId("chat-local")
        val locator = WorkspaceFileLocator.RootRelative.parse("artifacts/a.txt")
        val first = WorkspaceFileIdentity.fromLocation(provider, WorkspaceFileSource.CHAT_ARTIFACT, locator)
        val second = WorkspaceFileIdentity.fromLocation(provider, WorkspaceFileSource.CHAT_ARTIFACT, locator)
        val otherProvider = WorkspaceFileIdentity.fromLocation(
            WorkspaceProviderId("other"),
            WorkspaceFileSource.CHAT_ARTIFACT,
            locator,
        )
        val otherSource = WorkspaceFileIdentity.fromLocation(
            provider,
            WorkspaceFileSource.APP_WORKSPACE,
            locator,
        )
        assertEquals(first, second)
        assertNotEquals(first, otherProvider)
        assertNotEquals(first, otherSource)
    }

    @Test
    fun sourceAndLocatorMustMatchTheirSecurityModel() {
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFileEntry.create(
                providerId = WorkspaceProviderId("saf"),
                source = WorkspaceFileSource.EXTERNAL_SAF,
                locator = WorkspaceFileLocator.RootRelative.parse("not-a-saf-uri"),
                kind = WorkspaceFileKind.FILE,
                displayName = "report.txt",
                permissionScope = WorkspacePermissionScope.EXTERNAL_GRANT,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            WorkspaceFileRoot(
                providerId = WorkspaceProviderId("linux"),
                source = WorkspaceFileSource.LINUX_FILES,
                displayName = "Linux",
                locator = WorkspaceRootLocator.Logical.parse("wrong-root"),
                permissionScope = WorkspacePermissionScope.LINUX_SANDBOX,
            )
        }
    }
}
