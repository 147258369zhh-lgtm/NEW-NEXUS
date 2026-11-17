package io.github.mangi.eta.agent.terminal

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LinuxSdkZipExtractorTest {
    @get:Rule val folder = TemporaryFolder()
    private fun zip(name: String): File = folder.newFile().also { file ->
        ZipOutputStream(file.outputStream()).use { output ->
            output.putNextEntry(ZipEntry(name)); output.write("payload".toByteArray()); output.closeEntry()
        }
    }
    @Test fun removesTopLevelDirectoryWithoutEscapingStaging() = runBlocking {
        val staging = folder.newFolder("staging")
        LinuxSdkZipExtractor.extract(zip("gradle-9.9.1/bin/gradle"), staging)
        assertEquals("payload", File(staging,"bin/gradle").readText())
    }
    @Test fun rejectsZipTraversalBeforeWritingOutsideStaging() {
        val staging = folder.newFolder("unsafe")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { LinuxSdkZipExtractor.extract(zip("gradle/../../escape"), staging) }
        }
        assertFalse(File(staging.parentFile,"escape").exists())
    }
}
