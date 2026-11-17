package io.github.mangi.eta.agent.terminal

import java.io.File
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/** Gradle archives contain a single top-level directory. Never create links or write outside staging. */
internal object LinuxSdkZipExtractor {
    suspend fun extract(archive: File, staging: File) {
        require(staging.mkdirs() || staging.isDirectory)
        val root = staging.canonicalFile.toPath()
        var expanded = 0L
        var entries = 0
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while(true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                require(++entries <= 200_000)
                val name = entry.name
                require(!name.startsWith('/') && !name.contains('\\') && name.split('/').none { it == "." || it == ".." })
                val relative = name.substringAfter('/', "").trimEnd('/')
                if(relative.isBlank()) { zip.closeEntry(); continue }
                val target = File(staging, relative).canonicalFile
                require(target.toPath().startsWith(root) && target.toPath() != root)
                if(entry.isDirectory) require(target.mkdirs() || target.isDirectory) else {
                    require(target.parentFile!!.mkdirs() || target.parentFile!!.isDirectory)
                    target.outputStream().use { out ->
                        val chunk = ByteArray(8192)
                        while(true) {
                            coroutineContext.ensureActive()
                            val count = zip.read(chunk)
                            if(count < 0) break
                            expanded += count
                            require(expanded <= 3L * 1024 * 1024 * 1024) { "归档展开大小超限" }
                            out.write(chunk,0,count)
                        }
                    }
                }
                zip.closeEntry()
            }
        }
    }
}
