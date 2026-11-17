package io.github.mangi.eta.agent.skill

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.security.MessageDigest

/** Copies inert, versioned Skill files into the existing run workspace; never executes them. */
internal class SkillRuntimePreparer(
    skillsRoot: File,
    private val maxFileBytes: Long = 8L * 1024 * 1024,
    private val maxTotalBytes: Long = 32L * 1024 * 1024,
    private val maxFiles: Int = 512,
) {
    private val root = skillsRoot.canonicalFile

    data class Prepared(
        val androidPath: String,
        val linuxPath: String,
        val contentSha256: String,
        val version: String?,
        val files: Int,
        val bytes: Long,
    )

    fun prepare(entry: SkillIndexEntry, workspace: File, linuxWorkspace: String): Prepared =
        SkillMutationLock.withLock(root) {
            require(entry.installed && entry.enabled) { "Skill 尚未安装或已停用" }
            require(Regex("[a-z0-9]+(?:-[a-z0-9]+)*").matches(entry.id)) { "Skill id 无效" }
            val source = File(entry.rootPath)
            require(!Files.isSymbolicLink(source.toPath()) && source.canonicalFile.parentFile == root) { "Skill 根目录不安全" }
            val listed = listFiles(source)
            require(File(source, "SKILL.md").length() <= minOf(maxFileBytes, 512L * 1024)) { "Skill 清单超过大小限制" }
            val parsed = SkillParser.parseSkillFile(File(source, "SKILL.md")) ?: throw IOException("Skill 清单不可读")
            require(parsed.frontmatter["name"] == entry.id) { "Skill 版本或身份已变化，请重新读取" }
            val currentDeclaration = SkillDeclarationParser.parse(parsed.frontmatter,
                parsed.frontmatter["metadata"]?.let(SkillParser::parseIndentedBlock).orEmpty())
            require(currentDeclaration.compatibilityProblems.isEmpty()) { currentDeclaration.compatibilityProblems.joinToString("；") }
            val manifest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val copies = listed.map { relative ->
                val file = File(source, relative)
                require(file.length() <= maxFileBytes) { "Skill 单个资源超过 $maxFileBytes 字节" }
                val bytes = readBounded(file, minOf(maxFileBytes, maxTotalBytes - total))
                total += bytes.size
                require(total <= maxTotalBytes) { "Skill 资源总量超过 $maxTotalBytes 字节" }
                manifest.update(relative.toByteArray(Charsets.UTF_8))
                manifest.update(0.toByte())
                manifest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
                manifest.update(0.toByte())
                manifest.update(bytes)
                relative to bytes
            }
            val digest = manifest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
            require(!Files.isSymbolicLink(workspace.toPath())) { "运行工作区不允许符号链接" }
            if (!workspace.exists()) require(workspace.mkdirs()) { "运行工作区不可用" }
            val canonicalWorkspace = workspace.canonicalFile
            val namespace = childDirectory(canonicalWorkspace, ".nexa-skills")
            val owner = childDirectory(namespace, entry.id)
            val target = File(owner, digest)
            if (Files.exists(target.toPath(), NOFOLLOW_LINKS)) {
                require(!Files.isSymbolicLink(target.toPath()) && target.isDirectory) { "Skill 运行资源目录不安全" }
                require(listFiles(target) == copies.map { it.first }) { "Skill 运行资源已变化，未覆盖" }
                copies.forEach { (relative, bytes) ->
                    require(readBounded(File(target, relative), maxFileBytes).contentEquals(bytes)) { "Skill 运行资源已变化，未覆盖" }
                }
            } else {
                val staging = Files.createTempDirectory(owner.toPath(), ".prepare-").toFile()
                try {
                    copies.forEach { (relative, bytes) ->
                        val parts = relative.split('/')
                        val parent = parts.dropLast(1).fold(staging) { parent, part -> childDirectory(parent, part) }
                        val destination = File(parent, parts.last())
                        require(!Files.exists(destination.toPath(), NOFOLLOW_LINKS)) { "Skill 资源目标冲突" }
                        Files.write(destination.toPath(), bytes, java.nio.file.StandardOpenOption.CREATE_NEW)
                    }
                    require(staging.canonicalFile.parentFile == owner && target.canonicalFile.parentFile == owner) { "Skill 运行资源目标越界" }
                    require(!Files.exists(target.toPath(), NOFOLLOW_LINKS) && staging.renameTo(target)) { "Skill 运行资源提交失败" }
                } finally {
                    // The generated staging path is a direct child of the validated owner directory.
                    if (staging.exists() && staging.canonicalFile.parentFile == owner && !Files.isSymbolicLink(staging.toPath())) {
                        staging.walkTopDown().onEnter { !Files.isSymbolicLink(it.toPath()) }
                            .toList().sortedByDescending { it.toPath().nameCount }.forEach { it.delete() }
                    }
                }
            }
            Prepared(
                androidPath = target.absolutePath,
                linuxPath = linuxWorkspace.trimEnd('/') + "/.nexa-skills/${entry.id}/$digest",
                contentSha256 = digest,
                version = currentDeclaration.version ?: entry.provenance?.version,
                files = copies.size,
                bytes = total,
            )
        }

    private fun listFiles(directory: File): List<String> {
        require(directory.isDirectory && !Files.isSymbolicLink(directory.toPath())) { "Skill 资源目录无效" }
        val result = mutableListOf<String>()
        var entries = 0
        val pending = ArrayDeque<File>().apply { add(directory) }
        while (pending.isNotEmpty()) {
            val parent = pending.removeFirst()
            val children = parent.listFiles() ?: throw IOException("Skill 资源目录不可读")
            children.forEach { file ->
                require(++entries <= maxFiles * 2) { "Skill 目录条目过多" }
                require(!Files.isSymbolicLink(file.toPath())) { "Skill 资源不允许符号链接" }
                val relative = file.relativeTo(directory).invariantSeparatorsPath
                val parts = relative.split('/')
                require(parts.size <= 16 && parts.all {
                    it.isNotBlank() && it !in setOf(".", "..") && it.none { character -> character.isISOControl() || character in "\\:" }
                }) { "Skill 资源路径不安全" }
                require(file.canonicalFile.toPath().startsWith(directory.canonicalFile.toPath())) { "Skill 资源越界" }
                when {
                    Files.isDirectory(file.toPath(), NOFOLLOW_LINKS) -> pending.add(file)
                    Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS) -> { result += relative; require(result.size <= maxFiles) { "Skill 文件数超过 $maxFiles" } }
                    else -> throw IOException("Skill 资源不是普通文件")
                }
            }
        }
        return result.sorted()
    }

    private fun childDirectory(parent: File, name: String): File {
        val child = File(parent, name)
        if (!Files.exists(child.toPath(), NOFOLLOW_LINKS)) Files.createDirectory(child.toPath())
        require(Files.isDirectory(child.toPath(), NOFOLLOW_LINKS) && !Files.isSymbolicLink(child.toPath()) && child.canonicalFile.parentFile == parent) { "Skill 运行资源目录不安全" }
        return child.canonicalFile
    }

    private fun readBounded(file: File, limit: Long): ByteArray {
        require(limit >= 0 && file.length() <= limit && !Files.isSymbolicLink(file.toPath())) { "Skill 资源超过大小限制或路径不安全" }
        return file.inputStream().use { input ->
            val bytes = input.readNBytes((minOf(limit, Int.MAX_VALUE.toLong() - 1) + 1).toInt())
            require(bytes.size.toLong() <= limit) { "Skill 资源读取超过大小限制" }
            bytes
        }
    }
}
