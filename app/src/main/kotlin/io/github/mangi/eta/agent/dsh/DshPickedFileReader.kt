package io.github.mangi.eta.agent.dsh

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.provider.OpenableColumns
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

internal data class DshPickedFile(val file: File, val name: String, val bytes: Long) : AutoCloseable {
    override fun close() { file.delete() }
}

/** One native reader remains admitted until the provider and its close operation really exit. */
internal class DshPickedFileReader(context: Context, private val idleMs: Long = 10_000) {
    private val context = context.applicationContext

    suspend fun read(uri: Uri, limitBytes: Long): DshPickedFile = suspendCancellableCoroutine { continuation ->
        require(uri.scheme == "content" && limitBytes in 0..DshProtocol.FILE_BYTES)
        if (!busy.compareAndSet(false, true)) {
            continuation.resumeWith(Result.failure(DshException("DSH_FILE_IO_BUSY", "上一次文件读取尚未结束，请稍后重试")))
            return@suspendCancellableCoroutine
        }
        val signal = CancellationSignal()
        val resource = AtomicReference<Closeable?>()
        val closing = AtomicBoolean(false)
        val closeFinished = AtomicBoolean(false)
        val workFinished = AtomicBoolean(false)
        val stopped = AtomicBoolean(false)
        val settled = AtomicBoolean(false)
        val released = AtomicBoolean(false)
        val progress = AtomicLong(System.nanoTime())
        fun release() { if (workFinished.get() && closeFinished.get() && released.compareAndSet(false, true)) busy.set(false) }
        fun fail(failure: Exception) { if (settled.compareAndSet(false, true)) continuation.resumeWith(Result.failure(failure)) }
        fun stop() {
            stopped.set(true)
            if (!closing.compareAndSet(false, true)) return
            closer.execute {
                try { runCatching { signal.cancel() }; runCatching { resource.getAndSet(null)?.close() } }
                finally { closeFinished.set(true); release() }
            }
        }
        continuation.invokeOnCancellation { settled.set(true); stop() }
        val watchdog = timer.scheduleAtFixedRate({
            if (!workFinished.get() && System.nanoTime() - progress.get() >= TimeUnit.MILLISECONDS.toNanos(idleMs)) {
                stop()
                fail(DshException("DSH_FILE_READ_TIMEOUT", "文件来源长时间没有响应，本次读取已取消"))
            }
        }, idleMs, idleMs.coerceAtMost(1000), TimeUnit.MILLISECONDS)
        reader.execute {
            var temporary: File? = null
            try {
                fun checkActive() { if (stopped.get() || !continuation.isActive) throw java.io.InterruptedIOException("文件读取已取消") }
                fun own(value: Closeable) {
                    if (stopped.get()) { value.close(); checkActive() }
                    resource.set(value)
                    if (stopped.get()) { resource.getAndSet(null)?.close(); checkActive() }
                }
                checkActive()
                var name = "文件"; var declared: Long? = null
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null as Bundle?, signal)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 && !cursor.isNull(it) }?.let { name = displayName(cursor.getString(it)) }
                        cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !cursor.isNull(it) }?.let { declared = cursor.getLong(it).takeIf { value -> value >= 0 } }
                    }
                }
                checkActive(); require(declared == null || declared!! <= limitBytes) { "所选文件超过本次大小限制" }
                progress.set(System.nanoTime())
                val descriptor = requireNotNull(context.contentResolver.openAssetFileDescriptor(uri, "r", signal)) { "无法打开所选文件" }
                own(descriptor)
                require(descriptor.declaredLength < 0 || descriptor.declaredLength <= limitBytes) { "所选文件超过本次大小限制" }
                val input = descriptor.createInputStream()
                own(input)
                progress.set(System.nanoTime())
                val directory = File(context.cacheDir, "dsh-picked-files").apply { check(isDirectory || mkdirs()) }
                directory.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > TimeUnit.MINUTES.toMillis(10) }?.forEach(File::delete)
                val target = File.createTempFile("picked-", ".part", directory).also { temporary = it }
                var bytes = 0L
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        checkActive()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), limitBytes - bytes + 1).toInt())
                        checkActive()
                        if (count < 0) break
                        if (count == 0) continue
                        require(bytes + count <= limitBytes) { "所选文件实际大小超过限制" }
                        output.write(buffer, 0, count); bytes += count; progress.set(System.nanoTime())
                    }
                }
                if (declared != null) require(declared == bytes) { "文件来源返回的数据长度不完整" }
                if (closing.compareAndSet(false, true)) {
                    try { resource.getAndSet(null)?.close() } finally { closeFinished.set(true) }
                }
                checkActive()
                val result = DshPickedFile(target, name, bytes)
                workFinished.set(true); release()
                if (settled.compareAndSet(false, true)) continuation.resume(result) { _, value, _ -> value.close() }
                else result.close()
                temporary = null
            } catch (failure: Exception) { fail(failure) }
            finally {
                temporary?.delete(); watchdog.cancel(false)
                workFinished.set(true)
                stop(); release()
            }
        }
    }

    companion object {
        private val busy = AtomicBoolean(false)
        private val reader = Executors.newSingleThreadExecutor { Thread(it, "dsh-file-reader").apply { isDaemon = true } }
        private val closer = Executors.newSingleThreadExecutor { Thread(it, "dsh-file-close").apply { isDaemon = true } }
        private val timer = Executors.newSingleThreadScheduledExecutor { Thread(it, "dsh-file-timeout").apply { isDaemon = true } }
        internal fun displayName(raw: String): String {
            val value = raw.substringAfterLast('/').substringAfterLast('\\').filterNot(Char::isISOControl).trim().ifBlank { "文件" }
            return value.substring(0, value.offsetByCodePoints(0, value.codePointCount(0, value.length).coerceAtMost(128)))
        }
    }
}
