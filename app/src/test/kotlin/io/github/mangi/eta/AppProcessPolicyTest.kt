package io.github.mangi.eta

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppProcessPolicyTest {
    @Test fun packageSandboxDoesNotInitializeStorageOrAudioOwners() {
        val app = "io.github.zhh_lgtm.nexus"
        assertTrue(AppProcessPolicy.isPackageSandbox("$app:package_sandbox", app))
        assertTrue(AppProcessPolicy.isPackageSandbox("$app:package_sandbox:instance_1", app))
        assertFalse(AppProcessPolicy.isPackageSandbox(app, app))
        assertFalse(AppProcessPolicy.isPackageSandbox("$app:voice", app))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("$app:package_sandbox:instance_1", app))
    }
    @Test
    fun `仅主进程初始化完整 Runtime 依赖`() {
        assertTrue(AppProcessPolicy.shouldInitializeFullRuntime("io.github.mangi.eta", "io.github.mangi.eta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.mangi.eta:voice", "io.github.mangi.eta"))
        assertFalse(AppProcessPolicy.shouldInitializeFullRuntime("io.github.mangi.eta:voice_session", "io.github.mangi.eta"))
    }
}
