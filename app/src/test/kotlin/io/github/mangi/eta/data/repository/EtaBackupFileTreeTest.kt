package io.github.mangi.eta.data.repository

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class EtaBackupFileTreeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun includesEmptyDirectoriesAndChecksLimitsDuringTraversal() = runBlocking {
        val root = temporary.root
        File(root, "files/empty").mkdirs()
        File(root, "files/file.txt").writeText("content")
        val paths = mutableListOf<String>()
        EtaBackupFileTree.walk(root, listOf("files"), emptySet()) { file, _ -> paths += file.relativeTo(root).invariantSeparatorsPath }
        assertEquals(setOf("files", "files/empty", "files/file.txt"), paths.toSet())
        val failure = runCatching { EtaBackupFileTree.walk(root, listOf("files"), emptySet(), maxEntries = 2) { _, _ -> } }
        assertTrue(failure.exceptionOrNull() is EtaBackupException)
    }

    @Test fun excludesSubtreesBeforeDescendingAndPropagatesCancellation() = runBlocking {
        val root = temporary.root
        File(root, "files/excluded/deep").mkdirs()
        File(root, "files/excluded/deep/secret").writeText("not scanned")
        val paths = mutableListOf<String>()
        EtaBackupFileTree.walk(root, listOf("files"), setOf("files/excluded")) { file, _ -> paths += file.name }
        assertEquals(listOf("files"), paths)
        val failure = runCatching {
            EtaBackupFileTree.walk(root, listOf("files"), emptySet()) { _, _ -> throw CancellationException() }
        }
        assertTrue(failure.exceptionOrNull() is CancellationException)
    }

    @Test fun doesNotFollowCyclesOrExternalDirectoryLinks() = runBlocking {
        val root = temporary.newFolder("app")
        val outside = temporary.newFolder("outside")
        File(outside, "secret").writeText("never read")
        val files = File(root, "files").apply { mkdirs() }.toPath()
        try {
            Files.createSymbolicLink(files.resolve("cycle"), files)
            Files.createSymbolicLink(files.resolve("external"), outside.toPath())
        } catch (failure: Exception) { assumeNoException("Host cannot create symlinks", failure) }
        val links = mutableSetOf<String>()
        val regular = mutableSetOf<String>()
        EtaBackupFileTree.walk(root, listOf("files"), emptySet()) { file, attrs ->
            if (attrs.isSymbolicLink) links += file.name
            if (attrs.isRegularFile) regular += file.name
        }
        assertEquals(setOf("cycle", "external"), links)
        assertTrue(regular.isEmpty())
    }

    @Test fun cleanupDeletesLinksWithoutDeletingTheirTargets() {
        val root = temporary.newFolder("staging")
        val outside = temporary.newFolder("outside")
        val secret = File(outside, "keep.txt").apply { writeText("keep") }
        try { Files.createSymbolicLink(File(root, "external").toPath(), outside.toPath())
        } catch (failure: Exception) { assumeNoException("Host cannot create symlinks", failure) }
        EtaBackupFileTree.delete(root)
        assertFalse(Files.exists(root.toPath(), NOFOLLOW_LINKS))
        assertEquals("keep", secret.readText())
    }
}
