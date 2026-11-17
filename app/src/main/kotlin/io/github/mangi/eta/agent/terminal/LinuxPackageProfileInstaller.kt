package io.github.mangi.eta.agent.terminal

import android.content.Context
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext

/** Target is fixed before waiting for the install lock; switching selection cannot retarget it. */
internal class LinuxPackageProfileInstaller(
    private val context: Context,
    private val distribution: LinuxDistribution,
    private val profile: LinuxPackageProfile,
    val backend: LinuxExecutionBackend = LinuxEnvironmentSettingsRepository.backend(context, distribution),
) {
    val rootfs: File = LinuxEnvironmentPaths.rootfsDir(context, distribution, backend)
    private val managedToolInstaller = PinnedLinuxToolInstaller(context)
    fun hasInstallationMarker(): Boolean = File(rootfs, profile.markerName).isFile
    fun isReady(): Boolean = linuxPackageProfileReady(rootfs, profile)
    suspend fun cachedStatus(): LinuxPackageProfileStatus = withContext(Dispatchers.IO) { LinuxPackageProfileStatus.read(File(rootfs, profile.markerName), profile.revision) }
    suspend fun status(): LinuxPackageProfileStatus = withContext(Dispatchers.IO) { probe(profile) }

    suspend fun install(force: Boolean = false, repair: Boolean = false, onProgress: suspend (PackageProfileInstallProgress) -> Unit = {}): PackageProfileInstallResult {
        // A global package lock can be held by a different screen/toolchain installation.
        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.WAITING, profileId = profile.id))
        installMutex.lock()
        return try {
            if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath) || !File(rootfs, AlpineEnvironmentPaths.COMMON_TOOLS_MARKER).isFile)
                PackageProfileInstallResult.EnvironmentNotReady
            else withContext(Dispatchers.IO) { installLocked(profile, force, repair, onProgress, mutableSetOf()) }
        } finally { installMutex.unlock() }
    }

    private suspend fun probe(target: LinuxPackageProfile): LinuxPackageProfileStatus {
        if (!LinuxEnvironmentPaths.rootfsReady(rootfs.absolutePath) || !target.specs.containsKey(distribution))
            return LinuxPackageProfileStatus(false, null, System.currentTimeMillis())
        val result = runGuest(linuxProfileProbeScript(target, distribution), VERIFY_TIMEOUT_SECONDS)
        val status = LinuxPackageProfileStatus.fromProbe(result.exitCode, result.output)
        // Live status is read-only; it cannot race an installation marker replacement.
        return status
    }

    private suspend fun installLocked(target: LinuxPackageProfile, force: Boolean, repair: Boolean, onProgress: suspend (PackageProfileInstallProgress) -> Unit, visiting: MutableSet<String>): PackageProfileInstallResult {
        if (!visiting.add(target.id)) return PackageProfileInstallResult.Failed(PackageProfileInstallStage.CHECKING, target.id, reason = "Dependency cycle: ${target.id}")
        var currentStage = PackageProfileInstallStage.CHECKING
        suspend fun report(stage: PackageProfileInstallStage, detail: String? = null) {
            currentStage = stage
            onProgress(PackageProfileInstallProgress(stage, profileId = target.id, detail = detail))
        }
        suspend fun execute(command: String, initial: PackageProfileInstallStage, timeout: Long = INSTALL_TIMEOUT_SECONDS): PackageProfileInstallResult.Failed? {
            report(initial)
            val parser = LinuxPackageProgressParser(target.id, initial)
            val result = runGuest(command, timeout) { line ->
                parser.parse(line)?.let { parsed -> currentStage = parsed.stage; onProgress(parsed) }
            }
            return if (result.exitCode == 0) null else PackageProfileInstallResult.Failed(currentStage, target.id, result.exitCode, linuxPackageFailureReason(result))
        }
        try {
            report(PackageProfileInstallStage.CHECKING)
            if (!target.specs.containsKey(distribution)) return PackageProfileInstallResult.Failed(PackageProfileInstallStage.CHECKING, target.id, reason = "Unsupported distribution: ${distribution.wireName}")
            // Existing command readiness never implies that it is the newest upstream release.
            val alreadyReady = !force && probe(target).ready
            target.dependsOn?.let { dependency ->
                // Upgrading the requested capability must not reinstall a large, healthy dependency.
                val result = installLocked(dependency, false, false, onProgress, visiting)
                if (result != PackageProfileInstallResult.Installed && result != PackageProfileInstallResult.AlreadyReady) return result
            }
            if (alreadyReady) {
                // A prior release or interrupted install may have working commands but no current marker.
                // Refresh that marker so the cache and visible live status agree on the next screen visit.
                report(PackageProfileInstallStage.VERIFYING)
                val verified = runGuest(linuxProfileProbeScript(target, distribution, writeMarker = true), VERIFY_TIMEOUT_SECONDS)
                if (!LinuxPackageProfileStatus.fromProbe(verified.exitCode, verified.output).ready)
                    return PackageProfileInstallResult.Failed(PackageProfileInstallStage.VERIFYING, target.id, verified.exitCode, linuxPackageFailureReason(verified))
                report(PackageProfileInstallStage.COMPLETE)
                return PackageProfileInstallResult.AlreadyReady
            }
            val spec = target.spec(distribution)
            // Preserve a previous marker until the replacement has passed its live probes.
            if (spec.packages.isNotEmpty()) execute(packageInstallScript(spec, force, repair), PackageProfileInstallStage.DOWNLOADING)?.let { return it }
            (listOfNotNull(spec.managedTool) + spec.managedTools).distinct().forEach { tool ->
                val installed = managedToolInstaller.install(tool, distribution, rootfs,
                    onStage = { stage -> report(stage, tool.name) },
                    onProgress = { downloaded, total ->
                        currentStage = PackageProfileInstallStage.DOWNLOADING
                        onProgress(PackageProfileInstallProgress(PackageProfileInstallStage.DOWNLOADING, downloaded, total, target.id, tool.name))
                    })
                if (!installed) return PackageProfileInstallResult.Failed(currentStage, target.id, reason = "${tool.name}: download, checksum or activation failed")
            }
            val setup = setupScript(target, spec)
            if (setup.isNotBlank()) execute(setup, PackageProfileInstallStage.INSTALLING)?.let { return it }
            report(PackageProfileInstallStage.VERIFYING)
            val verified = runGuest(linuxProfileProbeScript(target, distribution, writeMarker = true), VERIFY_TIMEOUT_SECONDS)
            AndroidAgentLogger.info("Package profile distribution=${distribution.wireName} backend=${backend.wireName} profile=${target.id} exitCode=${verified.exitCode}")
            if (!LinuxPackageProfileStatus.fromProbe(verified.exitCode, verified.output).ready)
                return PackageProfileInstallResult.Failed(PackageProfileInstallStage.VERIFYING, target.id, verified.exitCode, linuxPackageFailureReason(verified))
            report(PackageProfileInstallStage.COMPLETE)
            return PackageProfileInstallResult.Installed
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: java.io.IOException) {
            return PackageProfileInstallResult.Failed(currentStage, target.id, reason = linuxPackageDiagnosticText(error.message.orEmpty()).ifBlank { error.javaClass.simpleName })
        } finally { visiting.remove(target.id) }
    }

    private suspend fun runGuest(command: String, timeout: Long, onOutput: (suspend (String) -> Unit)? = null) = InstallerShellRunner.run(command, timeout, distribution.terminalEnvironment, rootfs.absolutePath,
        identity = if (backend == LinuxExecutionBackend.PROOT) "user" else "root", onOutput = onOutput)

    private fun packageInstallScript(spec: LinuxPackageSpec, force: Boolean, repair: Boolean): String {
        val kind = if (distribution == LinuxDistribution.ALPINE) LinuxSourceSettings.APK else LinuxSourceSettings.APT
        val sources = LinuxSourceSettings.ordered(context, distribution, kind)
        val script = when (distribution) {
            LinuxDistribution.ALPINE -> AlpineEnvironmentInstaller.apkMirrorScript(sources.filter { it.kind == LinuxSourceSettings.APK }.map { it.archiveUrl })
            LinuxDistribution.DEBIAN -> DebianEnvironmentInstaller.aptMirrorScript(sources.filter { it.kind == LinuxSourceSettings.APT }.map { DebianAptMirror(it.id, it.archiveUrl, it.securityUrl ?: it.archiveUrl) })
        }
        return linuxPackageInstallScript(distribution, spec.packages, force, script, repair)
    }

    private fun setupScript(target: LinuxPackageProfile, spec: LinuxPackageSpec): String {
        val script = spec.setupScript ?: return ""
        if (target.id == "python") {
            val mirrors = LinuxSourceSettings.sourceUrls(context, distribution, LinuxSourceSettings.PYTHON)
            return buildString {
                append("set -e\nfor nexa_source in ").append(mirrors.joinToString(" ") { shellQuote(it) }).append("; do\n")
                append("export UV_PYTHON_INSTALL_MIRROR=\"${'$'}nexa_source\"\nif sh -e -c ").append(shellQuote(script)).append("; then exit 0; fi\ndone\n")
                append("unset UV_PYTHON_INSTALL_MIRROR\n").append(script).append('\n')
            }
        }
        val npm = target.id in setOf("kimi", "node_dev")
        val pypi = target.id in setOf("python_dev", "duckdb")
        val kind = if (npm) LinuxSourceSettings.NPM else if (pypi) LinuxSourceSettings.PYPI else null
        if (kind == null) return "set -e\n$script"
        val defaults = if (npm) listOf("https://registry.npmmirror.com", "https://registry.npmjs.org") else listOf("https://pypi.tuna.tsinghua.edu.cn/simple", "https://pypi.org/simple")
        val urls = (LinuxSourceSettings.sourceUrls(context, distribution, kind) + defaults).distinct()
        val action = if (target.id == "kimi") "npm install -g --prefix /usr/local @moonshot-ai/kimi-code@latest" else script
        val variable = if (npm) "npm_config_registry" else "UV_DEFAULT_INDEX"
        return buildString {
            append("set -e\nfor nexa_source in ").append(urls.joinToString(" ") { shellQuote(it) }).append("; do\n")
            append("export ").append(variable).append("=\"${'$'}nexa_source\"\n")
            append("if sh -e -c ").append(shellQuote(action)).append("; then exit 0; fi\ndone\nexit 1\n")
        }
    }

    companion object {
        // TeX/font triggers under PRoot can be much slower than the package download itself.
        private const val INSTALL_TIMEOUT_SECONDS = 3600L
        private const val VERIFY_TIMEOUT_SECONDS = 300L
        private val installMutex = Mutex()
    }
}

/** Preserve an existing service policy and restore it even when APT fails. */
internal fun debianNoServiceStartScript(): String = """
    nexa_policy_backup=
    if [ -e /usr/sbin/policy-rc.d ]; then
      nexa_policy_backup=${'$'}(mktemp)
      cp -p /usr/sbin/policy-rc.d "${'$'}nexa_policy_backup"
    fi
    trap 'if [ -n "${'$'}nexa_policy_backup" ]; then cp -p "${'$'}nexa_policy_backup" /usr/sbin/policy-rc.d; rm -f "${'$'}nexa_policy_backup"; else rm -f /usr/sbin/policy-rc.d; fi' EXIT
    printf '#!/bin/sh\nexit 101\n' > /usr/sbin/policy-rc.d
    chmod 0755 /usr/sbin/policy-rc.d
""".trimIndent() + "\n"
