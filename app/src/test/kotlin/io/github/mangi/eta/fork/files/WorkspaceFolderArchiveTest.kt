package io.github.mangi.eta.fork.files

import io.github.mangi.eta.agent.terminal.FileToolBackend
import io.github.mangi.eta.agent.terminal.FileToolDirectoryPage
import io.github.mangi.eta.agent.terminal.FileToolKind
import io.github.mangi.eta.agent.terminal.FileToolStat
import io.github.mangi.eta.agent.terminal.LocalFileToolBackend
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CancellationException
import java.util.zip.ZipInputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceFolderArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = temporary.root
    private fun file(path: String, content: String) = File(root, path).apply { parentFile!!.mkdirs(); writeText(content) }
    private fun archive(backend: FileToolBackend = LocalFileToolBackend(root), limits: WorkspaceArchiveLimits = WorkspaceArchiveLimits()): ByteArray {
        val output = ByteArrayOutputStream()
        WorkspaceFolderArchive(root, backend, limits).write(File(root, "folder").path, output)
        return output.toByteArray()
    }

    @Test fun preservesNestedUnicodeBinaryAndEmptyDirectories() {
        file("folder/中文/报告.txt", "预算报告")
        File(root, "folder/empty").mkdirs()
        val raw = ByteArray(130_000) { (it % 256).toByte() }
        file("folder/raw.bin", "").writeBytes(raw)
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive()), Charsets.UTF_8).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertTrue(entries.containsKey("folder/empty/"))
        assertTrue(entries.containsKey("folder/中文/"))
        assertEquals("预算报告", entries.getValue("folder/中文/报告.txt").toString(Charsets.UTF_8))
        assertArrayEquals(raw, entries.getValue("folder/raw.bin"))
        assertTrue(entries.keys.none { it.startsWith('/') || ".." in it.split('/') })
    }

    @Test fun limitsFailInsteadOfProducingASilentPartialArchive() {
        file("folder/one.txt", "1234")
        file("folder/two.txt", "1234")
        listOf(
            WorkspaceArchiveLimits(maxEntries = 2),
            WorkspaceArchiveLimits(maxFileBytes = 3),
            WorkspaceArchiveLimits(maxTotalBytes = 7),
        ).forEach { limits ->
            val error = assertThrows(IllegalStateException::class.java) { archive(limits = limits) }
            assertEquals("WORKSPACE_ARCHIVE_LIMIT", error.message)
        }
    }

    @Test fun linksAreRejectedWithoutReadingTargets() {
        File(root, "folder").mkdirs()
        var reads = 0
        val backend = object : FileToolBackend by LocalFileToolBackend(root) {
            override fun list(path: String, offset: Int, limit: Int, expectedRevision: String?) = FileToolDirectoryPage(
                listOf(FileToolStat(File(path, "link").path, "link", FileToolKind.SYMLINK, 1, "1")), 1, false, "1",
            )
            override fun readBytes(path: String, offset: Long, limit: Int): ByteArray { reads++; error("Must not read") }
        }
        val error = assertThrows(IllegalArgumentException::class.java) { archive(backend) }
        assertEquals("WORKSPACE_LINK_UNSUPPORTED", error.message)
        assertEquals(0, reads)
    }

    @Test fun backendCannotInjectAnOutsidePath() {
        File(root, "folder").mkdirs()
        val backend = object : FileToolBackend by LocalFileToolBackend(root) {
            override fun list(path: String, offset: Int, limit: Int, expectedRevision: String?) = FileToolDirectoryPage(
                listOf(FileToolStat(File(root.parentFile, "outside").path, "outside", FileToolKind.FILE, 1, "1")), 1, false, "1",
            )
        }
        val error = assertThrows(IllegalArgumentException::class.java) { archive(backend) }
        assertEquals("WORKSPACE_PATH_OUTSIDE_ROOT", error.message)
    }

    @Test fun modifiedFileAbortsTheArchive() {
        file("folder/one.txt", "1234")
        val local = LocalFileToolBackend(root)
        val backend = object : FileToolBackend by local {
            var changed = false
            override fun readBytes(path: String, offset: Long, limit: Int) = local.readBytes(path, offset, limit).also { changed = true }
            override fun stat(path: String) = local.stat(path).let { if (changed && it.kind == FileToolKind.FILE) it.copy(revision = "changed") else it }
        }
        val error = assertThrows(IllegalStateException::class.java) { archive(backend) }
        assertEquals("WORKSPACE_FILE_CHANGED", error.message)
    }

    @Test fun changedDirectoryAbortsBeforePublication() {
        file("folder/one.txt", "1234")
        val local = LocalFileToolBackend(root)
        val backend = object : FileToolBackend by local {
            var changed = false
            override fun readBytes(path: String, offset: Long, limit: Int) = local.readBytes(path, offset, limit).also { changed = true }
            override fun stat(path: String) = local.stat(path).let { if (changed && it.kind == FileToolKind.DIRECTORY) it.copy(revision = "changed") else it }
        }
        val error = assertThrows(IllegalStateException::class.java) { archive(backend) }
        assertEquals("WORKSPACE_FILE_CHANGED", error.message)
    }

    @Test fun cancellationEscapesTraversal() {
        file("folder/one.txt", "1234")
        var calls = 0
        assertThrows(CancellationException::class.java) {
            WorkspaceFolderArchive(root, LocalFileToolBackend(root)).write(File(root, "folder").path, ByteArrayOutputStream()) {
                if (++calls == 3) throw CancellationException("cancelled")
            }
        }
    }

    @Test fun manyChildrenRemainCompleteAcrossPages() {
        repeat(205) { file("folder/file-$it.txt", "$it") }
        var count = 0
        ZipInputStream(ByteArrayInputStream(archive())).use { zip ->
            while (zip.nextEntry != null) count++
        }
        assertEquals(206, count)
    }
}
