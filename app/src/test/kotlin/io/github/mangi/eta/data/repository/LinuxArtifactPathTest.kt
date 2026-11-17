package io.github.mangi.eta.data.repository

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinuxArtifactPathTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun mapsOnlyThePrivateWorkspaceMount() {
        val privateRoot = temporaryFolder.newFolder("private")
        val mount = File(privateRoot, "project").apply { mkdirs() }
        val file = File(mount, "report.txt").apply { writeText("report") }
        val resolve = RunArtifactCapture::linuxWorkspaceFile
        assertEquals(file.canonicalFile, resolve("/workspace/report.txt", "/workspace", mount.path, privateRoot))
        assertEquals(file.canonicalFile, resolve("/workspace/sub/../report.txt", "/workspace", mount.path, privateRoot))
        assertNull(resolve("/workspace-other/report.txt", "/workspace", mount.path, privateRoot))
        assertNull(resolve("/workspace/../other.txt", "/workspace", mount.path, privateRoot))
        assertNull(resolve("workspace/report.txt", "/workspace", mount.path, privateRoot))
        assertNull(resolve("/workspace/../../report.txt", "/workspace", mount.path, privateRoot))
        assertNull(resolve("/workspace/dir\\report.txt", "/workspace", mount.path, privateRoot))
        assertNull(resolve("/workspace/C:report.txt", "/workspace", mount.path, privateRoot))
        val external = temporaryFolder.newFolder("external")
        assertNull(resolve("/workspace/report.txt", "/workspace", external.path, privateRoot))
    }
}
