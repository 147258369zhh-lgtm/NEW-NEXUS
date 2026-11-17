package io.github.mangi.eta.agent.terminal

import org.junit.Assert.*
import org.junit.Test

class LinuxPackageInstallDiagnosticsTest {
    @Test fun aptReportsRealDownloadAndInstallationPercentagesWithoutInventedBytes() {
        val parser = LinuxPackageProgressParser("tex")
        val download = parser.parse("dlstatus:1:9.46357:Downloading file 1 of 3 (4m39s remaining)")!!
        assertEquals(PackageProfileInstallStage.DOWNLOADING, download.stage)
        assertEquals(9, download.percent)
        assertEquals("tex", download.profileId)
        assertEquals(0L, download.downloadedBytes)
        assertEquals(0L, download.totalBytes)
        val installation = parser.parse("pmstatus:texlive-xetex:80:Configuring texlive-xetex")!!
        assertEquals(PackageProfileInstallStage.INSTALLING, installation.stage)
        assertEquals(80, installation.percent)
        assertEquals("Configuring texlive-xetex", installation.detail)
    }

    @Test fun apkCommitProgressIsNotMisrepresentedAsNetworkByteCounts() {
        val parser = LinuxPackageProgressParser("pdf_fonts")
        assertEquals(PackageProfileInstallStage.DOWNLOADING, parser.parse("NEXA_STAGE=DOWNLOADING")!!.stage)
        val progress = parser.parse("250/1000")!!
        assertEquals(PackageProfileInstallStage.INSTALLING, progress.stage)
        assertEquals(25, progress.percent)
        assertEquals(0L, progress.downloadedBytes)
        assertEquals(0L, progress.totalBytes)
        assertNull(parser.parse("0/0"))
        assertEquals(PackageProfileInstallStage.INSTALLING, parser.parse("(3/4) Installing font-noto-cjk (0_git20220127-r1)")!!.stage)
        assertEquals(PackageProfileInstallStage.VERIFYING, parser.parse("NEXA_STAGE=VERIFYING")!!.stage)
    }

    @Test fun upgradeAndRepairStayInTheObservedMirrorTransaction() {
        val alpine = linuxPackageInstallScript(LinuxDistribution.ALPINE, listOf("qpdf"), true, "#!/bin/sh\nexit 0")
        assertTrue(alpine.contains("/usr/local/bin/eta-apk install --upgrade --progress-fd 2 'qpdf'"))
        assertFalse(alpine.contains("apk add --no-cache --upgrade"))
        assertFalse(alpine.contains("apk fix"))
        val repair = linuxPackageInstallScript(LinuxDistribution.ALPINE, listOf("qpdf"), true, "#!/bin/sh\nexit 0", repair = true)
        assertTrue(repair.contains("apk fix --reinstall --progress-fd 2 'qpdf'"))
        val debian = linuxPackageInstallScript(LinuxDistribution.DEBIAN, listOf("qpdf"), true, "#!/bin/sh\nexit 0")
        assertTrue(debian.contains("/usr/local/bin/eta-apt install --reinstall -o Dpkg::Use-Pty=0 -o APT::Status-Fd=2 'qpdf'"))
        assertFalse(debian.contains("apt-get install -y --reinstall"))
        assertTrue(debian.contains("policy-rc.d"))
    }

    @Test fun longOutputRetainsTheRealTailAndSplitsCarriageReturnProgress() {
        val capture = InstallerOutputCapture(128)
        val lines = mutableListOf<String>()
        val prelude = "x".repeat(1000).toCharArray()
        capture.append(prelude, prelude.size, lines::add)
        val ending = "\r25/100\r50/100\nERROR: No space left on device\n中文".toCharArray()
        capture.append(ending, ending.size, lines::add)
        capture.finish(lines::add)
        assertTrue(capture.text().length <= 128)
        assertTrue(capture.text().endsWith("中文"))
        assertTrue(lines.contains("25/100"))
        assertTrue(lines.contains("50/100"))
        val reason = linuxPackageFailureReason(InstallerCommandResult(1, capture.text()))
        assertTrue(reason.contains("No space left on device"))
    }

    @Test fun diagnosticsKeepProbeFailuresButRedactRepositoryCredentials() {
        val raw = "\u001b[31mERROR https://user:secret@example.test/a?access_token=private failed\u001b[0m"
        val clean = linuxPackageDiagnosticText(raw)
        assertFalse(clean.contains("secret"))
        assertFalse(clean.contains("private"))
        assertFalse(clean.contains('\u001b'))
        val reason = linuxPackageFailureReason(InstallerCommandResult(73, "NEXA_VERSION=fc-list: 2.15\nNEXA_ERROR=Probe failed: fc-match\nNoto font missing"))
        assertTrue(reason.contains("Probe failed: fc-match"))
        assertTrue(reason.contains("Noto font missing"))
        assertFalse(reason.contains("NEXA_VERSION"))
    }
}
