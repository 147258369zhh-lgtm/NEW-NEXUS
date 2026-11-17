package io.github.mangi.eta.agent.workflow

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

internal data class NotepadEntry(val id: String, val createdAt: Long, val text: String,
    val attachments: List<String> = emptyList(), val draft: String = "", val source: String = "",
    val transcripts: Map<String, String> = emptyMap(), val transcriptsInText: Set<String> = emptySet())
internal data class NotepadSnapshot(val revision: Long, val entries: List<NotepadEntry>)
internal class NotepadRevisionConflict : IllegalStateException("记事本版本已更改")

internal fun NotepadEntry.withEditorChanges(text: String, attachments: List<String>): NotepadEntry = copy(
    text = text,
    attachments = attachments,
    transcripts = transcripts.filterKeys { it in attachments },
    transcriptsInText = transcriptsInText.filter { name ->
        name in attachments && transcripts[name]?.let { it.isNotBlank() && it in text } == true
    }.toSet(),
)

internal class IndependentNotepadStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "independent-notepad").apply { mkdirs() }
    private val file = AtomicFile(File(root, "entries.json"))
    private val resolver = context.applicationContext.contentResolver

    fun read(): NotepadSnapshot = synchronized(lock) { readUnlocked() }
    private fun readUnlocked(): NotepadSnapshot {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return NotepadSnapshot(0, emptyList())
        val json = JSONObject(file.openRead().bufferedReader(Charsets.UTF_8).use { it.readText() })
        require(json.optInt("version") == 1) { "记事本格式不受支持" }
        val entries = json.getJSONArray("entries")
        return NotepadSnapshot(json.getLong("revision"), (0 until entries.length()).map { index ->
            val item = entries.getJSONObject(index)
            val files = item.optJSONArray("attachments") ?: JSONArray()
            val transcripts = item.optJSONObject("transcripts") ?: JSONObject()
            val included = item.optJSONArray("transcripts_in_text") ?: JSONArray()
            NotepadEntry(item.getString("id"), item.getLong("created_at"), item.getString("text"),
                (0 until files.length()).map { files.getString(it) }, item.optString("draft"), item.optString("source"),
                transcripts.keys().asSequence().associateWith { transcripts.getString(it) },
                (0 until included.length()).map { included.getString(it) }.toSet())
        })
    }

    fun save(expected: Long, entries: List<NotepadEntry>): NotepadSnapshot = synchronized(lock) {
        if (readUnlocked().revision != expected) throw NotepadRevisionConflict()
        val normalized = entries.map { entry -> entry.copy(
            transcripts = entry.transcripts.filterKeys { it in entry.attachments },
            transcriptsInText = entry.transcriptsInText.filter { it in entry.attachments && it in entry.transcripts }.toSet(),
        ) }
        val next = NotepadSnapshot(expected + 1, normalized)
        val json = JSONObject().put("version", 1).put("revision", next.revision).put("entries", JSONArray(normalized.map {
            JSONObject().put("id", it.id).put("created_at", it.createdAt).put("text", it.text)
                .put("attachments", JSONArray(it.attachments)).put("draft", it.draft).put("source", it.source)
                .put("transcripts", JSONObject(it.transcripts))
                .put("transcripts_in_text", JSONArray(it.transcriptsInText))
        }))
        val output = file.startWrite()
        try { output.write(json.toString().toByteArray(Charsets.UTF_8)); file.finishWrite(output) }
        catch (error: Throwable) { file.failWrite(output); throw error }
        next
    }

    fun copyAttachment(uri: Uri): String {
        val original = runCatching { resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } }.getOrNull().orEmpty()
        val suffix = attachmentSuffix(original, runCatching { resolver.getType(uri) }.getOrNull())
        val name = "${UUID.randomUUID()}.$suffix"
        val target = File(root, name)
        try { resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法读取附件" }
            target.outputStream().use { output ->
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= 100L * 1024 * 1024) { "附件不能超过 100 MB" }
                    output.write(buffer, 0, count)
                }
            }
        } } catch (error: Throwable) { target.delete(); throw error }
        return name
    }

    fun saveAudio(wav: ByteArray): String {
        val name = "${UUID.randomUUID()}.wav"
        val target = File(root, name)
        try { target.writeBytes(wav) } catch (error: Throwable) { target.delete(); throw error }
        return name
    }
    fun attachment(name: String): File = File(root, name).also {
        require(name.matches(attachmentName)) { "附件路径无效" }
        require(it.canonicalFile.parentFile == root.canonicalFile) { "附件路径无效" }
    }
    fun deleteUnreferenced(names: Collection<String>) = synchronized(lock) {
        val referenced = readUnlocked().entries.flatMap { it.attachments }.toSet()
        names.filterNot { it in referenced }.forEach { attachment(it).delete() }
    }

    fun legacyEntries(): List<NotepadEntry> = File(root.parentFile, "workflow-state").listFiles().orEmpty().mapNotNull { file ->
        runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val note = root.optJSONObject("notepad") ?: return@runCatching null
            val content = note.optString("content").takeIf(String::isNotBlank) ?: return@runCatching null
            val scope = root.optString("scope_id").takeIf(String::isNotBlank) ?: return@runCatching null
            NotepadEntry(UUID.randomUUID().toString(), note.optLong("created_at", file.lastModified()), content,
                source = "legacy:$scope:${note.optLong("revision")}")
        }.getOrNull()
    }
    companion object {
        private val lock = Any()
        private val attachmentName = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.[a-z0-9]{1,10}")
        internal fun attachmentSuffix(name: String, mime: String?): String =
            name.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
                ?: when (mime?.substringBefore(';')?.trim()?.lowercase()) {
                    "image/jpeg" -> "jpg"
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    "audio/wav", "audio/x-wav" -> "wav"
                    "text/plain" -> "txt"
                    "text/markdown" -> "md"
                    "text/csv" -> "csv"
                    "application/json" -> "json"
                    "application/pdf" -> "pdf"
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx"
                    else -> "bin"
                }
    }
}
