package io.github.mangi.eta.agent.terminal

import java.io.File

internal data class LinuxPackageCheck(val command: String, val versionCommand: String)
internal data class LinuxPackageProfileStatus(val ready: Boolean, val installedVersion: String?, val checkedAt: Long) {
    companion object {
        fun read(marker: File, revision: Int): LinuxPackageProfileStatus = runCatching {
            val fields = marker.readLines().mapNotNull { line -> line.indexOf('=').takeIf { it > 0 }?.let { line.take(it) to line.substring(it + 1) } }.toMap()
            val ready = fields["schema"] == "2" && fields["profile"] == revision.toString() && fields["ready"] == "true" && !fields["version"].isNullOrBlank()
            LinuxPackageProfileStatus(ready, fields["version"], fields["checkedAt"]?.toLongOrNull() ?: 0)
        }.getOrDefault(LinuxPackageProfileStatus(false, null, 0))
        fun fromProbe(exitCode: Int, output: String): LinuxPackageProfileStatus {
            val version = output.lineSequence().filter { it.startsWith("NEXA_VERSION=") }.map { it.removePrefix("NEXA_VERSION=") }.joinToString("; ").takeIf(String::isNotBlank)
            return LinuxPackageProfileStatus(exitCode == 0 && version != null, version, System.currentTimeMillis())
        }
    }
}

internal fun linuxProfileChecks(profile: LinuxPackageProfile, distribution: LinuxDistribution): List<LinuxPackageCheck> {
    val declared = profile.spec(distribution).checks
    if (declared.isNotEmpty()) return declared
    return when (profile.id) {
        "python" -> listOf(LinuxPackageCheck("uv", "uv --version"), LinuxPackageCheck("python3", "python3 --version"))
        "node" -> listOf(LinuxPackageCheck("node", "node --version"), LinuxPackageCheck("npm", "npm --version"))
        "ssh" -> listOf(LinuxPackageCheck("ssh", "ssh -V"), LinuxPackageCheck("sshd", "sshd -V"))
        "kimi" -> listOf(LinuxPackageCheck("kimi", "kimi --version"), LinuxPackageCheck("kimi", "kimi web --help"))
        else -> emptyList()
    }
}

/** Skip warnings before a dotted version; commands without versions retain their first nonblank evidence. */
internal fun linuxProfileVersionEvidenceScript(): String =
    "awk 'first == \"\" && /[^[:space:]]/ { first = ${'$'}0 } " +
        "/[0-9]+[.][0-9]+/ { print; found = 1; exit } " +
        "END { if (!found && first != \"\") print first }'"

/** Each required command and its version/import probe must succeed before writing a marker. */
internal fun linuxProfileProbeScript(profile: LinuxPackageProfile, distribution: LinuxDistribution, writeMarker: Boolean = false): String = buildString {
    append("set -e\nexport LC_ALL=C\n")
    val checks = linuxProfileChecks(profile, distribution)
    if (checks.isEmpty()) { append("exit 64\n"); return@buildString }
    append("nexa_versions=\n")
    checks.forEachIndexed { index, check ->
        append("command -v ").append(shellQuote(check.command)).append(" >/dev/null || { printf 'NEXA_ERROR=Missing command: %s\\n' ").append(shellQuote(check.command)).append("; exit 72; }\n")
        append("nexa_version=$( ").append(check.versionCommand).append(" 2>&1 ) || { printf 'NEXA_ERROR=Probe failed: %s\\n%s\\n' ").append(shellQuote(check.command)).append(" \"${'$'}nexa_version\"; exit 73; }\n")
        append("[ -n \"${'$'}nexa_version\" ] || exit 73\n")
        append("nexa_version=$(printf '%s\\n' \"${'$'}nexa_version\" | ").append(linuxProfileVersionEvidenceScript()).append(" | tr '\\r\\n' '  ' | cut -c1-240)\n")
        append("[ -n \"${'$'}nexa_version\" ] || exit 73\n")
        append("printf 'NEXA_VERSION=%s: %s\\n' ").append(shellQuote(check.command)).append(" \"${'$'}nexa_version\"\n")
        if (index > 0) append("nexa_versions=\"${'$'}nexa_versions; \"\n")
        append("nexa_versions=\"${'$'}{nexa_versions}").append(check.command).append(": ${'$'}nexa_version\"\n")
    }
    if (writeMarker) {
        val marker = "/${profile.markerName}"
        append("nexa_marker_tmp=$(mktemp /tmp/nexa-profile.XXXXXX)\n")
        append("trap 'rm -f \"${'$'}nexa_marker_tmp\"' EXIT\n")
        append("printf 'schema=2\\nprofile=${profile.revision}\\nready=true\\ncheckedAt=%s000\\nversion=%s\\n' \"$(date +%s)\" \"${'$'}nexa_versions\" > \"${'$'}nexa_marker_tmp\"\n")
        append("chmod 0644 \"${'$'}nexa_marker_tmp\"\nmv \"${'$'}nexa_marker_tmp\" ").append(shellQuote(marker)).append('\n')
    }
}

/** A version command alone does not establish that formats, Chinese macros and fonts work. */
internal fun linuxTexSmokeProbe(): String = """
    (
      nexa_tex_dir=${'$'}(mktemp -d /tmp/nexa-tex.XXXXXX) || exit 74
      trap 'rm -rf "${'$'}nexa_tex_dir"' EXIT
      printf '%s\n' '\documentclass{article}' '\usepackage{fontspec}' '\usepackage{xeCJK}' '\setCJKmainfont{Noto Sans CJK SC}' '\begin{document}NEXA 中文\end{document}' > "${'$'}nexa_tex_dir/probe.tex"
      if xelatex -no-shell-escape -interaction=nonstopmode -halt-on-error -output-directory "${'$'}nexa_tex_dir" "${'$'}nexa_tex_dir/probe.tex" > "${'$'}nexa_tex_dir/output.log" 2>&1; then
        [ -s "${'$'}nexa_tex_dir/probe.pdf" ] || exit 75
        printf 'Chinese XeLaTeX PDF verified\n'
      else
        tail -n 16 "${'$'}nexa_tex_dir/output.log"
        exit 75
      fi
    )
""".trimIndent()
