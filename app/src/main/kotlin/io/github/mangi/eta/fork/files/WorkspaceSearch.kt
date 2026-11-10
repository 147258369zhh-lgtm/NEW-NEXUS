package io.github.mangi.eta.fork.files

import io.github.mangi.eta.agent.terminal.FileToolBackend
import io.github.mangi.eta.agent.terminal.FileToolKind
import io.github.mangi.eta.agent.terminal.FileToolStat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CancellationException

internal data class WorkspaceSearchHit(
    val path: String,
    val directory: Boolean,
    val size: Long,
    val snippet: String? = null,
    val line: Int? = null,
)

internal data class WorkspaceSearchResult(
    val hits: List<WorkspaceSearchHit>,
    val visited: Int,
    val skipped: Int,
    val limited: Boolean,
)

internal data class WorkspaceSearchLimits(
    val maxEntries: Int = 10_000,
    val maxResults: Int = 200,
    val maxFileBytes: Int = 1024 * 1024,
    val maxTotalBytes: Long = 16L * 1024 * 1024,
    val maxDurationMillis: Long = 10_000,
) {
    init {
        require(maxEntries > 0 && maxResults > 0 && maxFileBytes > 0 && maxTotalBytes > 0 && maxDurationMillis > 0)
    }
}

/** Bounded, read-only traversal of the existing workspace backend, including Root-owned files.
 * Links are excluded rather than followed. A partial search must never look like an exhaustive one.
 */
internal class WorkspaceSearch(
    workspace: File,
    private val backend: FileToolBackend,
    private val limits: WorkspaceSearchLimits = WorkspaceSearchLimits(),
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val root = workspace.canonicalFile.toPath()

    fun search(
        directory: String,
        query: String,
        includeContents: Boolean = false,
        checkCancelled: () -> Unit = {},
    ): WorkspaceSearchResult {
        require(query.length <= 512) { "WORKSPACE_FILE_SEARCH_TOO_LONG" }
        val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotBlank)
        require(terms.isNotEmpty()) { "WORKSPACE_FILE_SEARCH_EMPTY" }
        val start = nanoTime()
        var visited = 0
        var skipped = 0
        var readBytes = 0L
        var limited = false
        val hits = mutableListOf<WorkspaceSearchHit>()
        val queue = ArrayDeque<String>()
        val seen = hashSetOf<String>()

        fun check() {
            checkCancelled()
            if (Thread.currentThread().isInterrupted) throw CancellationException("Workspace search cancelled")
            backend.checkActive()
            if ((nanoTime() - start) / 1_000_000 >= limits.maxDurationMillis) throw BudgetReached()
        }

        fun confined(path: String): String {
            val resolved = File(backend.resolve(path)).canonicalFile.toPath()
            require(resolved.startsWith(root)) { "WORKSPACE_PATH_OUTSIDE_ROOT" }
            return resolved.toString()
        }

        fun content(stat: FileToolStat, path: String): String? {
            if (stat.sizeBytes > limits.maxFileBytes) { skipped++; return null }
            if (stat.sizeBytes > limits.maxTotalBytes - readBytes) throw BudgetReached()
            val bytes = ByteArrayOutputStream(stat.sizeBytes.toInt())
            while (bytes.size().toLong() < stat.sizeBytes) {
                check()
                // Re-resolve before every read so a replaced link cannot escape the root.
                require(confined(path) == path) { "WORKSPACE_FILE_CHANGED" }
                val count = minOf(16 * 1024L, stat.sizeBytes - bytes.size()).toInt()
                val chunk = backend.readBytes(path, bytes.size().toLong(), count)
                check(chunk.isNotEmpty() && chunk.size <= count) { "WORKSPACE_FILE_CHANGED" }
                readBytes += chunk.size
                bytes.write(chunk)
            }
            val after = backend.stat(path)
            check(after.kind == FileToolKind.FILE && after.revision == stat.revision) { "WORKSPACE_FILE_CHANGED" }
            val raw = bytes.toByteArray()
            if (raw.any { it == 0.toByte() } || raw.count {
                val value = it.toInt() and 0xff
                value < 0x20 && value !in setOf(9, 10, 13)
            } > (raw.size / 10).coerceAtLeast(1)) { skipped++; return null }
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(raw)).toString()
            } catch (_: CharacterCodingException) { skipped++; null }
        }

        val initial = confined(directory)
        require(backend.stat(initial).kind == FileToolKind.DIRECTORY) { "WORKSPACE_NOT_DIRECTORY" }
        queue.add(initial)
        try {
            while (queue.isNotEmpty()) {
                check()
                val parent = queue.removeFirst()
                if (!seen.add(parent)) continue
                var offset = 0
                var revision: String? = null
                try {
                    do {
                        check()
                        require(confined(parent) == parent) { "WORKSPACE_FILE_CHANGED" }
                        val page = backend.list(parent, offset, 200, revision)
                        revision = page.revision
                        for (entry in page.entries) {
                            check()
                            if (visited >= limits.maxEntries) throw BudgetReached()
                            visited++
                            if (entry.kind !in setOf(FileToolKind.FILE, FileToolKind.DIRECTORY)) { skipped++; continue }
                            try {
                                // A backend page may only enumerate direct children of its directory.
                                require(File(entry.path).absoluteFile.toPath().normalize().parent == File(parent).toPath()) {
                                    "WORKSPACE_PATH_OUTSIDE_ROOT"
                                }
                                val path = confined(entry.path)
                                require(path == File(entry.path).absolutePath) { "WORKSPACE_LINK_UNSUPPORTED" }
                                val stat = backend.stat(path)
                                check(stat.kind == entry.kind) { "WORKSPACE_FILE_CHANGED" }
                                val relative = root.relativize(File(path).toPath()).toString().replace(File.separatorChar, '/')
                                if (stat.kind == FileToolKind.DIRECTORY) queue.add(path)
                                val name = relative.lowercase(Locale.ROOT)
                                val missing = terms.filterNot(name::contains)
                                var snippet: String? = null
                                var line: Int? = null
                                if (missing.isNotEmpty()) {
                                    if (!includeContents || stat.kind != FileToolKind.FILE) continue
                                    val text = content(stat, path) ?: continue
                                    val searchable = text.lowercase(Locale.ROOT)
                                    if (!missing.all(searchable::contains)) continue
                                    // Find the snippet in the original string so Unicode case expansion
                                    // cannot shift its character offsets.
                                    val match = missing.minOf { text.indexOf(it, ignoreCase = true).coerceAtLeast(0) }
                                    line = 1 + text.take(match).count { it == '\n' }
                                    val lineStart = if (match == 0) 0 else text.lastIndexOf('\n', match - 1).let { if (it < 0) 0 else it + 1 }
                                    val from = maxOf(lineStart, match - 60)
                                    val end = text.indexOf('\n', match).let { if (it < 0) text.length else it }
                                    snippet = text.substring(from, minOf(end, from + 180)).trim().replace('\t', ' ')
                                }
                                if (hits.size >= limits.maxResults) throw BudgetReached()
                                hits += WorkspaceSearchHit(relative, stat.kind == FileToolKind.DIRECTORY, stat.sizeBytes, snippet, line)
                            } catch (cancelled: CancellationException) { throw cancelled
                            } catch (interrupted: InterruptedException) { throw interrupted
                            } catch (budget: BudgetReached) { throw budget
                            } catch (_: Exception) { skipped++ }
                        }
                        if (!page.hasMore) break
                        check(page.nextOffset > offset) { "WORKSPACE_INVALID_PAGE" }
                        offset = page.nextOffset
                    } while (true)
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (interrupted: InterruptedException) { throw interrupted
                } catch (budget: BudgetReached) { throw budget
                } catch (_: Exception) { skipped++ }
            }
        } catch (_: BudgetReached) { limited = true }
        return WorkspaceSearchResult(hits, visited, skipped, limited)
    }

    private class BudgetReached : RuntimeException()
}
