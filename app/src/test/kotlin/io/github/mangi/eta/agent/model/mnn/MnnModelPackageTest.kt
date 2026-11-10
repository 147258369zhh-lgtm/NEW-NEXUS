package io.github.mangi.eta.agent.model.mnn

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MnnModelPackageTest {
    @Test
    fun validatesMinimumDirectoryAndOptionalVision() {
        val root = Files.createTempDirectory("mnn-package").toFile()
        try {
            File(root, "config.json").writeText("{}")
            File(root, "llm.mnn").writeText("graph")
            assertTrue(MnnModelPackage.isModelDirectory(root))
            assertFalse(MnnModelPackage.hasVision(root))
            File(root, "visual.mnn").writeText("vision")
            assertTrue(MnnModelPackage.hasVision(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun extractsNestedRepositoryRootAndHashesDeterministically() {
        val zip = zipOf(
            "Qwen3-MNN/config.json" to "{}",
            "Qwen3-MNN/llm.mnn" to "graph",
            "Qwen3-MNN/llm.mnn.weight" to "weights",
        )
        val out = Files.createTempDirectory("mnn-extract").toFile()
        try {
            val root = MnnModelPackage.extractZip(ByteArrayInputStream(zip), out, 1024)
            assertTrue(MnnModelPackage.isModelDirectory(root))
            assertEquals(root, root.canonicalFile)
            assertEquals(MnnModelPackage.sha256(root), MnnModelPackage.sha256(root))
        } finally {
            out.deleteRecursively()
        }
    }

    @Test
    fun rejectsZipSlip() {
        val zip = zipOf("../config.json" to "{}", "llm.mnn" to "graph")
        val out = Files.createTempDirectory("mnn-zip-slip").toFile()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                MnnModelPackage.extractZip(ByteArrayInputStream(zip), out, 1024)
            }
        } finally {
            out.deleteRecursively()
        }
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (path, value) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(value.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
}
