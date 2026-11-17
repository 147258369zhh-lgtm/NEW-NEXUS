package io.github.mangi.eta.agent.terminal

/** Package-manager output is evidence, not an estimate based on how long a task has run. */
internal class LinuxPackageProgressParser(private val profileId: String, initialStage: PackageProfileInstallStage = PackageProfileInstallStage.CHECKING) {
    var stage: PackageProfileInstallStage = initialStage
        private set

    fun parse(raw: String): PackageProfileInstallProgress? {
        val line = linuxPackageDiagnosticText(raw, 320)
        if (line.isBlank()) return null
        if (line.startsWith("NEXA_STAGE=")) {
            stage = runCatching { PackageProfileInstallStage.valueOf(line.substringAfter('=')) }.getOrNull() ?: return null
            return PackageProfileInstallProgress(stage, profileId = profileId)
        }
        val apt = Regex("^(dlstatus|pmstatus):([^:]*):([0-9.]+):(.*)$").matchEntire(line)
        if (apt != null) {
            stage = if (apt.groupValues[1] == "dlstatus") PackageProfileInstallStage.DOWNLOADING else PackageProfileInstallStage.INSTALLING
            return PackageProfileInstallProgress(stage, profileId = profileId, detail = apt.groupValues[4],
                percent = apt.groupValues[3].toDoubleOrNull()?.toInt()?.coerceIn(0, 100))
        }
        val apk = Regex("^(\\d+)/(\\d+)$").matchEntire(line)
        if (apk != null) {
            val done = apk.groupValues[1].toLongOrNull() ?: return null
            val total = apk.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
            // APK progress-fd reports installed bytes plus package counts, not network bytes.
            stage = PackageProfileInstallStage.INSTALLING
            return PackageProfileInstallProgress(stage, profileId = profileId, percent = (done.toDouble() * 100 / total).toInt().coerceIn(0, 100))
        }
        val next = when {
            line.startsWith("Get:") || line.startsWith("fetch ") || line.startsWith("Downloading ") -> PackageProfileInstallStage.DOWNLOADING
            line.contains("Verifying", ignoreCase = true) || line.contains("Checking SHA", ignoreCase = true) -> PackageProfileInstallStage.CHECKSUM
            Regex("^\\(\\d+/\\d+\\) (Installing|Upgrading|Reinstalling|Purging|Downgrading)").containsMatchIn(line) ||
                line.startsWith("Unpacking ") || line.startsWith("Setting up ") || line.startsWith("Processing triggers ") ||
                line.startsWith("Executing ") -> PackageProfileInstallStage.INSTALLING
            else -> stage
        }
        stage = next
        return PackageProfileInstallProgress(stage, profileId = profileId, detail = line)
    }
}

/** Do not display registry credentials or control characters from configurable repositories. */
internal fun linuxPackageDiagnosticText(value: String, limit: Int = 1200): String = value
    .replace(Regex("\\u001B\\[[0-?]*[ -/]*[@-~]"), "")
    .replace(Regex("(https?://)[^\\s/@]+:[^\\s/@]+@"), "$1[redacted]@")
    .replace(Regex("(?i)(token|password|api_key|access_token)=([^\\s&]+)"), "$1=[redacted]")
    .filter { it == '\n' || it == '\t' || !it.isISOControl() }
    .trim().takeLast(limit)

internal fun linuxPackageFailureReason(result: InstallerCommandResult): String {
    val lines = result.output.lineSequence().map { linuxPackageDiagnosticText(it.removePrefix("NEXA_ERROR="), 400) }
        .filter { it.isNotBlank() && !it.startsWith("NEXA_") }.toList()
    val errors = lines.filter { line ->
        Regex("(?i)(error|failed|unable|cannot|no space|permission denied|not found|unsatisfiable|breaks:|conflicts:|requires:|missing|not available)").containsMatchIn(line)
    }
    return (if (errors.isNotEmpty()) errors.takeLast(4) else lines.takeLast(4)).joinToString("\n").takeLast(1200)
}

/** Force is passed through the same mirror/recovery transaction instead of a second unobserved command. */
internal fun linuxPackageInstallScript(
    distribution: LinuxDistribution,
    packages: List<String>,
    force: Boolean,
    mirrorScript: String,
    repair: Boolean = false,
): String = buildString {
    val alpine = distribution == LinuxDistribution.ALPINE
    val helper = if (alpine) "/usr/local/bin/eta-apk" else "/usr/local/bin/eta-apt"
    val arguments = packages.joinToString(" ", transform = ::shellQuote)
    append("set -e\nexport LC_ALL=C DEBIAN_FRONTEND=noninteractive\n")
    if (!alpine) append(debianNoServiceStartScript())
    append("printf %s ").append(shellQuote(mirrorScript)).append(" > ").append(helper).append("\nchmod 0755 ").append(helper).append('\n')
    append("printf 'NEXA_STAGE=DOWNLOADING\\n'\n")
    append(helper).append(" install ")
    if (force) append(if (alpine) "--upgrade " else "--reinstall ")
    if (alpine) append("--progress-fd 2 ") else append("-o Dpkg::Use-Pty=0 -o APT::Status-Fd=2 ")
    append(arguments).append('\n')
    if (alpine && repair) {
        append("printf 'NEXA_STAGE=INSTALLING\\n'\napk fix --reinstall --progress-fd 2 ").append(arguments).append('\n')
    }
}
