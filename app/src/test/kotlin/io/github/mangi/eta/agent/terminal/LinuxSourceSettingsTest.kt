package io.github.mangi.eta.agent.terminal

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class LinuxSourceSettingsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clean() { java.io.File(context.filesDir, "linux-environment/sources.json").delete() }

    @Test fun persistsCustomHttpsRootfsAndAptSourcesAndRejectsCredentials() {
        LinuxSourceSettings.add(context, "Alpine mirror", LinuxDistribution.ALPINE, LinuxSourceSettings.ROOTFS,
            "https://mirror.example.com/alpine")
        LinuxSourceSettings.add(context, "Debian mirror", LinuxDistribution.DEBIAN, LinuxSourceSettings.APT,
            "https://mirror.example.com/debian", "https://mirror.example.com/security")
        val entries = LinuxSourceSettings.entries(context)
        assertEquals(2, entries.size)
        assertThrows(IllegalArgumentException::class.java) {
            LinuxSourceSettings.add(context, "unsafe", LinuxDistribution.ALPINE, LinuxSourceSettings.APK,
                "https://user:pass@mirror.example.com/alpine")
        }
    }

    @Test fun configuredMirrorsPrecedeBuiltinsInArtifactsAndScripts() {
        val alpine = AlpineEnvironmentInstaller.artifactForAbis(listOf("arm64-v8a"), listOf("https://mirror.example.com/alpine"))!!
        assertTrue(alpine.preferredUrls.first().startsWith("https://mirror.example.com/alpine/v3.24"))
        assertTrue(AlpineEnvironmentInstaller.apkMirrorScript(listOf("https://mirror.example.com/apk")).contains("mirror.example.com/apk"))
        val debian = DebianEnvironmentInstaller.artifactForAbis(listOf("arm64-v8a"), listOf("https://mirror.example.com/debian/rootfs.tar.xz"))!!
        assertEquals("https://mirror.example.com/debian/rootfs.tar.xz", debian.preferredUrls.first())
        assertTrue(DebianEnvironmentInstaller.aptMirrorScript(listOf(
            DebianAptMirror("custom", "https://mirror.example.com/debian", "https://mirror.example.com/security")
        )).contains("mirror.example.com/debian"))
    }
    @Test fun sourceSelectionPersistsAndArchiveMirrorsKeepOfficialDigest() {
        val custom = LinuxSourceSettings.add(context, "Node CN", LinuxDistribution.DEBIAN, LinuxSourceSettings.NODE,
            "https://mirror.example.com/node/v{version}/{file}").last()
        LinuxSourceSettings.select(context, LinuxDistribution.DEBIAN, LinuxSourceSettings.NODE, custom.id)
        val official = PinnedLinuxToolArtifacts.artifactFor(ManagedLinuxTool.NODE, LinuxDistribution.DEBIAN, listOf("arm64-v8a"))!!
        val mirrored = LinuxSourceSettings.resolveArtifact(context, LinuxDistribution.DEBIAN, official, LinuxSourceSettings.NODE)
        assertEquals("https://mirror.example.com/node/v26.13.1/node-v26.13.1-linux-arm64.tar.xz", mirrored.preferredUrls.first())
        assertEquals(official.sha256, mirrored.sha256)
        assertEquals(official.sizeBytes, mirrored.sizeBytes)
        assertEquals(custom.id, LinuxSourceSettings.selectedId(context, LinuxDistribution.DEBIAN, LinuxSourceSettings.NODE))
        LinuxSourceSettings.delete(context, custom.id)
        assertTrue(LinuxSourceSettings.sourceUrls(context, LinuxDistribution.DEBIAN, LinuxSourceSettings.NODE).isNotEmpty())
    }

    @Test fun rejectsShellFragmentsUnknownTemplatesAndTraversal() {
        listOf("https://mirror.example.com/${'$'}(id)", "https://mirror.example.com/a;id", "https://mirror.example.com/a/../b", "https://mirror.example.com/{command}", "http://mirror.example.com", "https://mirror.example.com/%2e%2e/b").forEach { url ->
            assertThrows(IllegalArgumentException::class.java) {
                LinuxSourceSettings.add(context, "bad", LinuxDistribution.DEBIAN, LinuxSourceSettings.TOOL_ARCHIVE, url)
            }
        }
    }

    @Test fun everySourceKindHasChineseAndOverseasPresets() {
        LinuxDistribution.entries.forEach { distribution -> LinuxSourceSettings.kindsFor(distribution).forEach { kind ->
            val presets = LinuxSourceSettings.builtins(distribution, kind)
            assertEquals(listOf("china", "overseas"), presets.map { it.id })
            presets.forEach { LinuxSourceSettings.validateUrl(it.archiveUrl) }
        } }
    }

    @Test fun oldV1SourceFileRemainsReadableAfterPreferredSourceChanges() {
        val file = java.io.File(context.filesDir, "linux-environment/sources.json")
        file.parentFile!!.mkdirs()
        file.writeText("""{"version":1,"sources":[{"id":"source-legacy","name":"legacy","distribution":"ALPINE","kind":"apk","archiveUrl":"https://mirror.example.com/alpine"}]}""")
        assertEquals("source-legacy", LinuxSourceSettings.entries(context).single().id)
        LinuxSourceSettings.select(context, LinuxDistribution.ALPINE, LinuxSourceSettings.APK, "overseas")
        assertEquals("source-legacy", LinuxSourceSettings.entries(context).single().id)
        assertEquals("https://dl-cdn.alpinelinux.org/alpine", LinuxSourceSettings.sourceUrls(context, LinuxDistribution.ALPINE, LinuxSourceSettings.APK).first())
    }
    @Test fun rootfsTemplateUsesArchitectureFromFileName() {
        val artifact = AlpineEnvironmentInstaller.artifactForAbis(listOf("arm64-v8a"))!!
        assertEquals("aarch64", LinuxSourceSettings.artifactArchitecture(artifact))
        val mirrored = LinuxSourceSettings.resolveArtifact(context, LinuxDistribution.ALPINE, artifact, LinuxSourceSettings.ROOTFS)
        assertTrue(mirrored.preferredUrls.first().contains("/aarch64/alpine-minirootfs-3.24.4-aarch64.tar.gz"))
        assertEquals("x86_64", LinuxSourceSettings.artifactArchitecture(AlpineEnvironmentInstaller.artifactForAbis(listOf("x86_64"))!!))
    }

    @Test fun uvAndNodeBuiltinTemplatesResolveBeforeDownload() {
        val cases = listOf(
            Triple(ManagedLinuxTool.UV, LinuxDistribution.ALPINE, LinuxSourceSettings.UV),
            Triple(ManagedLinuxTool.UV, LinuxDistribution.DEBIAN, LinuxSourceSettings.UV),
            Triple(ManagedLinuxTool.NODE, LinuxDistribution.DEBIAN, LinuxSourceSettings.NODE),
        )
        cases.forEach { (tool, distribution, kind) ->
            val artifact = PinnedLinuxToolArtifacts.artifactFor(tool, distribution, listOf("arm64-v8a"))!!
            val resolved = LinuxSourceSettings.resolveArtifact(context, distribution, artifact, kind)
            val expectedMirror = if (tool == ManagedLinuxTool.UV) "https://gh-proxy.com/${artifact.url}"
                else "https://cdn.npmmirror.com/binaries/node/v${artifact.version}/${artifact.fileName}"
            assertEquals(expectedMirror, resolved.preferredUrls.first())
            assertTrue(resolved.preferredUrls.contains(artifact.url))
            assertEquals(artifact.sha256, resolved.sha256)
            assertEquals(artifact.sizeBytes, resolved.sizeBytes)
        }
    }

    @Test fun placeholderParserAcceptsRepeatedFieldsAndRejectsMalformedBraces() {
        val template = "https://mirror.example.com/{version}/{arch}/{file}?upstream={official}&copy={file}"
        assertEquals(template, LinuxSourceSettings.validateUrl(template))
        assertEquals(
            "https://mirror.example.com/1.2.3/arm64/tool.tar.gz?upstream=https://official.example.com/tool.tar.gz&copy=tool.tar.gz",
            LinuxSourceSettings.expand(template, "1.2.3", "tool.tar.gz", "arm64", "https://official.example.com/tool.tar.gz"),
        )
        listOf("{file", "file}", "{}", "{{file}}", "{file{arch}}", "{unknown}").forEach { malformed ->
            assertThrows(IllegalArgumentException::class.java) {
                LinuxSourceSettings.validateUrl("https://mirror.example.com/$malformed")
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            LinuxSourceSettings.validateUrl("https://mirror.example.com/{file}", templates = false)
        }
    }
}
