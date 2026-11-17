package io.github.mangi.eta.agent.terminal

import android.app.Application
import io.github.mangi.eta.agent.capability.NexusCapabilityDirectory
import io.github.mangi.eta.agent.capability.NexusRunHosts
import io.github.mangi.eta.agent.control.NexusSettingsControlPlane
import io.github.zhh_lgtm.nexus.application.api.SettingsActorKind
import io.github.zhh_lgtm.nexus.application.api.WorkspaceBinding
import io.github.zhh_lgtm.nexus.kernel.api.HostKind
import java.io.File
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LinuxRunHostTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test fun realDirectoryDescriptorsResolveToTheLinuxOwnerOnlyForLinuxArgumentsOrSessions() {
        val root = File(app.cacheDir, "linux-rootfs").apply { mkdirs() }
        File(root, LinuxEnvironmentPaths.READY_MARKER).writeText("ready")
        val workspace = WorkspaceBinding("binding", "project", "run", "/project-copy", sourceReference = "content://project/root")
        val execution = TerminalExecutionContext(TerminalEnvironment.DEBIAN, root.absolutePath,
            userWorkspacePath = workspace.hostPath, workspaceBinding = workspace)
        var granted = false; var closed = false
        val directory = NexusCapabilityDirectory.get(app)
        val local = LinuxRunHost.binding(execution, LinuxExecutionLimits(30, 1024, 2), directory, { closed }, { granted },
            { if (it == "linux-session") TerminalEnvironment.DEBIAN else null })
        NexusRunHosts(directory, emptyList(), listOf(local)).use { hosts ->
            val linux = JSONObject().put("environment", "linux")
            assertEquals(HostKind.LINUX, hosts.descriptor("read_file", linux)!!.hostKind)
            assertEquals(local.adapter.id, hosts.hostId("read_file", linux))
            assertFalse(hosts.isAvailable("read_file", linux))
            granted = true
            assertTrue(hosts.isAvailable("read_file", linux))
            assertEquals(HostKind.NATIVE_ANDROID, hosts.descriptor("read_file", JSONObject())!!.hostKind)
            assertEquals(HostKind.LINUX, hosts.descriptor("terminal", JSONObject().put("session_id", "linux-session"))!!.hostKind)
            val state = hosts.snapshot().single { it.getString("hostId") == local.adapter.id.value }
            assertEquals("READY", state.getString("state"))
            assertEquals(1024, state.getJSONObject("metadata").getJSONObject("limits").getInt("memory_mb"))
            assertEquals("/project-copy", state.getJSONObject("metadata").getJSONObject("workspace").getString("host_path"))
            assertTrue(state.getJSONObject("metadata").getJSONObject("workspace").getBoolean("source_read_only"))
            closed = true
            assertFalse(hosts.isAvailable("read_file", linux))
        }
    }

    @Test fun linuxLimitsAreSavedThroughTheAuditedControlPlaneAndUndoKeepsCurrentStateChecks() = runBlocking {
        val plane = NexusSettingsControlPlane.get(app)
        val original = LinuxExecutionSettings.read(app)
        val result = plane.applyJson("settings.linux.resources", LinuxExecutionLimits(90, 2048, 2).json(), SettingsActorKind.LOCAL_USER, "linux-ui")
        assertEquals(LinuxExecutionLimits(90, 2048, 2), LinuxExecutionSettings.read(app))
        assertEquals("APPLIED", plane.audit().single { it.optString("id") == result.getString("auditId") }.getString("phase"))
        plane.rollback(result.getString("auditId"), SettingsActorKind.LOCAL_USER)
        assertEquals(original, LinuxExecutionSettings.read(app))
        assertThrows(IllegalArgumentException::class.java) { LinuxExecutionLimits(cpuSeconds = 0) }
        assertThrows(IllegalArgumentException::class.java) { LinuxExecutionSettings.save(app, LinuxExecutionLimits().json().put("memory_mb", 10)) }
        Unit
    }

    @Test fun actualLinuxPayloadPlacesCpuAndMemoryLimitsBeforeTheRequestedCommand() {
        val limits = LinuxExecutionLimits(45, 1024, 2)
        val payload = ShellProcessSupervisor(workspacePath = "/project-copy", linuxLimits = limits)
            .buildLinuxPayload("/rootfs", "python3 script.py")
        assertTrue(payload.contains("ulimit -t 45")); assertTrue(payload.contains("ulimit -v 1048576"))
        assertTrue(payload.indexOf("ulimit -t 45") < payload.indexOf("python3 script.py"))
        assertTrue(payload.contains("project-copy")); assertFalse(payload.contains("ulimit -u"))
        val process = ProcessBuilder("sh", "-n").start()
        process.outputStream.use { it.write(payload.toByteArray()) }
        assertEquals(0, process.waitFor())
    }

    @Test fun concurrentStartsReserveTheShellSlotBeforeAnyCommandProcessCanBeLaunched() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val starts = java.util.concurrent.atomic.AtomicInteger()
        val supervisor = ShellProcessSupervisor(linuxLimits = LinuxExecutionLimits(maxShells = 1), processStarter = {
            starts.incrementAndGet(); entered.countDown(); release.await(3, java.util.concurrent.TimeUnit.SECONDS)
            throw java.io.IOException("fixture launch failed")
        })
        val first = async(kotlinx.coroutines.Dispatchers.IO) { supervisor.startShellProcess("user", "must not run", false) }
        try {
            assertTrue(entered.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS))
            assertNull(supervisor.startShellProcess("user", "must not start another command", false))
            assertEquals(1, starts.get())
            release.countDown()
            assertNull(kotlinx.coroutines.withTimeout(2000) { first.await() })
            assertNull(supervisor.startShellProcess("user", "new command", false))
            assertEquals(2, starts.get())
        } finally { release.countDown(); first.cancel() }
    }
}
