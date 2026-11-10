package io.github.mangi.eta.agent.prompt

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * SAF-facing adapter with a bounded pre-read. Parsing never receives more than the largest
 * supported container, and ordinary text/JSON remains limited to 2 MiB by the codec.
 */
internal object PromptImportGateway {
    fun inspect(openStream: () -> InputStream, sourceName: String? = null): PromptImportResult = try {
        openStream().use { stream ->
            PromptImportCodec.inspect(readBounded(stream), sourceName)
        }
    } catch (failure: PromptImportException) {
        PromptImportResult.Failure(failure.code, failure.message.orEmpty())
    } catch (failure: IOException) {
        PromptImportResult.Failure(
            PromptImportErrorCode.READ_FAILED,
            "读取提示词文件失败：" + (failure.message ?: "I/O 错误"),
        )
    } catch (failure: SecurityException) {
        PromptImportResult.Failure(PromptImportErrorCode.READ_FAILED, "没有读取该文件的权限")
    }

    fun inspectUri(
        resolver: ContentResolver,
        uri: Uri,
        sourceName: String? = null,
    ): PromptImportResult = inspect(
        openStream = {
            resolver.openInputStream(uri)
                ?: throw IOException("无法打开所选文件")
        },
        sourceName = sourceName ?: displayName(resolver, uri),
    )

    fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0) cursor.getString(index) else null
        }
    }.getOrNull()

    internal fun readBounded(stream: InputStream): ByteArray {
        val header = ByteArray(8)
        var headerCount = 0
        while (headerCount < header.size) {
            val count = stream.read(header, headerCount, header.size - headerCount)
            if (count < 0) break
            headerCount += count
        }
        val limit = PromptImportCodec.inputLimitFor(header.copyOf(headerCount))
        val output = ByteArrayOutputStream()
        output.write(header, 0, headerCount)
        var total = headerCount
        if (total > limit) {
            throw PromptImportException(PromptImportErrorCode.INPUT_TOO_LARGE, "提示词文件超过读取上限")
        }
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) {
                throw PromptImportException(PromptImportErrorCode.INPUT_TOO_LARGE, "提示词文件超过读取上限")
            }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
