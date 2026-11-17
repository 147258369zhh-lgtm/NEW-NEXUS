package io.github.mangi.eta.agent.terminal

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalInputPathMappingTest {
    @Test
    fun sessionInputDirectoryMapsToLinuxBridge() {
        val root = "/data/user/0/io.github.zhh_lgtm.nexus/files/terminal-user/workspace"

        assertEquals(
            "/nexus-inputs/imports/run-input/report.pdf",
            TerminalInputPathMapping.linuxPath(root, "$root/imports/run-input/report.pdf"),
        )
        assertEquals(
            "/nexus-inputs/projects/project/run/files/output.csv",
            TerminalInputPathMapping.linuxPath(root, "$root/./projects//project/run/files/output.csv"),
        )
        assertEquals("/nexus-inputs", TerminalInputPathMapping.linuxPath(root, root))
    }

    @Test
    fun phoneUrisAndForeignPathsHaveNoLinuxPath() {
        val root = "/data/user/0/io.github.zhh_lgtm.nexus/files/terminal-user/workspace"

        assertNull(TerminalInputPathMapping.linuxPath(root, "content://com.android.providers.media.documents/document/image%3A42"))
        assertNull(TerminalInputPathMapping.linuxPath(root, "/storage/emulated/0/Download/report.pdf"))
        assertNull(TerminalInputPathMapping.linuxPath(root, "imports/run-input/report.pdf"))
        assertNull(TerminalInputPathMapping.linuxPath(root, "$root/../outside.txt"))
    }

    @Test
    fun chrootPayloadMountsSessionInputsReadableBridge() {
        // 纯字符串断言，使用 Android 命名空间的 POSIX 路径。
        val inputs = "/data/user/0/io.github.zhh_lgtm.nexus/files/terminal-user/workspace"
        val chroot = ShellProcessSupervisor().buildLinuxPayload(
            rootfsPath = "/tree/terminal/alpine/rootfs",
            command = "pwd",
            inputsWorkspace = inputs,
        )
        val inputsMount = chroot.lines().first { TerminalRuntime.LINUX_INPUTS_PATH in it }
        assertTrue(inputsMount.contains("eta_mount_optional"))
        assertTrue(inputsMount.contains(inputs))
    }
}

