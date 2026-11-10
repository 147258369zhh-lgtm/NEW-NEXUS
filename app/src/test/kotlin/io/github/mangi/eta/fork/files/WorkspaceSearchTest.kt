package io.github.mangi.eta.fork.files

import io.github.mangi.eta.agent.terminal.FileToolBackend
import io.github.mangi.eta.agent.terminal.FileToolDirectoryPage
import io.github.mangi.eta.agent.terminal.FileToolKind
import io.github.mangi.eta.agent.terminal.FileToolStat
import io.github.mangi.eta.agent.terminal.LocalFileToolBackend
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceSearchTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root get() = temporary.root
    private fun file(path: String, text: String = "") = File(root, path).apply { parentFile!!.mkdirs(); writeText(text) }
    private fun searcher(backend: FileToolBackend = LocalFileToolBackend(root), limits: WorkspaceSearchLimits = WorkspaceSearchLimits()) =
        WorkspaceSearch(root, backend, limits)

    @Test fun searchesNestedNamesAndPathsWithoutReadingContents() {
        file("docs/deep/Report.TXT", "正文")
        file("other/report.txt")
        val backend = object : FileToolBackend by LocalFileToolBackend(root) {
            override fun readBytes(path: String, offset: Long, limit: Int): ByteArray = error("Name search must not read files")
        }
        val result = searcher(backend).search(root.path, "DOCS report")
        assertEquals(listOf("docs/deep/Report.TXT"), result.hits.map { it.path })
        assertFalse(result.limited)
        assertEquals(0, result.skipped)
    }

    @Test fun scopeIsTheSelectedDirectory() {
        file("one/report.txt")
        file("two/report.txt")
        val result = searcher().search(File(root, "one").path, "report")
        assertEquals(listOf("one/report.txt"), result.hits.map { it.path })
    }

    @Test fun combinesPathTermsAndUtf8ContentsWithLineAndSnippet() {
        file("notes/deep/result.txt", "第一行\n预算已确认，开始开发\n最后一行")
        file("other.txt", "预算")
        val result = searcher().search(root.path, "NOTES 预算", includeContents = true)
        val hit = result.hits.single()
        assertEquals("notes/deep/result.txt", hit.path)
        assertEquals(2, hit.line)
        assertEquals("预算已确认，开始开发", hit.snippet)
    }

    @Test fun ignoresBinaryMalformedAndOversizedTextButKeepsNameMatches() {
        file("binary.bin").writeBytes(byteArrayOf(0, 1, 2))
        file("malformed.txt").writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
        file("large.txt", "x".repeat(101))
        file("needle-name.bin").writeBytes(byteArrayOf(0))
        val result = searcher(limits = WorkspaceSearchLimits(maxFileBytes = 100)).search(root.path, "needle", true)
        assertEquals(listOf("needle-name.bin"), result.hits.map { it.path })
        assertEquals(3, result.skipped)
        assertFalse(result.limited)
    }

    @Test fun resultAndTraversalBudgetsReportPartialResults() {
        repeat(4) { file("match-$it.txt") }
        val matches = searcher(limits = WorkspaceSearchLimits(maxResults = 2)).search(root.path, "match")
        assertEquals(2, matches.hits.size)
        assertTrue(matches.limited)
        val entries = searcher(limits = WorkspaceSearchLimits(maxEntries = 2)).search(root.path, "absent")
        assertEquals(2, entries.visited)
        assertTrue(entries.limited)
    }

    @Test fun totalReadBudgetStopsBeforeAnUnboundedScan() {
        repeat(3) { file("file-$it.txt", "a".repeat(80)) }
        val result = searcher(limits = WorkspaceSearchLimits(maxTotalBytes = 100)).search(root.path, "absent", true)
        assertTrue(result.limited)
        assertTrue(result.hits.isEmpty())
    }

    @Test fun elapsedTimeBudgetIsExplicit() {
        var ticks = 0L
        val search = WorkspaceSearch(root, LocalFileToolBackend(root), WorkspaceSearchLimits(maxDurationMillis = 1)) {
            (ticks++) * 1_000_000
        }
        assertTrue(search.search(root.path, "absent").limited)
    }

    @Test fun paginatesLargeDirectories() {
        repeat(205) { file("file-$it.txt") }
        val result = searcher().search(root.path, "file-204")
        assertEquals(205, result.visited)
        assertEquals(listOf("file-204.txt"), result.hits.map { it.path })
    }

    @Test fun unreadableSubtreeDoesNotDiscardOtherResults() {
        file("broken/file.txt")
        file("working/file.txt")
        val local = LocalFileToolBackend(root)
        val backend = object : FileToolBackend by local {
            override fun list(path: String, offset: Int, limit: Int, expectedRevision: String?): FileToolDirectoryPage {
                if (File(path).name == "broken") throw IOException("denied")
                return local.list(path, offset, limit, expectedRevision)
            }
        }
        val result = searcher(backend).search(root.path, "file")
        assertEquals(listOf("working/file.txt"), result.hits.map { it.path })
        assertEquals(1, result.skipped)
    }

    @Test fun skipsLinksAndRejectsOutOfRootBackendEntriesBeforeReading() {
        var reads = 0
        val outside = File(root.parentFile, "outside.txt").absolutePath
        val backend = object : FileToolBackend by LocalFileToolBackend(root) {
            override fun list(path: String, offset: Int, limit: Int, expectedRevision: String?) = FileToolDirectoryPage(
                listOf(
                    FileToolStat(File(root, "link").path, "link", FileToolKind.SYMLINK, 1, "1"),
                    FileToolStat(outside, "outside.txt", FileToolKind.FILE, 1, "1"),
                ), 2, false, "1",
            )
            override fun readBytes(path: String, offset: Long, limit: Int): ByteArray { reads++; error("Outside root") }
        }
        val result = searcher(backend).search(root.path, "secret", true)
        assertTrue(result.hits.isEmpty())
        assertEquals(2, result.skipped)
        assertEquals(0, reads)
    }

    @Test fun changingFileDoesNotPublishStaleContentMatches() {
        file("file.txt", "needle")
        val local = LocalFileToolBackend(root)
        val backend = object : FileToolBackend by local {
            var changed = false
            override fun readBytes(path: String, offset: Long, limit: Int) = local.readBytes(path, offset, limit).also { changed = true }
            override fun stat(path: String) = local.stat(path).let { if (changed) it.copy(revision = "changed") else it }
        }
        val result = searcher(backend).search(root.path, "needle", true)
        assertTrue(result.hits.isEmpty())
        assertEquals(1, result.skipped)
    }

    @Test fun cancellationPropagatesInsteadOfReturningEmptySuccess() {
        file("deep/file.txt", "needle")
        var checks = 0
        assertThrows(CancellationException::class.java) {
            searcher().search(root.path, "needle", true) {
                if (++checks >= 4) throw CancellationException("cancelled")
            }
        }
    }

    @Test fun callerCannotSearchOutsideWorkspace() {
        assertThrows(IllegalArgumentException::class.java) { searcher().search(root.parent!!, "needle") }
    }

    @Test fun interruptedBackendReadPropagatesWithoutReportingASkippedFile() {
        file("file.txt", "needle")
        val backend = object : FileToolBackend by LocalFileToolBackend(root) {
            override fun readBytes(path: String, offset: Long, limit: Int): ByteArray = throw InterruptedException("cancelled")
        }
        assertThrows(InterruptedException::class.java) { searcher(backend).search(root.path, "needle", true) }
    }

    @Test fun interruptedDirectoryListingPropagatesWithoutReportingPartialSuccess() {
        val backend = object : FileToolBackend by LocalFileToolBackend(root) {
            override fun list(path: String, offset: Int, limit: Int, expectedRevision: String?): FileToolDirectoryPage =
                throw InterruptedException("cancelled")
        }
        assertThrows(InterruptedException::class.java) { searcher(backend).search(root.path, "needle", true) }
    }
}
