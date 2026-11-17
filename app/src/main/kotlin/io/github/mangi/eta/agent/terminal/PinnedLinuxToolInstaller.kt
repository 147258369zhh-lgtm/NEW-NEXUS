package io.github.mangi.eta.agent.terminal

import android.content.Context
import android.os.Build
import io.github.mangi.eta.core.AndroidAgentLogger
import java.io.File
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

internal enum class ManagedLinuxTool {
    UV,
    NODE,
    GO,
    RUST,
    JDK,
    MAVEN,
    GRADLE,
}

internal class PinnedLinuxToolInstaller(
    private val context: Context,
) {
    private val downloader = VerifiedArtifactDownloader()

    suspend fun install(
        tool: ManagedLinuxTool,
        distribution: LinuxDistribution,
        rootfs: File,
        onStage: suspend (PackageProfileInstallStage) -> Unit = {},
        onProgress: suspend (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Boolean {
        val artifact = PinnedLinuxToolArtifacts.artifactFor(
            tool = tool,
            distribution = distribution,
            abis = Build.SUPPORTED_ABIS.toList(),
        )?.let { LinuxSourceSettings.resolveArtifact(context, distribution, it, when(tool) {
            ManagedLinuxTool.UV -> LinuxSourceSettings.UV; ManagedLinuxTool.NODE -> LinuxSourceSettings.NODE
            ManagedLinuxTool.GO -> LinuxSourceSettings.GO; ManagedLinuxTool.RUST -> LinuxSourceSettings.RUST_DIST; ManagedLinuxTool.JDK -> LinuxSourceSettings.JDK
            ManagedLinuxTool.MAVEN -> LinuxSourceSettings.MAVEN; ManagedLinuxTool.GRADLE -> LinuxSourceSettings.GRADLE
        }) } ?: return false
        val archive = File(context.cacheDir, "linux-installer/profiles/${artifact.fileName}.${java.util.UUID.randomUUID()}.download")
        return try {
            onProgress(0, artifact.sizeBytes)
            if (!downloader.download(artifact, archive,
                    onVerifying = { onStage(PackageProfileInstallStage.CHECKSUM) }, onProgress = onProgress)) return false
            coroutineContext.ensureActive()
            onStage(PackageProfileInstallStage.INSTALLING)
            activate(tool, artifact, archive, rootfs)
        } catch (_: java.io.IOException) {
            false
        } catch (_: IllegalArgumentException) {
            false
        } finally {
            archive.delete()
        }
    }

    private suspend fun activate(
        tool: ManagedLinuxTool,
        artifact: VerifiedArtifact,
        archive: File,
        rootfs: File,
    ): Boolean {
        if (LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            return activateRootless(tool, artifact, archive, rootfs)
        }
        val command = when (tool) {
            ManagedLinuxTool.UV -> uvActivationCommand(artifact, archive, rootfs)
            ManagedLinuxTool.NODE -> nodeActivationCommand(artifact, archive, rootfs)
            ManagedLinuxTool.GO, ManagedLinuxTool.RUST, ManagedLinuxTool.JDK, ManagedLinuxTool.MAVEN, ManagedLinuxTool.GRADLE -> return activateSdk(tool, artifact, archive, rootfs)
        }
        val result = InstallerShellRunner.run(
            command = command,
            timeoutSeconds = 180,
            environment = TerminalEnvironment.ANDROID,
        )
        AndroidAgentLogger.info(
            "Managed Linux tool action=activate tool=${tool.name.lowercase()} version=${artifact.version} " +
                "outcome=${if (result.exitCode == 0) "succeeded" else "failed"} " +
                "exitCode=${result.exitCode} outputChars=${result.output.length}",
        )
        return result.exitCode == 0
    }

    private suspend fun activateRootless(tool: ManagedLinuxTool, artifact: VerifiedArtifact, archive: File, rootfs: File): Boolean {
        if(tool in setOf(ManagedLinuxTool.GO, ManagedLinuxTool.RUST, ManagedLinuxTool.JDK, ManagedLinuxTool.MAVEN, ManagedLinuxTool.GRADLE)) return activateSdk(tool, artifact, archive, rootfs)
        val installVersion = artifact.version + "-" + java.util.UUID.randomUUID()
        val name = tool.name.lowercase()
        val versions = File(rootfs, "opt/eta/$name")
        val target = File(versions, installVersion)
        val staging = File(rootfs, "opt/eta/$name.installing-$installVersion")
        try {
            if (staging.exists() && !staging.deleteRecursively()) return false
            RootlessLinuxInstaller.extract(archive, staging, xz = tool == ManagedLinuxTool.NODE, stripComponents = 1)
            require(versions.mkdirs() || versions.isDirectory)
            if (!staging.renameTo(target)) return false
            val localBin = File(rootfs, "usr/local/bin").apply { mkdirs() }
            when (tool) {
                ManagedLinuxTool.UV -> listOf("uv", "uvx").forEach { command ->
                    val temporary = File(localBin, "$command.$installVersion.tmp")
                    temporary.writeText(uvWrapper(installVersion, command)); require(temporary.setExecutable(true, false))
                    java.nio.file.Files.move(temporary.toPath(), File(localBin, command).toPath(), java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
                ManagedLinuxTool.GO, ManagedLinuxTool.RUST, ManagedLinuxTool.JDK, ManagedLinuxTool.MAVEN, ManagedLinuxTool.GRADLE -> return false
                ManagedLinuxTool.NODE -> listOf("node", "npm", "npx").forEach { command ->
                    val link = File(localBin, command).toPath()
                    val temporaryLink = File(localBin, "$command.$installVersion.tmp").toPath()
                    java.nio.file.Files.createSymbolicLink(temporaryLink, java.nio.file.Paths.get("../../../opt/eta/node/${installVersion}/bin/$command"))
                    java.nio.file.Files.move(temporaryLink, link, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            return true
        } finally {
            if (staging.exists()) staging.deleteRecursively()
        }
    }

    private suspend fun activateSdk(tool: ManagedLinuxTool, artifact: VerifiedArtifact, archive: File, rootfs: File): Boolean {
        val name = tool.name.lowercase()
        val installVersion = artifact.version + "-" + java.util.UUID.randomUUID()
        val staging = File(rootfs, "opt/eta/$name.installing-$installVersion")
        val guestTarget = "/opt/eta/$name/$installVersion"
        val guestStaging = "/opt/eta/$name.installing-$installVersion"
        if(LinuxEnvironmentPaths.backendOf(rootfs.absolutePath) == LinuxExecutionBackend.PROOT) {
            if(staging.exists() && !staging.deleteRecursively()) return false
            if(tool == ManagedLinuxTool.GRADLE) LinuxSdkZipExtractor.extract(archive, staging)
            else RootlessLinuxInstaller.extract(archive, staging, xz = tool == ManagedLinuxTool.RUST, stripComponents = 1)
        } else {
            val extract = InstallerShellRunner.run("""
                ${AndroidBusyBox.discoveryScript()}
                [ -n "${'$'}eta_busybox" ] || exit 127
                "${'$'}eta_busybox" rm -rf ${shellQuote(staging.absolutePath)}
                "${'$'}eta_busybox" mkdir -p ${shellQuote(staging.absolutePath)} || exit 66
                ${if(tool == ManagedLinuxTool.GRADLE) "\"${'$'}eta_busybox\" unzip -q ${shellQuote(archive.absolutePath)} -d ${shellQuote(staging.absolutePath + ".zip")} && \"${'$'}eta_busybox\" mv ${shellQuote(staging.absolutePath + ".zip/gradle-" + artifact.version)}/* ${shellQuote(staging.absolutePath)} && \"${'$'}eta_busybox\" rm -rf ${shellQuote(staging.absolutePath + ".zip")} || exit 67" else "\"${'$'}eta_busybox\" tar ${if(tool == ManagedLinuxTool.RUST) "-xJf" else "-xzf"} ${shellQuote(archive.absolutePath)} -C ${shellQuote(staging.absolutePath)} --strip-components=1 || exit 67"}
            """.trimIndent(), 180, TerminalEnvironment.ANDROID)
            if(extract.exitCode != 0) return false
        }
        val binaries = when(tool) {
            ManagedLinuxTool.GO -> listOf("go", "gofmt")
            ManagedLinuxTool.RUST -> listOf("rustc", "cargo", "rustdoc")
            ManagedLinuxTool.JDK -> listOf("java", "javac", "jar", "javadoc", "jshell", "jlink", "jpackage")
            ManagedLinuxTool.MAVEN -> listOf("mvn", "mvnDebug")
            ManagedLinuxTool.GRADLE -> listOf("gradle")
            else -> return false
        }
        val installCommand = if(tool == ManagedLinuxTool.RUST) "sh $guestStaging/install.sh --prefix=$guestTarget --without=rust-docs --disable-ldconfig || exit 70" else "mkdir -p /opt/eta/$name && mv $guestStaging $guestTarget || exit 70"
        val testCommand = when(tool) { ManagedLinuxTool.GO -> "$guestTarget/bin/go version"; ManagedLinuxTool.RUST -> "$guestTarget/bin/rustc --version && $guestTarget/bin/cargo --version"; ManagedLinuxTool.MAVEN -> javaBuildEnvironment() + "\n$guestTarget/bin/mvn --version"; ManagedLinuxTool.GRADLE -> javaBuildEnvironment() + "\n$guestTarget/bin/gradle --version --no-daemon"; else -> "$guestTarget/bin/java --version && $guestTarget/bin/javac --version" }
        val wrappers = binaries.joinToString("\n") { command ->
            val env = when(tool) {
                ManagedLinuxTool.GO -> "export GOROOT=$guestTarget\n"
                ManagedLinuxTool.JDK -> "export JAVA_HOME=$guestTarget\n"
                ManagedLinuxTool.RUST -> "export CARGO_HOME=/opt/eta/cargo\nexport PATH=$guestTarget/bin:${'$'}PATH\n"
                ManagedLinuxTool.MAVEN, ManagedLinuxTool.GRADLE -> javaBuildEnvironment() + "\n"
                else -> ""
            }
            "printf %s ${shellQuote("#!/bin/sh\n" + env + "exec $guestTarget/bin/$command \"${'$'}@\"\n")} > /usr/local/bin/$command.$installVersion.tmp && chmod 0755 /usr/local/bin/$command.$installVersion.tmp && mv -f /usr/local/bin/$command.$installVersion.tmp /usr/local/bin/$command || exit 71"
        }
        val environment = if(rootfs.absolutePath.contains("alpine")) TerminalEnvironment.ALPINE else TerminalEnvironment.DEBIAN
        val verified = InstallerShellRunner.run("$installCommand\nchmod 0755 ${binaries.joinToString(" ") { "$guestTarget/bin/$it" }} || exit 71\n$testCommand || exit 72", 300, environment, rootfs.absolutePath)
        if(verified.exitCode != 0 || !verified.output.contains(artifact.version.substringBeforeLast(".0.3"))) return false
        val activated = InstallerShellRunner.run("mkdir -p /usr/local/bin\n$wrappers\nrm -rf $guestStaging", 60, environment, rootfs.absolutePath)
        return activated.exitCode == 0
    }

    private fun javaBuildEnvironment(): String = """
        nexa_java_home=${'$'}(sed -n 's/^export JAVA_HOME=//p' /usr/local/bin/java 2>/dev/null | head -n 1)
        if [ -z "${'$'}nexa_java_home" ]; then
          nexa_javac=${'$'}(command -v javac) || exit 73
          nexa_java_home=${'$'}(dirname "${'$'}(dirname "${'$'}(readlink -f "${'$'}nexa_javac")")")
        fi
        [ -x "${'$'}nexa_java_home/bin/java" ] || exit 73
        export JAVA_HOME="${'$'}nexa_java_home"
    """.trimIndent()

    private fun uvActivationCommand(
        artifact: VerifiedArtifact,
        archive: File,
        rootfs: File,
    ): String {
        val installVersion = artifact.version + "-" + java.util.UUID.randomUUID()
        val target = File(rootfs, "opt/eta/uv/${installVersion}")
        val staging = File(rootfs, "opt/eta/uv.installing-$installVersion")
        val versionsRoot = target.parentFile!!
        val uvWrapper = uvWrapper(installVersion, "uv")
        val uvxWrapper = uvWrapper(installVersion, "uvx")
        val uvPath = File(rootfs, "usr/local/bin/uv")
        val uvxPath = File(rootfs, "usr/local/bin/uvx")
        return """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            eta_archive=${shellQuote(archive.absolutePath)}
            eta_staging=${shellQuote(staging.absolutePath)}
            eta_target=${shellQuote(target.absolutePath)}
            "${'$'}eta_busybox" rm -rf "${'$'}eta_staging"
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_staging" || exit 66
            "${'$'}eta_busybox" tar -xzf "${'$'}eta_archive" -C "${'$'}eta_staging" --strip-components=1 || exit 67
            "${'$'}eta_busybox" mkdir -p ${shellQuote(versionsRoot.parentFile!!.absolutePath)} ${shellQuote(File(rootfs, "usr/local/bin").absolutePath)} || exit 66
            "${'$'}eta_busybox" mkdir -p ${shellQuote(versionsRoot.absolutePath)} || exit 66
            "${'$'}eta_busybox" mv "${'$'}eta_staging" "${'$'}eta_target" || exit 69
            "${'$'}eta_busybox" chmod 0755 "${'$'}eta_target/uv" "${'$'}eta_target/uvx" || exit 70
            printf %s ${shellQuote(uvWrapper)} > ${shellQuote(uvPath.absolutePath + "." + installVersion + ".tmp")} || exit 71
            printf %s ${shellQuote(uvxWrapper)} > ${shellQuote(uvxPath.absolutePath + "." + installVersion + ".tmp")} || exit 71
            "${'$'}eta_busybox" chmod 0755 ${shellQuote(uvPath.absolutePath + "." + installVersion + ".tmp")} ${shellQuote(uvxPath.absolutePath + "." + installVersion + ".tmp")} || exit 71
            "${'$'}eta_busybox" mv -f ${shellQuote(uvPath.absolutePath + "." + installVersion + ".tmp")} ${shellQuote(uvPath.absolutePath)} || exit 71
            "${'$'}eta_busybox" mv -f ${shellQuote(uvxPath.absolutePath + "." + installVersion + ".tmp")} ${shellQuote(uvxPath.absolutePath)} || exit 71
        """.trimIndent()
    }

    private fun uvWrapper(version: String, command: String): String = """
        #!/bin/sh
        export UV_PYTHON_INSTALL_DIR=/opt/eta/python
        export UV_PYTHON_BIN_DIR=/usr/local/bin
        export UV_PYTHON_INSTALL_BIN=1
        export UV_TOOL_DIR=/opt/eta/uv-tools
        export UV_TOOL_BIN_DIR=/usr/local/bin
        exec /opt/eta/uv/$version/$command "${'$'}@"
    """.trimIndent() + "\n"

    private fun nodeActivationCommand(
        artifact: VerifiedArtifact,
        archive: File,
        rootfs: File,
    ): String {
        val installVersion = artifact.version + "-" + java.util.UUID.randomUUID()
        val target = File(rootfs, "opt/eta/node/${installVersion}")
        val staging = File(rootfs, "opt/eta/node.installing-$installVersion")
        val versionsRoot = target.parentFile!!
        val localBin = File(rootfs, "usr/local/bin")
        return """
            ${AndroidBusyBox.discoveryScript()}
            [ -n "${'$'}eta_busybox" ] || exit 127
            eta_archive=${shellQuote(archive.absolutePath)}
            eta_staging=${shellQuote(staging.absolutePath)}
            eta_target=${shellQuote(target.absolutePath)}
            "${'$'}eta_busybox" rm -rf "${'$'}eta_staging"
            "${'$'}eta_busybox" mkdir -p "${'$'}eta_staging" || exit 66
            "${'$'}eta_busybox" tar -xJf "${'$'}eta_archive" -C "${'$'}eta_staging" --strip-components=1 || exit 67
            "${'$'}eta_busybox" mkdir -p ${shellQuote(versionsRoot.parentFile!!.absolutePath)} ${shellQuote(localBin.absolutePath)} || exit 66
            "${'$'}eta_busybox" mkdir -p ${shellQuote(versionsRoot.absolutePath)} || exit 66
            "${'$'}eta_busybox" mv "${'$'}eta_staging" "${'$'}eta_target" || exit 69
            "${'$'}eta_busybox" ln -sfn ${shellQuote("/opt/eta/node/${installVersion}/bin/node")} ${shellQuote(File(localBin, "node." + installVersion + ".tmp").absolutePath)} || exit 71
            "${'$'}eta_busybox" mv -f ${shellQuote(File(localBin, "node." + installVersion + ".tmp").absolutePath)} ${shellQuote(File(localBin, "node").absolutePath)} || exit 71
            "${'$'}eta_busybox" ln -sfn ${shellQuote("/opt/eta/node/${installVersion}/bin/npm")} ${shellQuote(File(localBin, "npm." + installVersion + ".tmp").absolutePath)} || exit 71
            "${'$'}eta_busybox" mv -f ${shellQuote(File(localBin, "npm." + installVersion + ".tmp").absolutePath)} ${shellQuote(File(localBin, "npm").absolutePath)} || exit 71
            "${'$'}eta_busybox" ln -sfn ${shellQuote("/opt/eta/node/${installVersion}/bin/npx")} ${shellQuote(File(localBin, "npx." + installVersion + ".tmp").absolutePath)} || exit 71
            "${'$'}eta_busybox" mv -f ${shellQuote(File(localBin, "npx." + installVersion + ".tmp").absolutePath)} ${shellQuote(File(localBin, "npx").absolutePath)} || exit 71
        """.trimIndent()
    }
}

internal object PinnedLinuxToolArtifacts {
    private const val UV_VERSION = "0.13.8"
    private const val NODE_VERSION = "26.13.1"
    private const val GITHUB_PROXY_PREFIX = "https://gh-proxy.com/"

    fun artifactFor(
        tool: ManagedLinuxTool,
        distribution: LinuxDistribution,
        abis: List<String>,
    ): VerifiedArtifact? = when (tool) {
        ManagedLinuxTool.UV -> uvArtifact(distribution, abis)
        ManagedLinuxTool.NODE -> nodeArtifact(distribution, abis)
        ManagedLinuxTool.GO, ManagedLinuxTool.RUST, ManagedLinuxTool.JDK, ManagedLinuxTool.MAVEN, ManagedLinuxTool.GRADLE -> LinuxSdkArtifacts.artifactFor(tool, distribution, abis)
    }

    private fun uvArtifact(
        distribution: LinuxDistribution,
        abis: List<String>,
    ): VerifiedArtifact? {
        val libc = if (distribution == LinuxDistribution.ALPINE) "musl" else "gnu"
        return abis.firstNotNullOfOrNull { abi ->
            when (abi) {
                "arm64-v8a" -> uvArtifact(
                    architecture = "aarch64",
                    libc = libc,
                    sha256 = if (libc == "musl") {
                        "0c6f8593fcf1eadfc60e663b685c408267559d7c5d61c689bbb9f3a7e369ba32"
                    } else {
                        "74f2f5c6024d397481db228db8fc25eed420db192243f88438c118e7e013e585"
                    },
                    sizeBytes = if (libc == "musl") 17_171_170L else 16_065_638L,
                )
                "x86_64" -> uvArtifact(
                    architecture = "x86_64",
                    libc = libc,
                    sha256 = if (libc == "musl") {
                        "61fb26dabc4f0cf1d050fc6c67d40e43e4fae2994af21ebc1e050b233bf2b5ed"
                    } else {
                        "5acfd9177cfecf0bf87578f059728215ccc884388988f55f7a65041b57387d91"
                    },
                    sizeBytes = if (libc == "musl") 18_577_654L else 16_855_069L,
                )
                else -> null
            }
        }
    }

    private fun uvArtifact(
        architecture: String,
        libc: String,
        sha256: String,
        sizeBytes: Long,
    ): VerifiedArtifact {
        val fileName = "uv-$architecture-unknown-linux-$libc.tar.gz"
        val officialUrl = "https://github.com/astral-sh/uv/releases/download/$UV_VERSION/$fileName"
        return VerifiedArtifact(
            id = "uv-$architecture-$libc",
            version = UV_VERSION,
            fileName = fileName,
            url = officialUrl,
            sha256 = sha256,
            sizeBytes = sizeBytes,
            preferredUrls = listOf(GITHUB_PROXY_PREFIX + officialUrl),
        )
    }

    private fun nodeArtifact(
        distribution: LinuxDistribution,
        abis: List<String>,
    ): VerifiedArtifact? {
        if (distribution != LinuxDistribution.DEBIAN) return null
        return abis.firstNotNullOfOrNull { abi ->
            when (abi) {
                "arm64-v8a" -> nodeArtifact(
                    architecture = "arm64",
                    sha256 = "89e1853138db51e8b82d3e98ba9c0d3f2956a28e5e55ea1b7e12d25aae92d5dd",
                    sizeBytes = 33_053_848L,
                )
                "x86_64" -> nodeArtifact(
                    architecture = "x64",
                    sha256 = "5c063e46bab0fbc1c17cc445d7ff129cbdcd0471be6ed83b6ab864f8143bc864",
                    sizeBytes = 34_093_052L,
                )
                else -> null
            }
        }
    }

    private fun nodeArtifact(
        architecture: String,
        sha256: String,
        sizeBytes: Long,
    ): VerifiedArtifact {
        val fileName = "node-v$NODE_VERSION-linux-$architecture.tar.xz"
        return VerifiedArtifact(
            id = "node-$architecture",
            version = NODE_VERSION,
            fileName = fileName,
            url = "https://nodejs.org/download/release/v$NODE_VERSION/$fileName",
            sha256 = sha256,
            sizeBytes = sizeBytes,
            preferredUrls = listOf("https://cdn.npmmirror.com/binaries/node/v$NODE_VERSION/$fileName"),
        )
    }
}
