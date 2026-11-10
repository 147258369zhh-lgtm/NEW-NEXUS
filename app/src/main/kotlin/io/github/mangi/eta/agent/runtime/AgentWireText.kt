package io.github.mangi.eta.agent.runtime

import android.os.Binder
import android.os.Message
import android.os.Messenger
import android.os.Bundle
import android.os.ParcelFileDescriptor
import java.io.File

/** 大文本通过已取消目录链接的只读文件传输；Binder 只携带描述符与长度。 */
internal object AgentWireText {
    private const val INLINE_CHARS = 16_384
    private const val FD_SUFFIX = "_eta_text_fd"
    private const val SIZE_SUFFIX = "_eta_text_bytes"
    private const val CLEANUP_PATH_SUFFIX = "_eta_text_cleanup_path"
    // 单次物化的内存保护，超过时拒绝交付，不截断已落盘的历史。
    private const val MAX_TRANSFER_BYTES = 64L * 1024 * 1024

    fun put(bundle: Bundle, key: String, text: String, directory: File?) {
        if (directory == null || text.length <= INLINE_CHARS) {
            bundle.putString(key, text)
            return
        }
        require(text.length <= MAX_TRANSFER_BYTES) { "历史传输超过单次内存预算，原始记录仍保留" }
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_TRANSFER_BYTES) { "历史传输超过单次内存预算，原始记录仍保留" }
        val file = File.createTempFile("eta-context-", ".json", directory)
        try {
            file.outputStream().use { it.write(bytes) }
            val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            bundle.putParcelable(key + FD_SUFFIX, descriptor)
            bundle.putLong(key + SIZE_SUFFIX, bytes.size.toLong())
        } finally {
            // Android/Linux keeps the descriptor valid after unlinking. Windows test and host
            // filesystems cannot unlink an open file, so retain a narrowly scoped cleanup path.
            if (!file.delete()) bundle.putString(key + CLEANUP_PATH_SUFFIX, file.absolutePath)
        }
    }

    fun hasDescriptor(bundle: Bundle, key: String): Boolean = bundle.containsKey(key + FD_SUFFIX)

    fun read(bundle: Bundle, key: String): String? {
        val descriptor = bundle.getParcelable(key + FD_SUFFIX, ParcelFileDescriptor::class.java)
            ?: return bundle.getString(key)
        val size = bundle.getLong(key + SIZE_SUFFIX, -1)
        val cleanupPath = bundle.getString(key + CLEANUP_PATH_SUFFIX)
        bundle.remove(key + FD_SUFFIX)
        bundle.remove(key + SIZE_SUFFIX)
        bundle.remove(key + CLEANUP_PATH_SUFFIX)
        return try {
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                require(size in 0..MAX_TRANSFER_BYTES && descriptor.statSize == size) {
                    "Invalid history transfer descriptor"
                }
                val bytes = ByteArray(size.toInt())
                var offset = 0
                while (offset < bytes.size) {
                    val read = input.read(bytes, offset, bytes.size - offset)
                    check(read > 0) { "Incomplete history transfer" }
                    offset += read
                }
                check(input.read() == -1) { "History transfer size mismatch" }
                bytes.toString(Charsets.UTF_8)
            }
        } finally {
            cleanupTransferFile(cleanupPath)
        }
    }

    /** 同进程 Messenger 不经过 Parcel；必须显式复制接收端的描述符所有权。 */
    fun send(target: Messenger?, message: Message) {
        val original = message.data
        var localCopy: Bundle? = null
        try {
            if (target == null) return
            if (target.binder is Binder) {
                val copy = Bundle(original)
                localCopy = copy
                original.keySet().filter { it.endsWith(FD_SUFFIX) }.forEach { key ->
                    copy.putParcelable(key, original.getParcelable(key, ParcelFileDescriptor::class.java)!!.dup())
                }
                message.data = copy
            }
            target.send(message)
            localCopy = null
        } finally {
            localCopy?.let(::close)
            close(original)
        }
    }

    fun close(bundle: Bundle) {
        bundle.keySet().filter { it.endsWith(FD_SUFFIX) }.forEach { key ->
            bundle.getParcelable(key, ParcelFileDescriptor::class.java)?.close()
            bundle.remove(key)
            bundle.remove(key.removeSuffix(FD_SUFFIX) + SIZE_SUFFIX)
            val cleanupKey = key.removeSuffix(FD_SUFFIX) + CLEANUP_PATH_SUFFIX
            val cleanupPath = bundle.getString(cleanupKey)
            bundle.remove(cleanupKey)
            cleanupTransferFile(cleanupPath)
        }
    }

    private fun cleanupTransferFile(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching {
            val file = File(path).canonicalFile
            if (file.name.startsWith("eta-context-") && file.name.endsWith(".json")) {
                file.delete()
            }
        }
    }
}
