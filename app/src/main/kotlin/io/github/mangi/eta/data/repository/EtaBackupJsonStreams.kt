package io.github.mangi.eta.data.repository

import android.content.Context
import android.util.JsonReader
import android.util.JsonToken
import android.util.JsonWriter
import io.github.mangi.eta.core.AndroidAgentLogger
import io.github.mangi.eta.data.db.ConversationContextCheckpointEntity
import io.github.mangi.eta.data.db.ConversationEntity
import io.github.mangi.eta.data.db.ConversationMessageEntity
import io.github.mangi.eta.data.db.ConversationBranchEntity
import io.github.mangi.eta.data.db.ConversationMessageOriginEntity
import io.github.mangi.eta.data.db.ConversationBranchSetupEntity
import java.io.File
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.math.BigDecimal
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.decodeToSequence

/** 沿用 JSON 备份合同，用磁盘暂存大数组，校验与恢复均逐条解码。 */
@OptIn(ExperimentalSerializationApi::class)
internal object EtaBackupJsonStreams {
    const val MAX_BACKUP_BYTES = 512L * 1024L * 1024L
    const val CONVERSATIONS = "conversations"
    const val MESSAGES = "messages"
    const val CHECKPOINTS = "contextCheckpoints"
    const val BRANCHES = "branches"
    const val ORIGINS = "messageOrigins"
    const val SETUPS = "branchSetups"
    private val recordFields = setOf(CONVERSATIONS, MESSAGES, CHECKPOINTS, BRANCHES, ORIGINS, SETUPS)
    private val knownFields = EtaBackupDocument.serializer().descriptor.let { descriptor ->
        (0 until descriptor.elementsCount).mapTo(mutableSetOf(), descriptor::getElementName)
    }
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

    suspend fun export(
        context: Context,
        output: OutputStream,
        maxBytes: Long = MAX_BACKUP_BYTES,
        onPhase: (String) -> Unit = {},
        write: suspend (DocumentWriter) -> EtaBackupSummary,
    ): EtaBackupSummary {
        onPhase("stage_export")
        val directory = createDirectory(context)
        try {
            val file = File(directory, "export.json")
            val summary = file.outputStream().buffered().use { stream ->
                val writer = DocumentWriter(LimitedOutput(stream, maxBytes))
                val result = write(writer)
                writer.finish()
                result
            }
            onPhase("copy_export")
            currentCoroutineContext().ensureActive()
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.write(buffer, 0, count)
                }
            }
            output.flush()
            return summary
        } finally {
            removeDirectory(directory)
        }
    }

    suspend fun stage(context: Context, input: InputStream, onPhase: (String) -> Unit = {}): StagedDocument {
        val directory = createDirectory(context)
        try {
            val header = File(directory, "header.json")
            val cancellation = currentCoroutineContext()
            val checked = PushbackInputStream(LimitedInput(input, MAX_BACKUP_BYTES, cancellation::ensureActive))
            val first = checked.read()
            if (first < 0) throw EtaBackupException("备份文件为空")
            checked.unread(first)
            JsonReader(checked.reader(Charsets.UTF_8)).use { reader ->
                JsonWriter(header.writer(Charsets.UTF_8).buffered()).use { writer ->
                    if (reader.peek() == JsonToken.END_DOCUMENT) throw EtaBackupException("备份文件为空")
                    reader.beginObject()
                    writer.beginObject()
                    val seen = mutableSetOf<String>()
                    while (reader.hasNext()) {
                        cancellation.ensureActive()
                        val name = reader.nextName()
                        if (!seen.add(name)) throw EtaBackupException("备份中存在重复字段")
                        onPhase(if (name in knownFields) "read_$name" else "read_unknown")
                        when {
                            name in recordFields -> {
                                if (reader.peek() != JsonToken.BEGIN_ARRAY) throw EtaBackupException("备份中的记录列表格式无效")
                                JsonWriter(File(directory, "$name.json").writer(Charsets.UTF_8).buffered()).use { records ->
                                    copyValue(reader, records, 0, cancellation::ensureActive)
                                }
                            }
                            name in knownFields -> {
                                writer.name(name)
                                copyValue(reader, writer, 0, cancellation::ensureActive)
                            }
                            else -> reader.skipValue()
                        }
                    }
                    reader.endObject()
                    writer.endObject()
                    if (reader.peek() != JsonToken.END_DOCUMENT) throw EtaBackupException("备份文件末尾存在额外内容")
                }
            }
            onPhase("read_header")
            val document = header.inputStream().use { json.decodeFromStream<EtaBackupDocument>(it) }
            return StagedDocument(directory, document)
        } catch (failure: Throwable) {
            removeDirectory(directory)
            if (failure is CancellationException || failure is EtaBackupException || failure is Error) throw failure
            throw EtaBackupException("备份文件格式无效", failure)
        }
    }

    class StagedDocument(private val directory: File, val header: EtaBackupDocument) : AutoCloseable {
        suspend fun conversations(action: suspend (ConversationEntity) -> Unit) = records(CONVERSATIONS, action)
        suspend fun messages(action: suspend (ConversationMessageEntity) -> Unit) = records(MESSAGES, action)
        suspend fun checkpoints(action: suspend (ConversationContextCheckpointEntity) -> Unit) = records(CHECKPOINTS, action)
        suspend fun branches(action: suspend (ConversationBranchEntity) -> Unit) = records(BRANCHES, action)
        suspend fun origins(action: suspend (ConversationMessageOriginEntity) -> Unit) = records(ORIGINS, action)
        suspend fun setups(action: suspend (ConversationBranchSetupEntity) -> Unit) = records(SETUPS, action)

        private suspend inline fun <reified T> records(field: String, noinline action: suspend (T) -> Unit) {
            val file = File(directory, "$field.json")
            if (!file.exists()) return
            file.inputStream().buffered().use { input ->
                for (row in json.decodeToSequence<T>(input, DecodeSequenceMode.ARRAY_WRAPPED)) {
                    currentCoroutineContext().ensureActive()
                    action(row)
                }
            }
        }

        override fun close() = removeDirectory(directory)
    }

    class DocumentWriter(private val output: OutputStream) {
        private val writer = JsonWriter(output.writer(Charsets.UTF_8).buffered())

        init { writer.beginObject() }

        fun <T> value(name: String, serializer: SerializationStrategy<T>, value: T) {
            writer.name(name)
            element(json.encodeToJsonElement(serializer, value))
        }

        fun beginArray(name: String) {
            writer.name(name).beginArray()
        }

        fun <T> record(serializer: SerializationStrategy<T>, value: T) {
            element(json.encodeToJsonElement(serializer, value))
        }

        fun endArray() { writer.endArray() }
        fun finish() { writer.endObject(); writer.flush() }

        // Json.encodeToStream allocates an escaped buffer proportional to each String. JsonWriter
        // escapes through a bounded BufferedWriter while JsonElement keeps the original String.
        private fun element(value: JsonElement) {
            when (value) {
                is JsonObject -> {
                    writer.beginObject()
                    value.forEach { (name, item) -> writer.name(name); element(item) }
                    writer.endObject()
                }
                is JsonArray -> {
                    writer.beginArray()
                    value.forEach(::element)
                    writer.endArray()
                }
                JsonNull -> writer.nullValue()
                is JsonPrimitive -> when {
                    value.isString -> writer.value(value.content)
                    value.booleanOrNull != null -> writer.value(requireNotNull(value.booleanOrNull))
                    else -> writer.value(BigDecimal(value.content))
                }
            }
        }
    }

    internal class LimitedInput(
        input: InputStream,
        private val limit: Long,
        private val checkCancelled: () -> Unit = {},
    ) : FilterInputStream(input) {
        private var total = 0L
        override fun read(): Int {
            checkCancelled()
            return `in`.read().also { if (it >= 0) count(1) }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkCancelled()
            return `in`.read(buffer, offset, length).also { if (it > 0) count(it) }
        }
        // 输入流由调用方持有，暂存读取结束不提前关闭调用方资源。
        override fun close() = Unit
        private fun count(bytes: Int) {
            total += bytes
            if (total > limit) throw sizeLimitFailure(limit)
        }
    }

    private class LimitedOutput(output: OutputStream, private val limit: Long) : FilterOutputStream(output) {
        private var total = 0L
        override fun write(value: Int) { count(1); out.write(value) }
        override fun write(buffer: ByteArray, offset: Int, length: Int) { count(length); out.write(buffer, offset, length) }
        private fun count(bytes: Int) {
            total += bytes
            if (total > limit) throw sizeLimitFailure(limit)
        }
    }

    private fun sizeLimitFailure(limit: Long): EtaBackupException {
        val size = if (limit >= 1024 * 1024) "${limit / (1024 * 1024)} MiB" else "$limit 字节"
        return EtaBackupException("备份文件超过 $size 限制")
    }

    private fun copyValue(reader: JsonReader, writer: JsonWriter, depth: Int, checkCancelled: () -> Unit) {
        checkCancelled()
        if (depth > 128) throw EtaBackupException("备份中的 JSON 嵌套过深")
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray(); writer.beginArray()
                while (reader.hasNext()) copyValue(reader, writer, depth + 1, checkCancelled)
                reader.endArray(); writer.endArray()
            }
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject(); writer.beginObject()
                while (reader.hasNext()) {
                    writer.name(reader.nextName())
                    copyValue(reader, writer, depth + 1, checkCancelled)
                }
                reader.endObject(); writer.endObject()
            }
            JsonToken.STRING -> writer.value(reader.nextString())
            JsonToken.NUMBER -> writer.value(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> writer.value(reader.nextBoolean())
            JsonToken.NULL -> { reader.nextNull(); writer.nullValue() }
            else -> throw EtaBackupException("备份文件格式无效")
        }
    }

    private fun createDirectory(context: Context): File =
        File(context.cacheDir, "eta_backup_${UUID.randomUUID()}").apply {
            if (!mkdirs()) throw IOException("Unable to create backup staging directory")
        }

    private fun removeDirectory(directory: File) {
        if (directory.exists() && !directory.deleteRecursively()) {
            AndroidAgentLogger.warn("Agent backup cleanup failed: type=IOException")
        }
    }
}
