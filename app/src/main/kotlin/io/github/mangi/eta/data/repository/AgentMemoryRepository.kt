package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.AtomicFile
import io.github.mangi.eta.data.datastore.SettingsDataStore
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow

internal data class AgentMemorySnapshot(
    val content: String,
    val revision: String,
    val byteSize: Int,
    val lineCount: Int,
)

internal data class AgentMemoryReadResult(
    val snapshot: AgentMemorySnapshot,
    val content: String,
    val startLine: Int?,
    val endLine: Int?,
    val hasMore: Boolean,
    val matchedLines: Int,
)

internal sealed interface AgentMemoryMutation {
    val revision: String

    data class ReplaceRange(
        override val revision: String,
        val startLine: Int,
        val endLine: Int,
        val content: String,
    ) : AgentMemoryMutation

    data class Append(
        override val revision: String,
        val content: String,
    ) : AgentMemoryMutation

    data class Clear(
        override val revision: String,
    ) : AgentMemoryMutation
}

internal sealed interface AgentMemoryWriteResult {
    data class Success(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
    data class Conflict(val snapshot: AgentMemorySnapshot) : AgentMemoryWriteResult
}

internal class AgentMemoryException(
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** 单一 MEMORY.md 的有界、原子文件存储。 */
internal class AgentMemoryStore(
    rootDir: File,
    directoryName: String = DIRECTORY_NAME,
) {
    private val memoryDir = File(rootDir, directoryName)
    private val atomicFile = AtomicFile(File(memoryDir, FILE_NAME))
    private val lock = Any()

    fun snapshot(): AgentMemorySnapshot = synchronized(lock) {
        snapshotLocked()
    }

    fun read(
        query: String? = null,
        startLine: Int = DEFAULT_START_LINE,
        maxChars: Int = DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult = synchronized(lock) {
        val snapshot = snapshotLocked()
        val boundedChars = maxChars.coerceIn(MIN_READ_CHARS, MAX_READ_CHARS)
        if (query.isNullOrBlank()) {
            page(snapshot, startLine, boundedChars)
        } else {
            search(snapshot, query.trim(), boundedChars)
        }
    }

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (mutation.revision != current.revision) {
            return@synchronized AgentMemoryWriteResult.Conflict(current)
        }
        val updated = when (mutation) {
            is AgentMemoryMutation.ReplaceRange -> replaceRange(current, mutation)
            is AgentMemoryMutation.Append -> append(current, mutation.content)
            is AgentMemoryMutation.Clear -> ""
        }
        writeLocked(updated)
        AgentMemoryWriteResult.Success(snapshotOf(updated))
    }

    fun replaceAll(content: String): AgentMemorySnapshot = synchronized(lock) {
        writeLocked(content)
        snapshotOf(content)
    }

    fun replaceAllIfRevision(content: String, revision: String): AgentMemoryWriteResult = synchronized(lock) {
        val current = snapshotLocked()
        if (current.revision != revision) return@synchronized AgentMemoryWriteResult.Conflict(current)
        writeLocked(content)
        AgentMemoryWriteResult.Success(snapshotOf(content))
    }

    private fun snapshotLocked(): AgentMemorySnapshot {
        val file = atomicFile.baseFile
        if (!file.exists()) return snapshotOf("")
        val bytes = try {
            atomicFile.openRead().use { it.readBytes() }
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_READ_FAILED",
                message = "无法读取记忆文件",
                cause = throwable,
            )
        }
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件超过 1 MiB 安全上限",
            )
        }
        val content = bytes.toString(Charsets.UTF_8)
        return snapshotOf(content, bytes)
    }

    private fun writeLocked(content: String) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            throw AgentMemoryException(
                code = "MEMORY_TOO_LARGE",
                message = "记忆文件不能超过 1 MiB UTF-8 字节",
            )
        }
        if (!memoryDir.exists() && !memoryDir.mkdirs() && !memoryDir.isDirectory) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法创建记忆目录",
            )
        }
        val output = try {
            atomicFile.startWrite()
        } catch (throwable: IOException) {
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法开始写入记忆文件",
                cause = throwable,
            )
        }
        try {
            output.write(bytes)
            atomicFile.finishWrite(output)
            ensureCommittedLocked(bytes)
        } catch (throwable: Throwable) {
            atomicFile.failWrite(output)
            throw AgentMemoryException(
                code = "MEMORY_WRITE_FAILED",
                message = "无法保存记忆文件",
                cause = throwable,
            )
        }
    }

    /**
     * AtomicFile 的正常路径在 Android 上完成原子替换。部分 Robolectric/Windows
     * 运行时会把 .new 留在磁盘上却不抛异常，此时必须核对并完成提交，避免一次看似
     * 成功的写入随后读回旧内容，进而破坏 revision 并发保护。
     */
    private fun ensureCommittedLocked(expected: ByteArray) {
        val target = atomicFile.baseFile
        if (target.readBytesOrNull()?.contentEquals(expected) == true) return

        val pending = File(memoryDir, "$FILE_NAME.new")
        if (pending.isFile) {
            val moved = runCatching {
                Files.move(
                    pending.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.isSuccess || runCatching {
                Files.move(
                    pending.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.isSuccess
            if (moved && target.readBytesOrNull()?.contentEquals(expected) == true) return
        }

        // 保留最后的可见性兜底；写入后仍会再次核对，绝不对调用方虚报成功。
        FileOutputStream(target, false).use { direct ->
            direct.write(expected)
            direct.fd.sync()
        }
        if (target.readBytesOrNull()?.contentEquals(expected) != true) {
            throw IOException("AtomicFile did not commit the memory replacement")
        }
    }

    private fun File.readBytesOrNull(): ByteArray? =
        runCatching { if (isFile) readBytes() else null }.getOrNull()

    private fun replaceRange(
        snapshot: AgentMemorySnapshot,
        mutation: AgentMemoryMutation.ReplaceRange,
    ): String {
        val lines = snapshot.content.memoryLines().toMutableList()
        if (
            mutation.startLine < 1 ||
            mutation.endLine < mutation.startLine ||
            mutation.endLine > lines.size
        ) {
            throw AgentMemoryException(
                code = "MEMORY_RANGE_INVALID",
                message = "替换行范围无效；请重新读取记忆后再试",
            )
        }
        val replacement = mutation.content.memoryLines()
        lines.subList(mutation.startLine - 1, mutation.endLine).clear()
        if (replacement.isNotEmpty()) {
            lines.addAll(mutation.startLine - 1, replacement)
        }
        return lines.joinToString("\n")
    }

    private fun append(snapshot: AgentMemorySnapshot, content: String): String {
        if (content.isEmpty()) return snapshot.content
        if (snapshot.content.isEmpty()) return content
        return snapshot.content.trimEnd('\n') + "\n" + content
    }

    private fun page(
        snapshot: AgentMemorySnapshot,
        requestedStartLine: Int,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        if (lines.isEmpty()) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val startIndex = (requestedStartLine - 1).coerceIn(0, lines.size)
        if (startIndex >= lines.size) {
            return AgentMemoryReadResult(snapshot, "", null, null, false, 0)
        }
        val output = StringBuilder()
        var endIndex = startIndex
        while (endIndex < lines.size) {
            val rendered = "${endIndex + 1}: ${lines[endIndex]}"
            val separatorLength = if (output.isEmpty()) 0 else 1
            if (output.isNotEmpty() && output.length + separatorLength + rendered.length > maxChars) break
            if (output.isNotEmpty()) output.append('\n')
            output.append(rendered.take(maxChars - output.length))
            endIndex++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = startIndex + 1,
            endLine = endIndex,
            hasMore = endIndex < lines.size,
            matchedLines = endIndex - startIndex,
        )
    }

    private fun search(
        snapshot: AgentMemorySnapshot,
        query: String,
        maxChars: Int,
    ): AgentMemoryReadResult {
        val lines = snapshot.content.memoryLines()
        val matched = lines.indices.filter { index ->
            lines[index].contains(query, ignoreCase = true)
        }
        val included = linkedSetOf<Int>()
        matched.forEach { index ->
            for (candidate in (index - SEARCH_CONTEXT_LINES)..(index + SEARCH_CONTEXT_LINES)) {
                if (candidate in lines.indices) included += candidate
            }
        }
        val output = StringBuilder()
        var lastIncluded: Int? = null
        var renderedCount = 0
        for (index in included) {
            val rendered = "${index + 1}: ${lines[index]}"
            val gap = when {
                output.isEmpty() -> ""
                lastIncluded != null && index > lastIncluded + 1 -> "\n…\n"
                else -> "\n"
            }
            if (output.isNotEmpty() && output.length + gap.length + rendered.length > maxChars) break
            output.append(gap).append(rendered.take(maxChars - output.length - gap.length))
            lastIncluded = index
            renderedCount++
            if (output.length >= maxChars) break
        }
        return AgentMemoryReadResult(
            snapshot = snapshot,
            content = output.toString(),
            startLine = included.firstOrNull()?.plus(1),
            endLine = lastIncluded?.plus(1),
            hasMore = renderedCount < included.size,
            matchedLines = matched.size,
        )
    }

    private fun snapshotOf(
        content: String,
        bytes: ByteArray = content.toByteArray(Charsets.UTF_8),
    ): AgentMemorySnapshot = AgentMemorySnapshot(
        content = content,
        revision = sha256(bytes),
        byteSize = bytes.size,
        lineCount = content.memoryLines().size,
    )

    private fun String.memoryLines(): List<String> =
        if (isEmpty()) emptyList() else split('\n')

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString(separator = "") { byte ->
                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
            }

    companion object {
        const val MAX_FILE_BYTES = 1024 * 1024
        const val DEFAULT_READ_CHARS = 12_000
        const val MAX_READ_CHARS = 32_000
        const val MIN_READ_CHARS = 1
        const val MAX_WRITE_CONTENT_CHARS = 3_500
        private const val DIRECTORY_NAME = "memory"
        private const val FILE_NAME = "MEMORY.md"
        private const val DEFAULT_START_LINE = 1
        private const val SEARCH_CONTEXT_LINES = 1
    }
}

internal object AgentMemoryRepository {
    @Volatile
    private lateinit var store: AgentMemoryStore

    private val scopedStores = java.util.concurrent.ConcurrentHashMap<String, AgentMemoryStore>()

    fun init(context: Context) {
        if (!::store.isInitialized) {
            val root = context.applicationContext.filesDir
            storeRootRef = root
            store = AgentMemoryStore(root)
        }
    }

    fun snapshot(): AgentMemorySnapshot {
        ensureInitialized()
        return store.snapshot()
    }

    fun snapshot(scope: AgentMemoryScope): AgentMemorySnapshot = scopedStore(scope).snapshot()

    fun read(
        query: String? = null,
        startLine: Int = 1,
        maxChars: Int = AgentMemoryStore.DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult {
        ensureInitialized()
        return store.read(query, startLine, maxChars)
    }

    fun read(
        scope: AgentMemoryScope,
        query: String? = null,
        startLine: Int = 1,
        maxChars: Int = AgentMemoryStore.DEFAULT_READ_CHARS,
    ): AgentMemoryReadResult = scopedStore(scope).read(query, startLine, maxChars)

    fun mutate(mutation: AgentMemoryMutation): AgentMemoryWriteResult {
        ensureInitialized()
        return store.mutate(mutation)
    }

    fun mutate(scope: AgentMemoryScope, mutation: AgentMemoryMutation): AgentMemoryWriteResult =
        scopedStore(scope).mutate(mutation)

    fun replaceAll(content: String): AgentMemorySnapshot {
        ensureInitialized()
        return store.replaceAll(content)
    }

    fun replaceAll(scope: AgentMemoryScope, content: String): AgentMemorySnapshot =
        scopedStore(scope).replaceAll(content)

    fun replaceAllIfRevision(scope: AgentMemoryScope, content: String, revision: String): AgentMemoryWriteResult =
        scopedStore(scope).replaceAllIfRevision(content, revision)

    fun clearScopedScopes() {
        ensureInitialized()
        scopedStores.clear()
        File(storeRoot(), "memory/scopes").deleteRecursively()
    }

    fun listScopes(): List<AgentMemoryScope> {
        ensureInitialized()
        val root = File(storeRoot(), "memory/scopes")
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown().filter { it.isFile && it.name == "MEMORY.md" }.mapNotNull { file ->
            val relative = file.parentFile?.relativeToOrNull(root)?.path?.replace(File.separatorChar, '/') ?: return@mapNotNull null
            val parts = relative.split('/').filter { it.isNotBlank() }
            if (parts.size != 2) return@mapNotNull null
            AgentMemoryScope.fromPath(parts[0], parts[1])
        }.toList()
    }

    fun combinedSnapshot(scopes: List<AgentMemoryScope>): AgentMemorySnapshot {
        ensureInitialized()
        val distinct = scopes.distinctBy { it.key }
        if (distinct.isEmpty()) return store.snapshot()
        val pieces = distinct.mapNotNull { scope ->
            val content = scopedStore(scope).snapshot().content.trim()
            content.takeIf { it.isNotEmpty() }?.let { "<!-- eta-memory-scope:${scope.key} -->\n$it" }
        }
        val content = pieces.joinToString("\n\n")
        val bytes = content.toByteArray(Charsets.UTF_8)
        return AgentMemorySnapshot(content, sha256(bytes), bytes.size, if (content.isEmpty()) 0 else content.split('\n').size)
    }

    fun enabledFlow(): Flow<Boolean> = SettingsDataStore.memoryEnabledFlow()

    suspend fun isEnabled(): Boolean = SettingsDataStore.settings().memoryEnabled

    suspend fun setEnabled(enabled: Boolean) = SettingsDataStore.setMemoryEnabled(enabled)

    private fun ensureInitialized() {
        check(::store.isInitialized) {
            "AgentMemoryRepository.init(context) must be called in Application.onCreate()"
        }
    }

    private fun scopedStore(scope: AgentMemoryScope): AgentMemoryStore {
        ensureInitialized()
        if (scope.type == AgentMemoryScopeType.GLOBAL) return store
        val key = scope.key
        return scopedStores.computeIfAbsent(key) {
            val scopeDirectory = File(storeRoot(), "memory/scopes/${scope.type.path}/${scope.id}")
            AgentMemoryStore(scopeDirectory.parentFile ?: storeRoot(), scopeDirectory.name)
        }
    }

    private fun storeRoot(): File = storeRootRef

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

    @Volatile
    private lateinit var storeRootRef: File
}
