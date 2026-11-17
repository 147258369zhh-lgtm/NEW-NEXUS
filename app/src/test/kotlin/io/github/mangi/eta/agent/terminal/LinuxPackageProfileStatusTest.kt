package io.github.mangi.eta.agent.terminal

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class LinuxPackageProfileStatusTest {
    @Test fun legacyMarkerDoesNotCertifyCurrentRuntime() {
        val file = File.createTempFile("nexa-profile-", ".marker")
        try {
            file.writeText("profile=1\n")
            assertFalse(LinuxPackageProfileStatus.read(file, 1).ready)
            file.writeText("schema=2\nprofile=1\nready=true\ncheckedAt=123000\nversion=go: go1.25.1\n")
            assertTrue(LinuxPackageProfileStatus.read(file, 1).ready)
            assertFalse(LinuxPackageProfileStatus.read(file, 2).ready)
            assertEquals(123000L, LinuxPackageProfileStatus.read(file, 1).checkedAt)
        } finally { file.delete() }
    }
    @Test fun partialOrFailedProbeCannotClaimSuccess() {
        assertFalse(LinuxPackageProfileStatus.fromProbe(73, "NEXA_VERSION=node: v25.1.0").ready)
        assertFalse(LinuxPackageProfileStatus.fromProbe(0, "").ready)
        val result = LinuxPackageProfileStatus.fromProbe(0, "noise\nNEXA_VERSION=go: go1.25.1\nNEXA_VERSION=rustc: 1.90.0")
        assertTrue(result.ready)
        assertEquals("go: go1.25.1; rustc: 1.90.0", result.installedVersion)
    }
    @Test fun generatedNodeProbePreservesEveryVersionLabelInItsMarker() {
        val directory = Files.createTempDirectory("nexa-profile-probe-").toFile()
        try {
            val profile = LinuxPackageProfiles.NODE
            // Keep guest-root output paths inside the host test directory.
            val script = linuxProfileProbeScript(profile, LinuxDistribution.DEBIAN, writeMarker = true)
                .replace(shellQuote("/${profile.markerName}"), shellQuote("probe.marker"))
                .replace("mktemp /tmp/nexa-profile.XXXXXX", "mktemp ./nexa-profile.XXXXXX")
            val scriptFile = File(directory, "probe.sh")
            // A file avoids Windows ProcessBuilder's quoting of the multiline sh -c argument.
            scriptFile.writeText("""
                cd "${'$'}NEXA_PROFILE_TEST_DIRECTORY" || exit 91
                printf 'NEXA_TEST_SHELL=%s\n' "${'$'}(command -v sh)"
                printf 'NEXA_TEST_CWD=%s\n' "${'$'}PWD"
                node() { printf 'v26.13.1\n'; }
                npm() { printf '11.20.0\n'; }
                $script
            """.trimIndent())
            val builder = ProcessBuilder("sh", scriptFile.name).directory(directory).redirectErrorStream(true)
            // Git sh accepts drive-letter paths with forward slashes; avoid relying on
            // the inherited MSYS PWD matching ProcessBuilder's Windows working directory.
            builder.environment()["NEXA_PROFILE_TEST_DIRECTORY"] = directory.invariantSeparatorsPath
            val process = builder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            assertEquals(output, 0, process.waitFor())

            val live = LinuxPackageProfileStatus.fromProbe(0, output)
            val marker = File(directory, "probe.marker")
            assertTrue("Missing marker ${marker.absolutePath}; files=${directory.list()?.toList()}; shell output:\n$output", marker.isFile)
            val markerText = marker.readText()
            val cached = LinuxPackageProfileStatus.read(marker, profile.revision)
            assertTrue(live.ready)
            assertTrue("Marker was not ready:\n$markerText\nShell output:\n$output", cached.ready)
            assertEquals("node: v26.13.1 ; npm: 11.20.0 ", live.installedVersion)
            assertEquals(live.installedVersion, cached.installedVersion)
        } finally { directory.deleteRecursively() }
    }
    @Test fun versionSelectionSkipsWarningsAndPreservesProbeFailureChecks() {
        val selector = linuxProfileVersionEvidenceScript()
        assertTrue(selector.contains("/[0-9]+[.][0-9]+/"))
        assertTrue(selector.contains("first == \"\" && /[^[:space:]]/"))
        assertTrue(selector.contains("END { if (!found && first != \"\") print first }"))
        val script = linuxProfileProbeScript(LinuxCapabilityCatalog.PACKET_ANALYSIS, LinuxDistribution.DEBIAN, true)
        assertFalse(script.contains("head -n 1"))
        assertTrue(script.contains("tshark --version 2>&1 ) || { printf 'NEXA_ERROR=Probe failed:"))
        assertTrue(script.contains(selector))
        assertTrue(script.indexOf(selector) < script.indexOf("schema=2"))
        assertEquals(6, script.lineSequence().count { it == "[ -n \"${'$'}nexa_version\" ] || exit 73" })
    }
    @Test fun documentProfilesRequireChineseFontsAndAWorkingTexPdf() {
        assertTrue(LinuxCapabilityCatalog.PDF_FONTS.revision > 1)
        assertTrue(LinuxCapabilityCatalog.TEX.revision > 1)
        LinuxDistribution.entries.forEach { distribution ->
            val fontProbes = linuxProfileChecks(LinuxCapabilityCatalog.PDF_FONTS, distribution)
            assertTrue(fontProbes.any { it.command == "fc-match" && it.versionCommand.contains("Noto.*(CJK|SC)") })
            assertFalse(fontProbes.any { it.versionCommand.contains("head -n 3") })
            val tex = linuxProfileProbeScript(LinuxCapabilityCatalog.TEX, distribution, true)
            assertTrue(tex.contains("\\usepackage{xeCJK}"))
            assertTrue(tex.contains("-no-shell-escape"))
            assertTrue(tex.indexOf("probe.pdf") < tex.indexOf("schema=2"))
            assertTrue(tex.contains("tail -n 16"))
        }
        assertTrue(LinuxCapabilityCatalog.TEX.spec(LinuxDistribution.ALPINE).packages.contains("texmf-dist-langchinese"))
        assertEquals(LinuxCapabilityCatalog.PDF_FONTS, LinuxCapabilityCatalog.TEX.dependsOn)
    }
    @Test fun dependencyGraphHasNoCyclesAndAllSupportedProfilesHaveRuntimeProbes() {
        val all = LinuxPackageProfiles.ALL
        assertEquals(all.size, all.map { it.id }.distinct().size)
        all.forEach { profile ->
            val visited = mutableSetOf<String>()
            var dependency: LinuxPackageProfile? = profile
            while (dependency != null) {
                assertTrue("dependency cycle ${profile.id}", visited.add(dependency.id))
                dependency = dependency.dependsOn
            }
            profile.specs.keys.forEach { distribution ->
                assertTrue("missing checks ${profile.id}", linuxProfileChecks(profile, distribution).isNotEmpty())
                val script = linuxProfileProbeScript(profile, distribution, true)
                assertTrue(script.indexOf("command -v") < script.indexOf("schema=2"))
            }
        }
        assertTrue(LinuxCapabilityCatalog.PDF.specs.containsKey(LinuxDistribution.ALPINE))
        assertFalse(LinuxCapabilityCatalog.DUCKDB.specs.containsKey(LinuxDistribution.ALPINE))
    }
}
