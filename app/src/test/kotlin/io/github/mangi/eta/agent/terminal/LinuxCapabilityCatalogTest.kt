package io.github.mangi.eta.agent.terminal

import org.junit.Assert.*
import org.junit.Test

class LinuxCapabilityCatalogTest {
    private val expanded = listOf(
        LinuxCapabilityCatalog.NETWORK_DIAGNOSTICS, LinuxCapabilityCatalog.NETWORK_SCAN,
        LinuxCapabilityCatalog.PACKET_ANALYSIS, LinuxCapabilityCatalog.WEB_SQLMAP,
        LinuxCapabilityCatalog.WEB_NIKTO, LinuxCapabilityCatalog.FILE_FORENSICS,
        LinuxCapabilityCatalog.SYSTEM_PERFORMANCE, LinuxCapabilityCatalog.DATABASE_CLIENTS,
        LinuxCapabilityCatalog.FILE_SYNC, LinuxCapabilityCatalog.VECTOR_GRAPHICS,
    )

    @Test fun profilesRespectConfiguredDistributionRepositories() {
        assertEquals(31, LinuxPackageProfiles.ALL.size)
        assertTrue(LinuxPackageProfiles.ALL.containsAll(expanded))
        assertEquals(setOf(LinuxDistribution.DEBIAN), LinuxCapabilityCatalog.WEB_SQLMAP.specs.keys)
        assertEquals(setOf(LinuxDistribution.ALPINE), LinuxCapabilityCatalog.WEB_NIKTO.specs.keys)
        assertEquals(setOf(LinuxDistribution.DEBIAN), LinuxCapabilityCatalog.FILE_FORENSICS.specs.keys)
        assertTrue("Debian trixie uses bind9-dnsutils", LinuxCapabilityCatalog.NETWORK_DIAGNOSTICS.spec(LinuxDistribution.DEBIAN).packages.contains("bind9-dnsutils"))
        assertTrue("Use the standalone Alpine CLI package", LinuxCapabilityCatalog.VECTOR_GRAPHICS.spec(LinuxDistribution.ALPINE).packages.contains("rsvg-convert"))
        assertEquals("nikto.pl", LinuxCapabilityCatalog.WEB_NIKTO.spec(LinuxDistribution.ALPINE).checks.single().command)
    }

    @Test fun databaseProfileContainsClientsWithoutServerPackagesOrStartup() {
        LinuxCapabilityCatalog.DATABASE_CLIENTS.specs.forEach { (distribution, spec) ->
            assertNull(spec.setupScript)
            assertFalse(spec.packages.any { it == "redis" || it.endsWith("-server") || it == "postgresql" })
            assertTrue(spec.packages.contains("mariadb-client"))
            if (distribution == LinuxDistribution.DEBIAN) {
                assertTrue(spec.packages.contains("redis-tools"))
                assertTrue(spec.checks.any { it.command == "redis-cli" })
            } else {
                assertTrue(spec.packages.contains("postgresql18-client"))
                assertFalse(spec.checks.any { it.command == "redis-cli" })
            }
        }
    }

    @Test fun securityAndUtilityReadinessOnlyUsesPassiveVersionOrHelpProbes() {
        val allowed = setOf(
            "dig -v", "mtr --version", "iperf3 --version", "traceroute --version", "nmap --version",
            "tcpdump --version", "tshark --version", "capinfos --version", "sqlmap --version",
            "nikto.pl -Version", "foremost -V", "hyperfine --version", "sar -V", "iostat -V", "strace -V",
            "psql --version", "mariadb --version", "redis-cli --version", "rclone version", "rsync --version",
            "rsvg-convert --version",
            "nexa_help=$(binwalk --help) && printf '%s\\n' \"${'$'}nexa_help\" | sed '/^[[:space:]]*$/d'",
        )
        expanded.forEach { profile ->
            profile.specs.forEach { (distribution, spec) ->
                assertNull("No automatic task for ${profile.id}", spec.setupScript)
                assertTrue(spec.checks.isNotEmpty())
                spec.checks.forEach { check -> assertTrue("Unexpected active probe: ${check.versionCommand}", check.versionCommand in allowed) }
                val script = linuxProfileProbeScript(profile, distribution, writeMarker = true)
                assertTrue(script.indexOf("command -v") < script.indexOf("schema=2"))
            }
        }
    }
}
