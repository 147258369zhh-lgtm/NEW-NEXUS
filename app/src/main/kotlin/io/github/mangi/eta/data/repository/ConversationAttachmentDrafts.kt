package io.github.mangi.eta.data.repository

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Base64
import io.github.mangi.eta.data.model.ConversationSubject
import io.github.zhh_lgtm.nexus.kernel.api.AgentFileReference
import io.github.zhh_lgtm.nexus.kernel.api.FileReferenceKind
import io.github.zhh_lgtm.nexus.kernel.api.PendingFileReferenceDraft
import io.github.zhh_lgtm.nexus.kernel.api.PendingImageDraft
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.json.JSONArray
import org.json.JSONObject

internal data class ConversationAttachments(
    val images: List<PendingImageDraft> = emptyList(),
    val files: List<PendingFileReferenceDraft> = emptyList(),
) {
    val isEmpty get() = images.isEmpty() && files.isEmpty()
}

/** Serialized IO, atomic metadata and owned image bytes. No picker permission is needed after save. */
internal class ConversationAttachmentDrafts(
    private val context: Context,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    internal class Lease(val subject: ConversationSubject, val epoch: Long, val generation: Long)
    private val lock = Any()
    private val epochs = mutableMapOf<ConversationSubject, Long>()
    private var generation = 0L
    private val pending = mutableMapOf<ConversationSubject, ConversationAttachments>()
    private val failures = mutableMapOf<ConversationSubject, Throwable>()
    private class Command(val run: suspend () -> Unit, val fail: (Throwable) -> Unit)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val worker = scope.launch(Dispatchers.IO) { for (command in commands) command.run() }
    init {
        worker.invokeOnCompletion { failure ->
            commands.close(failure)
            while (true) {
                val command = commands.tryReceive().getOrNull() ?: break
                command.fail(failure ?: IllegalStateException("Attachment draft store closed"))
            }
        }
    }
    private val directory get() = File(context.filesDir, "conversation_attachment_drafts")
    // These bytes may also be referenced by an accepted message. Clearing drafts must not delete them.
    private val blobs get() = File(context.filesDir, "conversation_attachment_images")

    fun open(subject: ConversationSubject): Lease = synchronized(lock) { Lease(subject, epochs[subject] ?: 0, generation) }
    fun isValid(lease: Lease): Boolean = synchronized(lock) {
        lease.generation == generation && lease.epoch == (epochs[lease.subject] ?: 0)
    }
    private fun file(subject: ConversationSubject) = AtomicFile(File(directory, digest(subject.key) + ".json"))

    private fun <T> enqueue(block: suspend () -> T): Deferred<T> {
        val result = CompletableDeferred<T>()
        if (commands.trySend(Command({ result.completeWith(runCatching { block() }) }, { result.completeExceptionally(it) })).isFailure)
            result.completeExceptionally(IllegalStateException("Attachment draft store closed"))
        return result
    }

    suspend fun load(lease: Lease): ConversationAttachments = enqueue {
        if (!isValid(lease)) return@enqueue ConversationAttachments()
        synchronized(lock) { pending[lease.subject] } ?: run {
            val source = file(lease.subject)
            if (!source.baseFile.exists() && !File(source.baseFile.path + ".bak").exists()) ConversationAttachments()
            else decode(source.openRead().bufferedReader().use { it.readText() })
        }
    }.await()

    fun save(lease: Lease, value: ConversationAttachments): Deferred<Unit> = synchronized(lock) {
        if (!isValid(lease)) return@synchronized CompletableDeferred(Unit)
        val frozen = value.copy(images = value.images.toList(), files = value.files.toList())
        pending[lease.subject] = frozen
        enqueue {
            try {
                // Commands admitted before a delete/import finish before its queued clear.
                write(lease.subject, frozen)
                synchronized(lock) {
                    if (pending[lease.subject] === frozen) pending.remove(lease.subject)
                    failures.remove(lease.subject)
                }
                Unit
            } catch (failure: Exception) {
                synchronized(lock) { failures[lease.subject] = failure }
                throw failure
            }
        }
    }

    fun remove(subject: ConversationSubject): Deferred<Unit> = synchronized(lock) {
        epochs[subject] = (epochs[subject] ?: 0) + 1
        pending.remove(subject)
        enqueue { file(subject).delete(); synchronized(lock) { failures.remove(subject) }; Unit }
    }

    fun clearAll(): Deferred<Unit> = synchronized(lock) {
        generation++
        epochs.clear()
        pending.clear()
        enqueue {
            directory.deleteRecursively()
            synchronized(lock) { failures.clear() }
        }
    }

    suspend fun flush() { enqueue { synchronized(lock) { failures.values.firstOrNull() }?.let { throw it } }.await() }

    /** Own bytes before a meeting submission so history/backup do not depend on data URLs or grants. */
    suspend fun materialize(value: ConversationAttachments): ConversationAttachments = enqueue { owned(value) }.await()

    private fun owned(value: ConversationAttachments): ConversationAttachments {
        require(value.images.size <= 24 && value.files.size <= 64) { "Too many draft attachments" }
        return value.copy(images = value.images.map { image ->
            image.copy(uri = ownImage(image.uri), dataUrl = ownImage(image.dataUrl))
        })
    }

    private fun ownImage(reference: String): String {
        val bytes = when {
            reference.startsWith("data:image/", true) -> {
                val encoded = reference.substringAfter("base64,", "")
                require(encoded.isNotEmpty() && encoded.length <= 48 * 1024 * 1024) { "Invalid attachment image" }
                Base64.decode(encoded, Base64.DEFAULT)
            }
            reference.startsWith("file:") -> File(Uri.parse(reference).path ?: "").takeIf(File::isFile)?.readBytes()
            else -> null
        } ?: return reference
        require(bytes.isNotEmpty() && bytes.size <= 32 * 1024 * 1024) { "Attachment image too large" }
        check(blobs.isDirectory || blobs.mkdirs()) { "Cannot create attachment storage" }
        val target = AtomicFile(File(blobs, digestBytes(bytes) + ".image"))
        if (!target.baseFile.isFile) atomicWrite(target, bytes)
        return Uri.fromFile(target.baseFile).toString()
    }

    private fun write(subject: ConversationSubject, value: ConversationAttachments) {
        if (value.isEmpty) { file(subject).delete(); return }
        val stable = owned(value)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create draft storage" }
        atomicWrite(file(subject), encode(stable).toByteArray(Charsets.UTF_8))
    }

    private fun atomicWrite(target: AtomicFile, bytes: ByteArray) {
        val output = target.startWrite()
        try { output.write(bytes); target.finishWrite(output) }
        catch (failure: Throwable) { target.failWrite(output); throw failure }
    }

    internal fun encode(value: ConversationAttachments): String = JSONObject().put("version", 1)
        .put("images", JSONArray().apply { value.images.forEach { image -> put(JSONObject()
            .put("id", image.id).put("uri", portable(image.uri)).put("preview", portable(image.dataUrl)).put("mime", image.mimeType)) } })
        .put("files", JSONArray().apply { value.files.forEach { file -> put(JSONObject()
            .put("id", file.id).put("name", file.reference.displayName).put("path", portable(file.reference.absolutePath))
            .put("kind", file.reference.kind.name)) } }).toString()

    internal fun decode(encoded: String): ConversationAttachments {
        val root = JSONObject(encoded)
        require(root.getInt("version") == 1) { "Unsupported attachment draft version" }
        val images = root.getJSONArray("images")
        val files = root.getJSONArray("files")
        require(images.length() <= 24 && files.length() <= 64)
        return ConversationAttachments(
            List(images.length()) { index -> images.getJSONObject(index).let {
                PendingImageDraft(it.getString("id"), local(it.getString("uri")), local(it.getString("preview")), it.getString("mime"))
            } },
            List(files.length()) { index -> files.getJSONObject(index).let {
                PendingFileReferenceDraft(it.getString("id"), AgentFileReference(it.getString("name"), local(it.getString("path")),
                    FileReferenceKind.valueOf(it.getString("kind"))))
            } },
        )
    }

    private fun portable(reference: String): String {
        val path = if (reference.startsWith("file:")) Uri.parse(reference).path.orEmpty() else reference
        val prefix = context.filesDir.absolutePath + "/"
        return if (path.startsWith(prefix)) (if (reference.startsWith("file:")) "eta-file:" else "eta-path:") + path.removePrefix(prefix) else reference
    }
    private fun local(reference: String): String {
        val isUri = reference.startsWith("eta-file:")
        if (!isUri && !reference.startsWith("eta-path:")) return reference
        val relative = reference.substringAfter(':')
        val target = File(context.filesDir, relative).canonicalFile
        require(target.path.startsWith(context.filesDir.canonicalPath + File.separator)) { "Invalid attachment path" }
        return if (isUri) Uri.fromFile(target).toString() else target.path
    }

    internal fun close() { commands.close(); worker.cancel() }

    companion object {
        private fun digest(value: String) = digestBytes(value.toByteArray(Charsets.UTF_8))
        private fun digestBytes(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        @Volatile private var instance: ConversationAttachmentDrafts? = null
        fun get(context: Context): ConversationAttachmentDrafts = synchronized(this) {
            val app = context.applicationContext
            instance?.takeIf { it.context === app } ?: ConversationAttachmentDrafts(app).also { instance?.close(); instance = it }
        }
    }
}
