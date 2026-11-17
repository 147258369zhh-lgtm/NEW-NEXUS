package io.github.mangi.eta.data.repository

import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunArtifactCaptureTerminalTest {
    @Test fun reportsCandidateTruncationAndIncompleteScans() {
        val root = Files.createTempDirectory("nexus-terminal-limit").toFile()
        try {
            val roots = listOf(RunArtifactCapture.TerminalScanRoot("android", root))
            val snapshot = RunArtifactCapture.snapshotTerminalRoots(roots)
            repeat(30) { File(root, "$it.txt").writeText("result") }
            val result = JSONObject(RunArtifactCapture.terminalResult("terminal", JSONObject(), "{\"ok\":true}", 0, roots, snapshot))
            assertEquals(30, result.getInt("generated_file_count"))
            assertEquals(24, result.getJSONArray("generated_files").length())
            assertTrue(result.getBoolean("generated_files_truncated"))
            assertFalse(result.getBoolean("generated_files_scan_incomplete"))
            val missing = listOf(RunArtifactCapture.TerminalScanRoot("android", File(root, "missing")))
            val incomplete = JSONObject(RunArtifactCapture.terminalResult("terminal", JSONObject(), "{\"ok\":true}", 0, missing))
            assertTrue(incomplete.getBoolean("generated_files_scan_incomplete"))
        } finally { root.deleteRecursively() }
    }
    @Test
    fun foregroundTerminalReceiptListsOnlyRecentOrdinaryWorkspaceFiles() {
        val root = Files.createTempDirectory("nexus-terminal-capture").toFile()
        try {
            val now = System.currentTimeMillis()
            File(root, "old.txt").apply {
                writeText("old")
                setLastModified(now - 10_000)
            }
            val roots = listOf(RunArtifactCapture.TerminalScanRoot("android", root))
            val snapshot = RunArtifactCapture.snapshotTerminalRoots(roots)
            val output = File(root, "reports/result.csv").apply {
                parentFile?.mkdirs()
                writeText("a,b\n1,2\n")
                setLastModified(now - 10_000)
            }
            File(root, "build/cache.bin").apply {
                parentFile?.mkdirs()
                writeText("internal")
                setLastModified(now)
            }

            val result = JSONObject(
                RunArtifactCapture.terminalResult(
                    toolName = "terminal",
                    args = JSONObject().put("action", "exec"),
                    raw = "{\"ok\":true}",
                    startedAt = now,
                    roots = roots,
                    snapshot = snapshot,
                ),
            )
            val files = result.getJSONArray("generated_files")

            assertEquals(1, result.getInt("generated_file_count"))
            assertEquals("requires_deliver_file", result.getString("artifact_registration"))
            assertEquals(1, files.length())
            assertEquals("reports/result.csv", files.getJSONObject(0).getString("path"))
            assertEquals("android", files.getJSONObject(0).getString("environment"))
            assertEquals("reports/result.csv", files.getJSONObject(0).getString("deliver_path"))
            assertEquals(output.length(), files.getJSONObject(0).getLong("size_bytes"))
            assertFalse(files.getJSONObject(0).has("absolute_path"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun failedOrNonExecTerminalResultRemainsUnchanged() {
        val root = Files.createTempDirectory("nexus-terminal-capture").toFile()
        try {
            File(root, "new.txt").writeText("new")
            val failed = "{\"ok\":false}"
            assertEquals(
                failed,
                RunArtifactCapture.terminalResult("terminal", JSONObject().put("action", "exec"), failed, 0,
                    listOf(RunArtifactCapture.TerminalScanRoot("android", root))),
            )
            val open = "{\"ok\":true,\"session_id\":\"term\"}"
            assertEquals(
                open,
                RunArtifactCapture.terminalResult("terminal", JSONObject().put("action", "open"), open, 0,
                    listOf(RunArtifactCapture.TerminalScanRoot("android", root))),
            )
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun backgroundJobWaitUsesItsRecordedStartTime() {
        val root = Files.createTempDirectory("nexus-terminal-capture").toFile()
        try {
            val now = System.currentTimeMillis()
            File(root, "background-report.html").apply {
                writeText("<html></html>")
                setLastModified(now)
            }
            val result = JSONObject(
                RunArtifactCapture.terminalResult(
                    toolName = "terminal_job",
                    args = JSONObject().put("action", "wait").put("job_id", "job-1"),
                    raw = JSONObject().put("ok", true).put("started_at", now - 30_000).toString(),
                    startedAt = now,
                    roots = listOf(RunArtifactCapture.TerminalScanRoot("linux", root, "/workspace")),
                ),
            )

            assertEquals("background-report.html", result.getJSONArray("generated_files").getJSONObject(0).getString("path"))
            assertEquals("linux", result.getJSONArray("generated_files").getJSONObject(0).getString("environment"))
            assertEquals("/workspace/background-report.html",
                result.getJSONArray("generated_files").getJSONObject(0).getString("deliver_path"))
        } finally {
            root.deleteRecursively()
        }
    }
}
