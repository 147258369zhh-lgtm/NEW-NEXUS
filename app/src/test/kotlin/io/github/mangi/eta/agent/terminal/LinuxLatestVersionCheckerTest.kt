package io.github.mangi.eta.agent.terminal

import org.junit.Assert.*
import org.junit.Test

class LinuxLatestVersionCheckerTest {
    @Test fun parsesOnlyStableOfficialMetadataAndKeepsDistributionVersionSeparate() {
        assertEquals("26.13.1", LinuxLatestVersionChecker.parseVersion("node", """[{"version":"v28.0.0-rc.1"},{"version":"v26.13.1"}]"""))
        assertNull(LinuxLatestVersionChecker.parseVersion("uv", """{"tag_name":"0.14.0","prerelease":true}"""))
        assertEquals("1.27.4", LinuxLatestVersionChecker.parseVersion("go", """[{"version":"go1.28rc1","stable":false},{"version":"go1.27.4","stable":true}]"""))
        assertEquals("13.7", LinuxLatestVersionChecker.parseVersion("debian", "<p>Debian 13.7 was released</p>"))
        assertEquals("27", LinuxLatestVersionChecker.parseVersion("jdk", """GA Releases</div><a href="./27/">JDK 27</a>"""))
        assertEquals("3.15.2", LinuxLatestVersionChecker.parseVersion("python", "Download Python 3.15.2"))
    }
    @Test fun sdkArtifactsMatchLibcAndDoNotOfferGlibcJavaOnAlpine() {
        val musl = LinuxSdkArtifacts.artifactFor(ManagedLinuxTool.RUST, LinuxDistribution.ALPINE, listOf("arm64-v8a"))!!
        assertTrue(musl.fileName.contains("aarch64-unknown-linux-musl"))
        assertEquals("1.100.0", musl.version)
        assertTrue(musl.sha256.matches(Regex("[0-9a-f]{64}")))
        assertNull(LinuxSdkArtifacts.artifactFor(ManagedLinuxTool.JDK, LinuxDistribution.ALPINE, listOf("arm64-v8a")))
        assertEquals("27.0.1.0.3", LinuxSdkArtifacts.artifactFor(ManagedLinuxTool.JDK, LinuxDistribution.DEBIAN, listOf("arm64-v8a"))!!.version)
    }
    @Test fun aptUpgradeDoesNotAllowPackageRemoval() {
        assertTrue(DebianEnvironmentInstaller.aptMirrorScript().contains("dist-upgrade --no-remove"))
        assertTrue(AlpineEnvironmentInstaller.apkMirrorScript().contains("apk upgrade --no-cache"))
    }
    @Test fun mavenAndGradleDiscoveryRejectPrereleasesAndUseUniversalArchives() {
        assertEquals("3.10.0", LinuxLatestVersionChecker.parseVersion("maven", "<versions><version>3.9.16</version><version>4.0.0-rc-7</version><version>3.10.0</version></versions>"))
        assertEquals("9.9.1", LinuxLatestVersionChecker.parseVersion("gradle", """{"version":"9.9.1","snapshot":false,"activeRc":false}"""))
        assertNull(LinuxLatestVersionChecker.parseVersion("gradle", """{"version":"10.0-rc-1","activeRc":true}"""))
        val gradle = LinuxSdkArtifacts.artifactFor(ManagedLinuxTool.GRADLE, LinuxDistribution.ALPINE, listOf("arm64-v8a"))!!
        assertEquals("9.9.1", gradle.version)
        assertEquals("all", LinuxSourceSettings.artifactArchitecture(gradle))
        assertEquals(134_921_139L, gradle.sizeBytes)
        assertEquals("3.10.0", LinuxSdkArtifacts.artifactFor(ManagedLinuxTool.MAVEN, LinuxDistribution.DEBIAN, listOf("x86_64"))!!.version)
    }
}
